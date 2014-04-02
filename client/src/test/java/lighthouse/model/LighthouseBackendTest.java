package lighthouse.model;

import com.google.bitcoin.core.*;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.testing.FakeTxBuilder;
import com.google.bitcoin.testing.InboundMessageQueuer;
import com.google.bitcoin.testing.TestWithPeerGroup;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import lighthouse.LighthouseBackend;
import lighthouse.files.AppDirectory;
import lighthouse.files.DiskManager;
import lighthouse.protocol.Ex;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.Project;
import lighthouse.protocol.TestUtils;
import lighthouse.threading.AffinityExecutor;
import lighthouse.wallet.PledgingWallet;
import org.javatuples.Triplet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.spongycastle.crypto.params.ECPrivateKeyParameters;
import org.spongycastle.crypto.signers.ECDSASigner;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.bitcoin.testing.FakeTxBuilder.createFakeBlock;
import static java.net.HttpURLConnection.HTTP_OK;
import static lighthouse.LighthouseBackend.Mode.CLIENT;
import static lighthouse.LighthouseBackend.Mode.SERVER;
import static lighthouse.protocol.LHUtils.*;
import static org.junit.Assert.*;

public class LighthouseBackendTest extends TestWithPeerGroup {
    private LighthouseBackend backend;
    private Path tmpDir;
    private AffinityExecutor.Gate gate;
    private Project project;
    private LinkedBlockingQueue<HttpExchange> httpReqs;
    private ProjectModel projectModel;
    private HttpServer localServer;
    private Address to;
    private VersionMessage supportingVer;
    private PledgingWallet pledgingWallet;
    private AffinityExecutor.ServiceAffinityExecutor executor;
    private DiskManager diskManager;

    private LHProtos.Pledge injectedPledge;

    public LighthouseBackendTest() {
        super(ClientType.BLOCKING_CLIENT_MANAGER);
    }

    @Override
    @Before
    public void setUp() throws Exception {
        pledgingWallet = new PledgingWallet(params) {
            @Nullable
            @Override
            public LHProtos.Pledge getPledgeFor(Project project) {
                if (injectedPledge != null) {
                    return injectedPledge;
                } else {
                    return super.getPledgeFor(project);
                }
            }

            @Override
            public Set<LHProtos.Pledge> getPledges() {
                if (injectedPledge != null)
                    return ImmutableSet.<LHProtos.Pledge>builder().addAll(super.getPledges()).add(injectedPledge).build();
                else
                    return super.getPledges();
            }
        };
        wallet = pledgingWallet;
        super.setUp();
        BriefLogFormatter.init();

        tmpDir = Files.createTempDirectory("lighthouse-dmtest");
        AppDirectory.overrideAppDir(tmpDir);
        AppDirectory.initAppDir("lhtests");

        // Give data backend its own thread. The "gate" lets us just run commands in the context of the unit test thread.
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                e.printStackTrace();
                fail("Uncaught exception");
            }
        });
        gate = new AffinityExecutor.Gate();
        executor = new AffinityExecutor.ServiceAffinityExecutor("test thread");
        diskManager = new DiskManager(executor);
        backend = new LighthouseBackend(CLIENT, peerGroup, blockChain, pledgingWallet, diskManager, executor);
        backend.setMinPeersForUTXOQuery(1);
        backend.setMaxJitterSeconds(0);

        // Wait to start up.
        backend.executor.fetchFrom(() -> null);

        projectModel = new ProjectModel(pledgingWallet);
        to = new ECKey().toAddress(params);
        projectModel.address.set(to.toString());
        projectModel.title.set("Foo");
        projectModel.memo.set("Bar");
        projectModel.goalAmount.set(Coin.COIN.value);
        project = projectModel.getProject();

        supportingVer = new VersionMessage(params, 1);
        supportingVer.localServices = VersionMessage.NODE_NETWORK | VersionMessage.NODE_GETUTXOS;
        supportingVer.clientVersion = GetUTXOsMessage.MIN_PROTOCOL_VERSION;

        httpReqs = new LinkedBlockingQueue<>();
        localServer = HttpServer.create(new InetSocketAddress("localhost", HTTP_LOCAL_TEST_PORT), 100);
        localServer.createContext(HTTP_PATH_PREFIX, exchange -> {
            gate.checkOnThread();
            Uninterruptibles.putUninterruptibly(httpReqs, exchange);
        });
        localServer.setExecutor(gate);
        localServer.start();

        // Make peers selected for tx broadcast deterministic.
        TransactionBroadcast.random = new Random(1);
    }

    @After
    public void tearDown() {
        super.tearDown();
        executor.service.shutdown();
        localServer.stop(Integer.MAX_VALUE);
    }

    @Test
    public void projectCreated() throws Exception {
        // Check that if we save a project, we get a set change mirrored back into our own thread and the file is
        // stored to disk correctly.
        ObservableList<Project> projects = backend.mirrorProjects(gate);
        assertEquals(0, projects.size());
        backend.saveProject(project);
        assertEquals(0, projects.size());
        gate.waitAndRun();
        assertEquals(1, projects.size());
        assertEquals("Foo", projects.iterator().next().getTitle());
        assertTrue(Files.exists(tmpDir.resolve("Foo" + DiskManager.PROJECT_FILE_EXTENSION)));
    }

    private void sendServerStatus(LHProtos.Pledge scrubbedPledge, HttpExchange exchange) throws IOException {
        LHProtos.ProjectStatus.Builder status = LHProtos.ProjectStatus.newBuilder();
        status.setId(project.getID());
        status.setTimestamp(Instant.now().getEpochSecond());
        status.setValuePledgedSoFar(Coin.COIN.value);
        status.addPledges(scrubbedPledge);
        byte[] bits = status.build().toByteArray();
        exchange.sendResponseHeaders(HTTP_OK, bits.length);
        exchange.getResponseBody().write(bits);
        exchange.close();
    }

    @Test
    public void projectAddedWithServer() throws Exception {
        // Check that if we add a path containing a project, it's noticed and the projects set is updated.
        // Also check that the status is queried from the HTTP server it's linked to.

        projectModel.serverName.set("localhost");
        project = projectModel.getProject();
        final LHProtos.Pledge scrubbedPledge = makeScrubbedPledge();

        Path projectPath = writeProjectToDisk();
        ObservableList<Project> projects = backend.mirrorProjects(gate);
        assertEquals(0, projects.size());
        backend.addProjectFile(projectPath);
        assertEquals(0, projects.size());
        gate.waitAndRun();
        // Is now loaded from disk.
        assertEquals(1, projects.size());
        final Project project1 = projects.iterator().next();
        assertEquals("Foo", project1.getTitle());

        // Let's watch out for pledges from the server.
        ObservableSet<LHProtos.Pledge> pledges = backend.mirrorOpenPledges(project1, gate);

        // HTTP request was made to server to learn about existing pledges.
        gate.waitAndRun();
        HttpExchange exchange = httpReqs.take();
        sendServerStatus(scrubbedPledge, exchange);

        // We got a pledge list update relayed into our thread.
        pledges.addListener((SetChangeListener<LHProtos.Pledge>) c -> {
            assertTrue(c.wasAdded());
        });
        gate.waitAndRun();
        assertEquals(1, pledges.size());
        assertEquals(Coin.COIN.value, pledges.iterator().next().getTotalInputValue());
    }

    private LHProtos.Pledge makeScrubbedPledge() {
        final LHProtos.Pledge pledge = LHProtos.Pledge.newBuilder()
                .setTotalInputValue(Coin.COIN.value)
                .setProjectId(project.getID())
                .setTimestamp(Utils.currentTimeSeconds())
                .addTransactions(ByteString.copyFromUtf8("not a real tx"))
                .build();
        final Sha256Hash origHash = Sha256Hash.create(pledge.toByteArray());
        return pledge.toBuilder()
                .clearTransactions()
                .setOrigHash(ByteString.copyFrom(origHash.getBytes()))
                .build();
    }

    @Test
    public void serverCheckStatus() throws Exception {
        // Check that the server status map is updated correctly.
        projectModel.serverName.set("localhost");
        project = projectModel.getProject();
        final LHProtos.Pledge scrubbedPledge = makeScrubbedPledge();
        ObservableMap<Project, LighthouseBackend.CheckStatus> statuses = backend.mirrorCheckStatuses(gate);
        assertEquals(0, statuses.size());
        Path projectPath = writeProjectToDisk();
        backend.addProjectFile(projectPath);
        gate.waitAndRun();
        // Is now loaded from disk.
        assertEquals(1, statuses.size());
        assertNotNull(statuses.get(project));
        assertTrue(statuses.get(project).inProgress);
        assertNull(statuses.get(project).error);
        // Doing request to server.
        gate.waitAndRun();
        HttpExchange exchange = httpReqs.take();
        exchange.sendResponseHeaders(404, -1);   // not found!
        gate.waitAndRun();
        // Error shows up in map.
        assertEquals(1, statuses.size());
        assertFalse(statuses.get(project).inProgress);
        final Throwable error = statuses.get(project).error;
        assertNotNull(error);
        assertEquals(java.io.FileNotFoundException.class, error.getClass());
        // Try again ...
        backend.refreshProjectStatusFromServer(project);
        gate.waitAndRun();
        assertEquals(1, statuses.size());
        assertTrue(statuses.get(project).inProgress);
        gate.waitAndRun();
        exchange = httpReqs.take();
        sendServerStatus(scrubbedPledge, exchange);
        gate.waitAndRun();
        assertEquals(0, statuses.size());
    }

    @Test
    public void serverAndLocalAreDeduped() throws Exception {
        // Verify that if the backend knows about a pledge, and receives the same pledge back in scrubbed form,
        // it knows they are the same and doesn't duplicate.
        projectModel.serverName.set("localhost");
        project = projectModel.getProject();
        final LHProtos.Pledge pledge = LHProtos.Pledge.newBuilder()
                .setTotalInputValue(Coin.COIN.value)
                .setProjectId(project.getID())
                .setTimestamp(Utils.currentTimeSeconds())
                .addTransactions(ByteString.copyFromUtf8("not a real tx"))
                .build();
        final Sha256Hash origHash = Sha256Hash.create(pledge.toByteArray());
        final LHProtos.Pledge scrubbedPledge = pledge.toBuilder()
                .clearTransactions()
                .setOrigHash(ByteString.copyFrom(origHash.getBytes()))
                .build();

        Path projectPath = writeProjectToDisk();
        // Make the wallet return the above pledge without having to screw around with actually using the wallet.
        injectedPledge = pledge;
        executor.service.shutdown();
        executor.service.awaitTermination(5, TimeUnit.SECONDS);
        executor = new AffinityExecutor.ServiceAffinityExecutor("test thread");
        diskManager = new DiskManager(executor);
        diskManager.addProjectFile(projectPath);
        backend = new LighthouseBackend(CLIENT, peerGroup, blockChain, pledgingWallet, diskManager, executor);

        // Let's watch out for pledges from the server.
        ObservableSet<LHProtos.Pledge> pledges = backend.mirrorOpenPledges(project, gate);
        assertEquals(1, pledges.size());
        assertEquals(pledge, pledges.iterator().next());

        // HTTP request was made to server to learn about existing pledges.
        gate.waitAndRun();
        sendServerStatus(scrubbedPledge, httpReqs.take());

        // Because we want to test the absence of action in an async process, we forcibly repeat the server lookup
        // that just occurred so we can wait for it, and be sure that the scrubbed version of our own pledge was not
        // mistakenly added. Attempting to just test here without waiting would race, as the backend is processing
        // the reply we have above in parallel.
        CompletableFuture future = backend.refreshProjectStatusFromServer(project);
        gate.waitAndRun();
        sendServerStatus(scrubbedPledge, httpReqs.take());
        future.get();
        assertEquals(0, gate.getTaskQueueSize());    // No pending set changes now.
        assertEquals(1, pledges.size());
        assertEquals(pledge, pledges.iterator().next());
    }

    @Test
    public void projectAddedP2P() throws Exception {
        peerGroup.startAsync();
        peerGroup.awaitRunning();

        // Check that if we add an path containing a project, it's noticed and the projects set is updated.
        // Also check that pledges are loaded from disk and checked against the P2P network.
        Path projectPath = writeProjectToDisk();
        ObservableList<Project> projects = backend.mirrorProjects(gate);
        backend.addProjectFile(projectPath);
        gate.waitAndRun();
        // Is now loaded from disk.
        assertEquals(1, projects.size());

        // P2P getutxo message was used to find out if the pledge was already revoked.
        Triplet<Transaction, Transaction, LHProtos.Pledge> data = TestUtils.makePledge(project, to, project.getGoalAmount());
        Transaction stubTx = data.getValue0();
        Transaction pledgeTx = data.getValue1();
        LHProtos.Pledge pledge = data.getValue2();

        // Let's watch out for pledges as they are loaded from disk and checked.
        ObservableSet<LHProtos.Pledge> pledges = backend.mirrorOpenPledges(project, gate);

        // The user drops the pledge.
        try (OutputStream stream = Files.newOutputStream(projectPath.getParent().resolve("dropped-pledge" + DiskManager.PLEDGE_FILE_EXTENSION))) {
            pledge.writeTo(stream);
        }

        // App finds a peer that supports getutxo.
        InboundMessageQueuer p1 = connectPeer(1);
        assertNull(outbound(p1));
        InboundMessageQueuer p2 = connectPeer(2, supportingVer);
        GetUTXOsMessage getutxos = (GetUTXOsMessage) waitForOutbound(p2);
        assertNotNull(getutxos);
        assertEquals(pledgeTx.getInput(0).getOutpoint(), getutxos.getOutPoints().get(0));

        // We reply with the data it expects.
        inbound(p2, new UTXOsMessage(params,
                ImmutableList.of(stubTx.getOutput(0)),
                new long[]{UTXOsMessage.MEMPOOL_HEIGHT},
                blockStore.getChainHead().getHeader().getHash(),
                blockStore.getChainHead().getHeight()));

        // We got a pledge list update relayed into our thread.
        AtomicBoolean flag = new AtomicBoolean(false);
        pledges.addListener((SetChangeListener<LHProtos.Pledge>) c -> {
            flag.set(c.wasAdded());
        });
        gate.waitAndRun();
        assertTrue(flag.get());
        assertEquals(1, pledges.size());
        final LHProtos.Pledge pledge2 = pledges.iterator().next();
        assertEquals(Coin.COIN.value / 2, pledge2.getTotalInputValue());

        // New block: let's pretend this block contains a revocation transaction. LighthouseBackend should recheck.
        Block newBlock = com.google.bitcoin.testing.FakeTxBuilder.makeSolvedTestBlock(blockStore, new ECKey().toAddress(params));
        inbound(p1, newBlock);
        // TODO: Replace this with just watching for revocations using the Bloom filter.
        getutxos = (GetUTXOsMessage) waitForOutbound(p2);
        assertNotNull(getutxos);
        assertEquals(pledgeTx.getInput(0).getOutpoint(), getutxos.getOutPoints().get(0));
        final ArrayList<TransactionOutput> empty = new ArrayList<>(1);
        empty.add(null);
        inbound(p2, new UTXOsMessage(params, empty,
                new long[]{UTXOsMessage.MEMPOOL_HEIGHT},
                blockStore.getChainHead().getHeader().getHash(),
                blockStore.getChainHead().getHeight()));
        gate.waitAndRun();
        assertEquals(0, pledges.size());   // was revoked

        peerGroup.stopAsync();
        peerGroup.awaitTerminated();
    }

    private Path writeProjectToDisk() throws IOException {
        Path dropDir = Files.createTempDirectory("lighthouse-droptest");
        Path file = dropDir.resolve("dropped-project" + DiskManager.PROJECT_FILE_EXTENSION);
        try (OutputStream stream = Files.newOutputStream(file)) {
            project.getProto().writeTo(stream);
        }
        return file;
    }

    @Test
    public void mergePeerAnswers() throws Exception {
        // Check that we throw an exception if peers disagree on the state of the UTXO set. Such a pledge would
        // be considered invalid.
        peerGroup.startAsync();
        peerGroup.awaitRunning();

        InboundMessageQueuer p1 = connectPeer(1, supportingVer);
        InboundMessageQueuer p2 = connectPeer(2, supportingVer);
        InboundMessageQueuer p3 = connectPeer(3, supportingVer);

        // Set ourselves up to check a pledge.
        ObservableList<Project> projects = backend.mirrorProjects(gate);
        ObservableMap<Project, LighthouseBackend.CheckStatus> statuses = backend.mirrorCheckStatuses(gate);

        Path projectPath = writeProjectToDisk();
        backend.addProjectFile(projectPath);
        gate.waitAndRun();
        assertEquals(1, projects.size());
        // This triggers a Bloom filter update so we can spot claims.
        checkBloomFilter(p1, p2, p3);

        Triplet<Transaction, Transaction, LHProtos.Pledge> data = TestUtils.makePledge(project, to, project.getGoalAmount());
        Transaction stubTx = data.getValue0();
        Transaction pledgeTx = data.getValue1();
        LHProtos.Pledge pledge = data.getValue2();
        try (OutputStream stream = Files.newOutputStream(projectPath.getParent().resolve("dropped-pledge" + DiskManager.PLEDGE_FILE_EXTENSION))) {
            pledge.writeTo(stream);
        }

        gate.waitAndRun();
        assertEquals(1, statuses.size());
        assertTrue(statuses.get(project).inProgress);

        // App finds a few peers that support getutxos and queries all of them.
        GetUTXOsMessage getutxos1, getutxos2, getutxos3;
        getutxos1 = (GetUTXOsMessage) waitForOutbound(p1);
        getutxos2 = (GetUTXOsMessage) waitForOutbound(p2);
        getutxos3 = (GetUTXOsMessage) waitForOutbound(p3);
        assertNotNull(getutxos1);
        assertNotNull(getutxos2);
        assertNotNull(getutxos3);
        assertEquals(getutxos1, getutxos2);
        assertEquals(getutxos2, getutxos3);
        assertEquals(pledgeTx.getInput(0).getOutpoint(), getutxos1.getOutPoints().get(0));

        // Two peers reply with the data it expects, one replies with a lie (claiming unspent when really spent).
        UTXOsMessage lie = new UTXOsMessage(params,
                ImmutableList.of(stubTx.getOutput(0)),
                new long[]{UTXOsMessage.MEMPOOL_HEIGHT},
                blockStore.getChainHead().getHeader().getHash(),
                blockStore.getChainHead().getHeight());
        UTXOsMessage correct = new UTXOsMessage(params,
                ImmutableList.of(),
                new long[]{},
                blockStore.getChainHead().getHeader().getHash(),
                blockStore.getChainHead().getHeight());
        inbound(p1, correct);
        inbound(p2, lie);
        inbound(p3, correct);

        gate.waitAndRun();
        assertEquals(1, statuses.size());
        assertFalse(statuses.get(project).inProgress);
        assertTrue(statuses.get(project).error instanceof Ex.InconsistentUTXOAnswers);
    }

    private BloomFilter checkBloomFilter(InboundMessageQueuer... peers) throws InterruptedException {
        BloomFilter result = null;
        for (InboundMessageQueuer peer : peers) {
            result = (BloomFilter) waitForOutbound(peer);
            assertTrue(waitForOutbound(peer) instanceof MemoryPoolMessage);
        }
        return result;
    }

    @Test
    public void pledgeAddedViaWallet() throws Exception {
        Path projectPath = writeProjectToDisk();
        ObservableList<Project> projects = backend.mirrorProjects(gate);
        backend.addProjectFile(projectPath);
        gate.waitAndRun();
        // Is now loaded from disk.
        assertEquals(1, projects.size());

        Transaction payment = FakeTxBuilder.createFakeTx(params, Coin.COIN, pledgingWallet.currentReceiveAddress());
        FakeTxBuilder.BlockPair bp = createFakeBlock(blockStore, payment);
        wallet.receiveFromBlock(payment, bp.storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
        wallet.notifyNewBestBlock(bp.storedBlock);
        PledgingWallet.PendingPledge pendingPledge = pledgingWallet.createPledge(project, Coin.COIN.value / 2, null);

        ObservableSet<LHProtos.Pledge> pledges = backend.mirrorOpenPledges(project, gate);
        assertEquals(0, pledges.size());
        LHProtos.Pledge proto = pendingPledge.commit(true);
        gate.waitAndRun();
        assertEquals(1, pledges.size());
        assertEquals(proto, pledges.iterator().next());
    }

    @Test
    public void submitPledgeViaHTTP() throws Exception {
        backend = new LighthouseBackend(SERVER, peerGroup, blockChain, pledgingWallet, diskManager, executor);
        backend.setMinPeersForUTXOQuery(1);
        backend.setMaxJitterSeconds(0);
        // Test the process of broadcasting a pledge's dependencies, then checking the UTXO set to see if it was
        // revoked already. If all is OK then it should show up in the verified pledges set.
        peerGroup.setMinBroadcastConnections(2);
        peerGroup.startAsync();
        peerGroup.awaitRunning();

        Triplet<Transaction, Transaction, LHProtos.Pledge> data = TestUtils.makePledge(project, to, project.getGoalAmount());
        Transaction stubTx = data.getValue0();
        Transaction pledgeTx = data.getValue1();
        LHProtos.Pledge pledge = data.getValue2();

        backend.addProjectFile(writeProjectToDisk());

        ObservableSet<LHProtos.Pledge> pledges = backend.mirrorOpenPledges(project, gate);

        // The dependency TX doesn't really have to be a dependency at the moment, it could be anything so we lazily
        // just make an unrelated fake tx to check the ordering of things.
        Transaction depTx = FakeTxBuilder.createFakeTx(params, Coin.COIN, address);
        pledge = pledge.toBuilder().setTransactions(0, ByteString.copyFrom(depTx.bitcoinSerialize()))
                                   .addTransactions(ByteString.copyFrom(pledgeTx.bitcoinSerialize()))
                                   .build();

        InboundMessageQueuer p1 = connectPeer(1);
        InboundMessageQueuer p2 = connectPeer(2, supportingVer);

        // Pledge is submitted to the server via HTTP.
        CompletableFuture<LHProtos.Pledge> future = backend.submitPledge(project, pledge);
        assertFalse(future.isDone());

        // Broadcast happens.
        Transaction broadcast = (Transaction) waitForOutbound(p1);
        assertEquals(depTx, broadcast);
        assertNull(outbound(p2));
        InventoryMessage inv = new InventoryMessage(params);
        inv.addTransaction(depTx);
        inbound(p2, inv);
        // Broadcast is now complete, so query.
        GetUTXOsMessage getutxos = (GetUTXOsMessage) waitForOutbound(p2);
        assertNotNull(getutxos);
        assertEquals(pledgeTx.getInput(0).getOutpoint(), getutxos.getOutPoints().get(0));

        // We reply with the data it expects.
        inbound(p2, new UTXOsMessage(params,
                ImmutableList.of(stubTx.getOutput(0)),
                new long[]{UTXOsMessage.MEMPOOL_HEIGHT},
                blockStore.getChainHead().getHeader().getHash(),
                blockStore.getChainHead().getHeight()));

        // We got a pledge list update relayed into our thread.
        AtomicBoolean flag = new AtomicBoolean(false);
        pledges.addListener((SetChangeListener<LHProtos.Pledge>) c -> {
            flag.set(c.wasAdded());
        });
        gate.waitAndRun();
        assertTrue(flag.get());
        assertEquals(1, pledges.size());
        final LHProtos.Pledge pledge2 = pledges.iterator().next();
        assertEquals(Coin.COIN.value / 2, pledge2.getTotalInputValue());

        future.get();

        // And the pledge was saved to disk named after the hash of the pledge contents.
        final Sha256Hash pledgeHash = Sha256Hash.create(pledge.toByteArray());
        final List<Path> dirFiles = mapList(listDir(AppDirectory.dir()), Path::getFileName);
        assertTrue(dirFiles.contains(Paths.get(pledgeHash.toString() + DiskManager.PLEDGE_FILE_EXTENSION)));

        peerGroup.stopAsync();
        peerGroup.awaitTerminated();
    }

    @Test
    public void claimServerless() throws Exception {
        // Create enough pledges to satisfy the project, broadcast the claim transaction, make sure the backend
        // spots the claim and understands the current state of the project.
        peerGroup.setMinBroadcastConnections(2);
        peerGroup.startAsync();
        peerGroup.awaitRunning();

        Path projectPath = writeProjectToDisk();
        backend.addProjectFile(projectPath);

        ObservableSet<LHProtos.Pledge> openPledges = backend.mirrorOpenPledges(project, gate);
        ObservableSet<LHProtos.Pledge> claimedPledges = backend.mirrorClaimedPledges(project, gate);
        assertEquals(0, claimedPledges.size());
        assertEquals(0, openPledges.size());

        Triplet<Transaction, Transaction, LHProtos.Pledge> data = TestUtils.makePledge(project, to, project.getGoalAmount());
        LHProtos.Pledge pledge1 = data.getValue2();

        Triplet<Transaction, Transaction, LHProtos.Pledge> data2 = TestUtils.makePledge(project, to, project.getGoalAmount());
        LHProtos.Pledge pledge2 = data2.getValue2();

        // The user drops the pledges.
        try (OutputStream stream = Files.newOutputStream(projectPath.getParent().resolve("dropped-pledge1" + DiskManager.PLEDGE_FILE_EXTENSION))) {
            pledge1.writeTo(stream);
        }
        try (OutputStream stream = Files.newOutputStream(projectPath.getParent().resolve("dropped-pledge2" + DiskManager.PLEDGE_FILE_EXTENSION))) {
            pledge2.writeTo(stream);
        }

        InboundMessageQueuer p1 = connectPeer(1);
        InboundMessageQueuer p2 = connectPeer(2, supportingVer);
        doGetUTXOAnswer(data.getValue0().getOutput(0), p2);
        doGetUTXOAnswer(data2.getValue0().getOutput(0), p2);

        gate.waitAndRun();
        gate.waitAndRun();
        assertEquals(2, openPledges.size());

        ObservableMap<String, LighthouseBackend.ProjectStateInfo> states = backend.mirrorProjectStates(gate);
        assertEquals(LighthouseBackend.ProjectState.OPEN, states.get(project.getID()).state);

        Transaction contract = project.completeContract(ImmutableSet.of(pledge1, pledge2));
        inbound(p1, InventoryMessage.with(contract));
        waitForOutbound(p1);   // getdata for the contract.
        inbound(p2, InventoryMessage.with(contract));
        inbound(p1, contract);
        GetDataMessage gdm = (GetDataMessage) waitForOutbound(p1);   // Dep resolution.
        inbound(p1, new NotFoundMessage(params, gdm.getItems()));

        for (int i = 0; i < 5; i++) {
            gate.waitAndRun();   // updates to lists and things.
        }

        assertEquals(LighthouseBackend.ProjectState.CLAIMED, states.get(project.getID()).state);
        assertEquals(contract.getHash(), states.get(project.getID()).claimedBy);
        assertTrue(Files.exists(AppDirectory.dir().resolve(DiskManager.PROJECT_STATUS_FILENAME)));

        assertEquals(2, claimedPledges.size());
        assertTrue(claimedPledges.contains(pledge1));
        assertTrue(claimedPledges.contains(pledge2));

        // TODO: Craft a test that verifies double spending of the claim is handled properly.

        peerGroup.stopAsync();
        peerGroup.awaitTerminated();
    }

    @Test
    public void duplicatePledgesNotAllowed() throws Exception {
        // Pledges should not share outputs, otherwise someone could pledge the same money twice either by accident
        // or maliciously. Note that in the case where two pledges have two different dependencies that both double
        // spend the same output, this will be caught by the backend trying to broadcast the dependencies itself
        // (in the server case), and then observing that the second pledge has a dependency that's failing to propagate.
        Path projectPath = writeProjectToDisk();
        backend.addProjectFile(projectPath);
        ObservableSet<LHProtos.Pledge> openPledges = backend.mirrorOpenPledges(project, gate);

        peerGroup.setMinBroadcastConnections(2);
        peerGroup.startAsync();
        peerGroup.awaitRunning();

        Transaction doubleSpentTx = new Transaction(params);
        doubleSpentTx.addInput(TestUtils.makeRandomInput());

        // Make a key that doesn't use deterministic signing, to make it easy for us to double spend with bitwise
        // different pledges.
        ECKey signingKey = new ECKey() {
            @Override
            protected ECDSASignature doSign(Sha256Hash input, BigInteger privateKeyForSigning) {
                ECDSASigner signer = new ECDSASigner();
                ECPrivateKeyParameters privKey = new ECPrivateKeyParameters(privateKeyForSigning, CURVE);
                signer.init(true, privKey);
                BigInteger[] components = signer.generateSignature(input.getBytes());
                return new ECDSASignature(components[0], components[1]).toCanonicalised();
            }
        };
        TransactionOutput output = doubleSpentTx.addOutput(Coin.COIN.divide(2), signingKey.toAddress(params));

        LHProtos.Pledge.Builder pledge1 = makeSimpleHalfPledge(signingKey, output);
        LHProtos.Pledge.Builder pledge2 = makeSimpleHalfPledge(signingKey, output);
        assertNotEquals(pledge1.getTransactions(0), pledge2.getTransactions(0));

        ObservableMap<Project, LighthouseBackend.CheckStatus> statuses = backend.mirrorCheckStatuses(gate);

        InboundMessageQueuer p1 = connectPeer(1, supportingVer);
        InboundMessageQueuer p2 = connectPeer(2, supportingVer);
        // User drops pledge 1
        try (OutputStream stream = Files.newOutputStream(projectPath.getParent().resolve("dropped-pledge1" + DiskManager.PLEDGE_FILE_EXTENSION))) {
            pledge1.build().writeTo(stream);
        }
        doGetUTXOAnswer(output, p1);
        doGetUTXOAnswer(output, p2);
        gate.waitAndRun();   // statuses (start lookup)
        gate.waitAndRun();   // openPledges
        gate.waitAndRun();   // statuses (end lookup)

        // First pledge is accepted.
        assertEquals(1, openPledges.size());
        assertEquals(pledge1.build(), openPledges.iterator().next());

        // User drops pledge 2
        try (OutputStream stream = Files.newOutputStream(projectPath.getParent().resolve("dropped-pledge2" + DiskManager.PLEDGE_FILE_EXTENSION))) {
            pledge2.build().writeTo(stream);
        }
        doGetUTXOAnswer(output, p1);
        doGetUTXOAnswer(output, p2);

        // Wait for check status to update.
        gate.waitAndRun();   // statuses (start lookup)
        gate.waitAndRun();   // statuses (error result)
        //noinspection ConstantConditions
        assertEquals(VerificationException.DuplicatedOutPoint.class, statuses.get(project).error.getClass());

        peerGroup.stopAsync();
        peerGroup.awaitTerminated();
    }

    private LHProtos.Pledge.Builder makeSimpleHalfPledge(ECKey signingKey, TransactionOutput output) {
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        Transaction tx = new Transaction(params);
        tx.addOutput(project.getOutputs().get(0));   // Project output.
        tx.addSignedInput(output, signingKey, Transaction.SigHash.ALL, true);
        pledge.addTransactions(ByteString.copyFrom(tx.bitcoinSerialize()));
        pledge.setTotalInputValue(Coin.COIN.divide(2).value);
        pledge.setProjectId(project.getID());
        pledge.setTimestamp(Utils.currentTimeSeconds());
        return pledge;
    }

    private void doGetUTXOAnswer(TransactionOutput output, InboundMessageQueuer p2) throws InterruptedException, BlockStoreException {
        waitForOutbound(p2);  // getutxos
        inbound(p2, new UTXOsMessage(params,
                ImmutableList.of(output),
                new long[]{UTXOsMessage.MEMPOOL_HEIGHT},
                blockStore.getChainHead().getHeader().getHash(),
                blockStore.getChainHead().getHeight()));
    }
}
