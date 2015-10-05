package lighthouse.wallet;

import com.google.common.collect.*;
import com.google.common.util.concurrent.*;
import kotlin.*;
import lighthouse.protocol.*;
import org.bitcoinj.core.*;
import org.bitcoinj.core.listeners.*;
import org.bitcoinj.params.*;
import org.bitcoinj.store.*;
import org.bitcoinj.testing.*;
import org.bitcoinj.utils.*;
import org.bitcoinj.wallet.*;
import org.junit.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.*;
import static org.bitcoinj.core.Transaction.*;
import static org.junit.Assert.*;

public class PledgingWalletTest {
    private NetworkParameters params = UnitTestParams.get();

    @Before
    public void setUp() throws Exception {
        BriefLogFormatter.init();
    }

    private LHProtos.Project makeProject(PledgingWallet wallet,  long value) throws AddressFormatException {
        final ECKey key = new DumpedPrivateKey(params, "cVbiZ5pX6xJgDuxxetwBCxu358G4TBD2fjHABP65xSmGYSjPyJnF").getKey();
        Address toAddress = key.toAddress(params);
        LHProtos.ProjectDetails.Builder details = Project.makeDetails(
                wallet.getParams(), "My cool project", "A project to make awesome things ... out of Lego!",
                toAddress, Coin.valueOf(value), wallet.freshAuthKey(), wallet.getKeyChainGroupLookaheadSize());
        LHProtos.Project.Builder projectBuilder = LHProtos.Project.newBuilder();
        projectBuilder.setSerializedPaymentDetails(details.build().toByteString());
        return projectBuilder.build();
    }

    private PledgingWallet roundtripWallet(PledgingWallet wallet) throws UnreadableWalletException {
        return PledgingWallet.deserialize(wallet.serialize());
    }

    @Test
    public void pledgePerfectSize() throws Exception {
        // Grab a Project, and a wallet with some outputs in it, then form a pledge that does not require any changes
        // to the wallets output set.
        WalletTestObjects objects = new WalletTestObjects(() -> new PledgingWallet(UnitTestParams.get()));
        PledgingWallet wallet = (PledgingWallet) objects.wallet;
        objects.sendAmounts(100_000, 200_000, 300_000);
        assertEquals(600_000, objects.wallet.getBalance().longValue());

        boolean[] flags = new boolean[2];
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onChange() {
                flags[0] = true;
            }

            @Override
            public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                flags[1] = true;
            }
        }, Threading.SAME_THREAD);
        Project project = new Project(makeProject(wallet, 100_000));
        PledgingWallet.PendingPledge pledge = wallet.createPledge(project, 100_000, null);
        assertNull(pledge.dependency);
        assertNotNull(pledge.pledge);
        final LHProtos.Pledge commit = pledge.commit(true);
        assertEquals(500_000, objects.wallet.getBalance().longValue());
        assertTrue(flags[0]);
        assertTrue(flags[1]);

        Transaction contract = project.completeContract(ImmutableSet.of(commit));
        assertEquals(contract.getInput(0).duplicateDetached(), pledge.pledge.getInput(0).duplicateDetached());
        List<TransactionOutput> outputs = ImmutableList.of(checkNotNull(pledge.pledge.getInput(0).getConnectedOutput()).duplicateDetached());
        project.verifyPledge(outpoint -> CompletableFuture.completedFuture(outputs), commit).get();
    }

    @Test
    public void pledgeImperfectSize() throws Exception {
        // Form a pledge that requires a new output to be created by the wallet (the dependency), so we have a correctly
        // sized stub output.
        WalletTestObjects objects = new WalletTestObjects(() -> new PledgingWallet(UnitTestParams.get()));
        PledgingWallet wallet = (PledgingWallet) objects.wallet;
        objects.sendAmounts(1_000_000, 2_000_000);

        Project project = new Project(makeProject(wallet, 3_000_000));
        PledgingWallet.PendingPledge pledge = wallet.createPledge(project, 2_500_000, null);
        assertNotNull(pledge.dependency);
        assertNotNull(pledge.pledge);
        assertEquals(2, pledge.dependency.getOutputs().size());
        assertTrue(2_500_000 == pledge.dependency.getOutput(0).getValue().longValue() ||
                   2_500_000 == pledge.dependency.getOutput(1).getValue().longValue());
        pledge.commit(true);

        // Do a second one. We have a 490k output available.
        PledgingWallet.PendingPledge pledge2 = wallet.createPledge(project, 400_000, null);
        assertNotNull(pledge2.dependency);
        assertNotNull(pledge2.pledge);
        assertEquals(2, pledge2.dependency.getOutputs().size());
        assertTrue(400_000 == pledge2.dependency.getOutput(0).getValue().longValue() ||
                   400_000 == pledge2.dependency.getOutput(1).getValue().longValue());
        // Check that the dependency of this pledge spends the change of the prior dependency.
        assertEquals(pledge2.dependency.getInput(0).getOutpoint().getHash(),
                pledge.dependency.getHash());

        pledge2.commit(true);
        System.out.println(roundTrip(wallet));
    }

    private static Wallet roundTrip(Wallet wallet) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new WalletProtobufSerializer().writeWallet(wallet, output);
        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        return PledgingWallet.deserialize(Protos.Wallet.parseFrom(input));
    }

    @Test(expected = InsufficientMoneyException.class)
    public void dontSpendStubs() throws Exception {
        // Form a pledge and then try to spend it with another pledge. Should reject.
        WalletTestObjects objects = new WalletTestObjects(() -> new PledgingWallet(UnitTestParams.get()));
        PledgingWallet wallet = (PledgingWallet) objects.wallet;
        objects.sendAmounts(1_000_000);
        Project project = new Project(makeProject(wallet, 3_000_000));
        PledgingWallet.PendingPledge pledge = wallet.createPledge(project, 1_000_000, null);
        pledge.commit(true);
        // Check that pledges are serialized.
        wallet = roundtripWallet(wallet);
        // This line should fail because the output we received is already pledged elsewhere.
        wallet.createPledge(project, 800_000, null);
    }

    @Test
    public void canRevokePledges() throws Exception {
        WalletTestObjects objects = new WalletTestObjects(() -> new PledgingWallet(UnitTestParams.get()));
        PledgingWallet wallet = (PledgingWallet) objects.wallet;
        objects.sendAmounts(1_000_000);

        Project project = new Project(makeProject(wallet, 3_000_000));
        PledgingWallet.PendingPledge pledge = wallet.createPledge(project, 500_000, null);
        pledge.commit(true);
        final MockTransactionBroadcaster.TxFuturePair txFuturePair = objects.broadcaster.waitForTxFuture();
        txFuturePair.succeed();
        Transaction stubTx = txFuturePair.tx;
        assertEquals(1, wallet.getPledges().size());
        assertEquals(500_000 - REFERENCE_DEFAULT_MIN_TX_FEE.longValue(), wallet.getBalance().longValue());

        // Stub confirms.
        objects.receiveViaBlock(stubTx);

        // Simulate app reload
        PledgingWallet newWallet = roundtripWallet(wallet);
        objects = new WalletTestObjects(() -> newWallet);
        wallet = (PledgingWallet) objects.wallet;
        System.err.println(wallet);
        LHProtos.Pledge[] revokedPledge = new LHProtos.Pledge[1];
        wallet.addOnRevokeHandler(Threading.SAME_THREAD, p -> { revokedPledge[0] = p; return Unit.INSTANCE$; });

        LHProtos.Pledge proto = wallet.getPledgeFor(project);
        assertNotNull(proto);
        // Get the pledge stub outpoint.
        Transaction pledgeTx = new Transaction(params, proto.getTransactions(1).toByteArray());
        TransactionOutPoint stubOp = pledgeTx.getInput(0).getOutpoint();

        // And revoke...
        ListenableFuture<Transaction> revocation = wallet.revokePledge(proto, null).broadcast.future();
        assertFalse(revocation.isDone());
        objects.broadcaster.waitForTxFuture().succeed();
        Transaction tx = revocation.get();
        assertEquals(0, wallet.getPledges().size());
        assertEquals(1_000_000 - REFERENCE_DEFAULT_MIN_TX_FEE.add(REFERENCE_DEFAULT_MIN_TX_FEE).longValue(),
                wallet.getBalance().value);  // -2x  fees
        TransactionOutPoint revokeOp = tx.getInput(0).getOutpoint();
        assertEquals(revokeOp, stubOp);
        TransactionOutput stub = stubTx.getOutput(0);
        // Check the tx executes properly.
        tx.getInput(0).verify(stub);
        assertEquals(revokedPledge[0], proto);
    }

    @Test
    public void revokedByClone() throws Exception {
        // Verify that the wallet does the right thing if a tx that isn't a claim appears out of nowhere and spends
        // a pledged output. Normally this means the wallet was restored from a backup or cloned elsewhere.
        WalletTestObjects objects = new WalletTestObjects(() -> new PledgingWallet(UnitTestParams.get()));
        PledgingWallet wallet = (PledgingWallet) objects.wallet;
        objects.sendAmounts(1_000_000);

        LHProtos.Pledge[] revokedPledge = new LHProtos.Pledge[1];
        wallet.addOnRevokeHandler(Threading.SAME_THREAD, p -> { revokedPledge[0] = p; return Unit.INSTANCE$; });

        Project project = new Project(makeProject(wallet, 3_000_000));
        LHProtos.Pledge pledge = wallet.createPledge(project, 500_000, null).commit(true);
        final MockTransactionBroadcaster.TxFuturePair txFuturePair = objects.broadcaster.waitForTxFuture();
        txFuturePair.succeed();
        Transaction stubTx = txFuturePair.tx;
        LHProtos.Pledge proto = wallet.getPledges().iterator().next();
        // Get the pledge stub outpoint.
        Transaction pledgeTx = new Transaction(params, proto.getTransactions(1).toByteArray());
        TransactionOutPoint stubOp = pledgeTx.getInput(0).getOutpoint();
        TransactionOutput stub = stubTx.getOutput(stubOp.getIndex());
        // Now double spend it
        Transaction dblspend = new Transaction(params);
        dblspend.addInput(stub);
        dblspend.addOutput(stub.getValue(), new ECKey().toAddress(params));
        wallet.receivePending(dblspend, null);
        assertEquals(pledge, revokedPledge[0]);
        assertEquals(0, wallet.getPledgedAmountFor(project));
    }

    @Test
    public void claim() throws Exception {
        // Check the wallet notices when its pledge has been claimed and understands the current state.

        WalletTestObjects objects1 = new WalletTestObjects(() -> new PledgingWallet(UnitTestParams.get()));
        PledgingWallet wallet1 = (PledgingWallet) objects1.wallet;
        Project project = new Project(makeProject(wallet1, 1_000_000));
        objects1.sendAmounts(1_000_000);
        PledgingWallet.PendingPledge ppledge1 = wallet1.createPledge(project, 500_000, null);
        LHProtos.Pledge pledge1 = ppledge1.commit(true);
        {
            final MockTransactionBroadcaster.TxFuturePair txFuturePair = objects1.broadcaster.waitForTxFuture();
            txFuturePair.succeed();
        }

        WalletTestObjects objects2 = new WalletTestObjects(() -> new PledgingWallet(UnitTestParams.get()));
        PledgingWallet wallet2 = (PledgingWallet) objects2.wallet;
        objects2.sendAmounts(1_000_000);
        PledgingWallet.PendingPledge ppledge2 = wallet2.createPledge(project, 500_000, null);
        LHProtos.Pledge pledge2 = ppledge2.commit(true);
        {
            final MockTransactionBroadcaster.TxFuturePair txFuturePair = objects2.broadcaster.waitForTxFuture();
            txFuturePair.succeed();
        }

        LHProtos.Pledge[] claimedPledge = new LHProtos.Pledge[1];
        Transaction[] claimTx = new Transaction[1];
        wallet1.addOnClaimHandler(Threading.SAME_THREAD, (p, tx) -> {
            claimedPledge[0] = p;
            claimTx[0] = tx;
            return Unit.INSTANCE$;
        });

        // We now have two wallets that have made two separate pledges, which is sufficient to complete the project.
        Transaction contract = project.completeContract(ImmutableSet.of(pledge1, pledge2));
        objects1.receiveViaBlock(contract);

        assertEquals(pledge1, claimedPledge[0]);
        assertEquals(2, claimTx[0].getInputs().size());
        assertEquals(project.getOutputs(), claimTx[0].getOutputs());
    }
}
