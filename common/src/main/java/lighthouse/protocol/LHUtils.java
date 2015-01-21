package lighthouse.protocol;

import com.google.common.base.*;
import com.google.common.io.*;
import com.google.common.util.concurrent.*;
import org.bitcoinj.core.*;
import org.bitcoinj.net.discovery.*;
import org.bitcoinj.params.*;
import org.slf4j.*;

import javax.annotation.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.stream.*;

public class LHUtils {
    public static final String PROJECT_MIME_TYPE = "application/vnd.vinumeris.lighthouse-project";
    public static final String PLEDGE_MIME_TYPE = "application/vnd.vinumeris.lighthouse-pledge";

    private static final Logger log = LoggerFactory.getLogger(LHUtils.class);

    public static List<Path> listDir(Path dir) {
        List<Path> contents = new LinkedList<>();
        try (Stream<Path> list = unchecked(() -> Files.list(dir))) {
            list.forEach(contents::add);
        }
        return contents;
    }

    public static String nowAsString() {
        return Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    public static boolean compareOutputsStructurally(Transaction tx, Project project) {
        List<TransactionOutput> outs1 = mapList(tx.getOutputs(), TransactionOutput::duplicateDetached);
        List<TransactionOutput> outs2 = mapList(project.getOutputs(), TransactionOutput::duplicateDetached);
        return outs1.equals(outs2);
    }

    public static boolean pledgeAppearsInClaim(Project forProject, LHProtos.Pledge pledge, Transaction claim) {
        Transaction tx = forProject.fastSanityCheck(pledge);
        List<TransactionInput> pledgeInputs = mapList(tx.getInputs(), TransactionInput::duplicateDetached);
        List<TransactionInput> claimInputs = mapList(claim.getInputs(), TransactionInput::duplicateDetached);
        claimInputs.retainAll(pledgeInputs);
        return claimInputs.size() == pledgeInputs.size();
    }

    public static String titleToUrlString(String s) {
        return s.replaceAll("[\"!@#$%^&*()_+\\\\';:?=/.,\\[\\] \\n]", "-").replaceAll("-+", "-").replaceAll("(^-+|-+$)", "").toLowerCase();
    }

    public static boolean isUnix() {
        return System.getProperty("os.name").toLowerCase().contains("linux") || System.getProperty("os.name").toLowerCase().contains("freebsd");
    }

    public static Transaction pledgeToTx(NetworkParameters params, LHProtos.Pledge pledge) {
        return new Transaction(params, pledge.getTransactions(pledge.getTransactionsCount() - 1).toByteArray());
    }

    public static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    public static PeerGroup connectXTPeers(NetworkParameters params, boolean isOffline, Runnable onLocalNodeUnusable) {
        PeerGroup xtPeers = new PeerGroup(params);
        final int XT_PEERS = 4;
        if (params == RegTestParams.get()) {
            // Use two local regtest nodes for testing.
            xtPeers.addAddress(new PeerAddress(unchecked(InetAddress::getLocalHost), RegTestParams.get().getPort()));
            xtPeers.addAddress(new PeerAddress(unchecked(InetAddress::getLocalHost), RegTestParams.get().getPort() + 1));
            xtPeers.setUseLocalhostPeerWhenPossible(false);
            xtPeers.startAsync();
        } else {
            // Just a quick check to see if we can resolve DNS names.
            if (!isOffline) {
                // PeerGroup will use a local Bitcoin node if at all possible, but it may not have what we need.
                xtPeers.addEventListener(new AbstractPeerEventListener() {
                    boolean shownMessage = false;

                    @Override
                    public void onPeerConnected(Peer peer, int peerCount) {
                        if (peer.getAddress().getAddr().isLoopbackAddress() && !peer.getPeerVersionMessage().isGetUTXOsSupported()) {
                            // We connected to localhost but it doesn't have what we need.
                            log.warn("Localhost peer does not have support for NODE_GETUTXOS, ignoring");
                            if (!shownMessage) {
                                shownMessage = true;
                                onLocalNodeUnusable.run();
                            }
                            xtPeers.setUseLocalhostPeerWhenPossible(false);
                            xtPeers.setMaxConnections(XT_PEERS);
                            peer.close();
                        }
                    }
                });
                // There's unfortunately no way to instruct the other seeds to search for a subset of the Bitcoin network
                // so that's why we need to use a new more flexible HTTP based protocol here. The seed will find
                // Bitcoin XT nodes as people start and stop them.
                //
                // Hopefully in future more people will run HTTP seeds, then we can use a MultiplexingDiscovery
                // to randomly merge their answers and reduce the influence of any one seed. Additionally if
                // more people run Bitcoin XT nodes we can bump up the number we search for here to again
                // reduce the influence of any one node. But this needs people to help decentralise.
                xtPeers.addPeerDiscovery(new HttpDiscovery(params,
                                unchecked(() -> new URI("http://main.seed.vinumeris.com/peers?srvmask=3&getutxo=true")),
                                // auth key used to sign responses.
                                ECKey.fromPublicOnly(BaseEncoding.base16().decode(
                                        "027a79143a4de36341494d21b6593015af6b2500e720ad2eda1c0b78165f4f38c4".toUpperCase()
                                )))
                );
                xtPeers.setConnectTimeoutMillis(10000);
                xtPeers.setMaxConnections(XT_PEERS);
                xtPeers.startAsync();
            }
        }
        return xtPeers;
    }

    //region Generic Java 8 enhancements
    public interface UncheckedRun<T> {
        public T run() throws Throwable;
    }

    public interface UncheckedRunnable {
        public void run() throws Throwable;
    }

    public static <T> T unchecked(UncheckedRun<T> run) {
        try {
            return run.run();
        } catch (Throwable throwable) {
            Throwables.propagate(throwable);
            return null;  // unreachable
        }
    }

    public static void uncheck(UncheckedRunnable run) {
        try {
            run.run();
        } catch (Throwable throwable) {
            Throwables.propagate(throwable);
        }
    }

    public static void ignoreAndLog(UncheckedRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            log.error("Ignoring error", t);
        }
    }

    public static <T> T ignoredAndLogged(UncheckedRun<T> runnable) {
        try {
            return runnable.run();
        } catch (Throwable t) {
            log.error("Ignoring error", t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T, E extends Throwable> T checkedGet(Future<T> future) throws E {
        try {
            return future.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw (E) e.getCause();
        }
    }

    public static boolean didThrow(UncheckedRun run) {
        try {
            run.run();
            return false;
        } catch (Throwable throwable) {
            return true;
        }
    }

    public static boolean didThrow(UncheckedRunnable run) {
        try {
            run.run();
            return false;
        } catch (Throwable throwable) {
            return true;
        }
    }

    public static <T> T stopwatched(String description, UncheckedRun<T> run) {
        long now = System.currentTimeMillis();
        T result = unchecked(run::run);
        log.info("{}: {}ms", description, System.currentTimeMillis() - now);
        return result;
    }

    public static void stopwatch(String description, UncheckedRunnable run) {
        long now = System.currentTimeMillis();
        uncheck(run::run);
        log.info("{}: {}ms", description, System.currentTimeMillis() - now);
    }

    //endregion

    /** Given an HTTP[S] URL, verifies that it matches the LH protocol layout and returns the hostname if so. */
    @Nullable
    public static String validateServerPath(String path) {
        try {
            if (path.isEmpty())
                return null;
            URI uri = new URI(path);
            boolean validScheme = uri.getScheme().equals("https") || (uri.getScheme().equals("http") && uri.getHost().equals("localhost"));
            if (validScheme && uri.getPath().startsWith(HTTP_PATH_PREFIX + HTTP_PROJECT_PATH))
                return uri.getHost();
            else
                return null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static final String HTTP_PATH_PREFIX = "/_lighthouse/crowdfund";
    public static final String HTTP_PROJECT_PATH = "/project/";
    public static final int HTTP_LOCAL_TEST_PORT = 13765;

    public static String makeServerPath(String name, String projectID) {
        return "https://" + name + HTTP_PATH_PREFIX + HTTP_PROJECT_PATH + projectID;
    }

    /** Convert from Guava futures to Java 8 futures */
    public static <T> CompletableFuture<T> convertFuture(ListenableFuture<T> future) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        Futures.addCallback(future, new FutureCallback<T>() {
            @Override
            public void onSuccess(@Nullable T result) {
                cf.complete(result);
            }

            @Override
            public void onFailure(Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }

    /** Just simpler syntax than the stupid streams library */
    public static <T, R> List<R> mapList(List<T> inputs, Function<T, R> transformer) {
        List<R> results = new ArrayList<>(inputs.size());
        for (T input : inputs) results.add(transformer.apply(input));
        return results;
    }

    public static <T> CompletableFuture<T> futureOfFutures(List<CompletableFuture<T>> futures) {
        // It's dumb that the CF API is so huge and doesn't have this.
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicInteger numPending = new AtomicInteger(futures.size());
        for (CompletableFuture<T> future : futures) {
            future.handle((v, ex) -> {
                if (ex != null) {
                    log.error("Future failed with an exception: {}", Throwables.getRootCause(ex).toString());
                }
                if (numPending.decrementAndGet() == 0)
                    result.complete(v);
                return null;
            });
        }
        return result;
    }

    /** Either hashes the given pledge protobuf or returns the hash it claims to have been originally. */
    public static Sha256Hash hashFromPledge(LHProtos.Pledge pledge) {
        if (pledge.getPledgeDetails().hasOrigHash())
            return new Sha256Hash(pledge.getPledgeDetails().getOrigHash().toByteArray());
        else
            return Sha256Hash.create(pledge.toByteArray());
    }
}
