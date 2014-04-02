package lighthouse.server;

import com.google.bitcoin.core.GetUTXOsMessage;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.kits.WalletAppKit;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.params.RegTestParams;
import com.google.bitcoin.params.TestNet3Params;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import lighthouse.LighthouseBackend;
import lighthouse.files.AppDirectory;
import lighthouse.protocol.LHUtils;
import lighthouse.threading.AffinityExecutor;
import lighthouse.wallet.PledgingWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;

import static lighthouse.LighthouseBackend.Mode.SERVER;

/**
 * PledgeServer is a standalone HTTP server that knows how to accept pledges and vend statuses (lists of pledges).
 * It can help simplify the workflow when the project owner is capable of running a web server.
 */
public class PledgeServer {
    private static final Logger log = LoggerFactory.getLogger(PledgeServer.class);
    public static final short DEFAULT_LOCALHOST_PORT = (short) LHUtils.HTTP_LOCAL_TEST_PORT;

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();

        OptionParser parser = new OptionParser();
        OptionSpec<String> dirFlag = parser.accepts("dir").withRequiredArg();
        OptionSpec<String> netFlag = parser.accepts("net").withRequiredArg().defaultsTo("regtest");
        OptionSpec<Short> portFlag = parser.accepts("port").withRequiredArg().ofType(Short.class).defaultsTo(DEFAULT_LOCALHOST_PORT);
        OptionSpec<String> keystoreFlag = parser.accepts("keystore").withRequiredArg();
        parser.accepts("local-node");
        OptionSet options = parser.parse(args);

        NetworkParameters params;
        switch (netFlag.value(options)) {
            case "main": params = MainNetParams.get(); break;
            case "test": params = TestNet3Params.get(); break;
            case "regtest": params = RegTestParams.get(); break;
            default:
                System.err.println("--net must be one of main, test or regtest");
                return;
        }

        HttpsServer server = createServer(portFlag, keystoreFlag, options);

        // Where will we store our projects and received pledges?
        if (options.has(dirFlag))
            AppDirectory.overrideAppDir(Paths.get(options.valueOf(dirFlag)));
        Path appDir = AppDirectory.initAppDir("lighthouse-server");   // Create dir if necessary.

        WalletAppKit kit = new WalletAppKit(params, appDir.toFile(), "lighthouse-server") {
            {
                walletFactory = PledgingWallet::new;
            }
        };
        if (kit.isChainFileLocked()) {
            log.error("App is already running. Please terminate the other instance or use a different directory (--dir=...)");
            return;
        }
        int minPeersSupportingGetUTXO = 2;   // Increase when the feature eventually rolls out to the network.
        if (options.has("local-node") || params == RegTestParams.get()) {
            kit.connectToLocalHost();
            minPeersSupportingGetUTXO = 1;  // Only local matters.
        }
        // Eventually take this out when getutxo is merged, released and people have upgraded.
        kit.setBlockingStartup(true)
           .setAutoSave(true)
           .setAutoStop(true)
           .setUserAgent("Lighthouse Server", "1.0")
           .startAsync()
           .awaitRunning();
        log.info("bitcoinj initialised");

        // Don't start up fully until we're properly set up. Eventually this can go away.
        // TODO: Make this also check for NODE_GETUTXOS flag.
        log.info("Waiting to find a peer that supports getutxo");
        kit.peerGroup().waitForPeersOfVersion(minPeersSupportingGetUTXO, GetUTXOsMessage.MIN_PROTOCOL_VERSION).get();
        log.info("Found ... starting web server on port {}", portFlag.value(options));

        // This app is mostly single threaded. It handles all requests and state changes on a single thread.
        // Speed should ideally not be an issue, as the backend blocks only rarely. If it's a problem then
        // we'll have to split the backend thread from the http server thread.
        AffinityExecutor executor = new AffinityExecutor.ServiceAffinityExecutor("server");
        server.setExecutor(executor);
        LighthouseBackend backend = new LighthouseBackend(SERVER, kit.peerGroup(), kit.chain(), (PledgingWallet) kit.wallet(), executor);
        backend.setMinPeersForUTXOQuery(minPeersSupportingGetUTXO);
        server.createContext(LHUtils.HTTP_PATH_PREFIX, new ProjectHandler(backend));
        server.createContext("/", exchange -> {
            log.warn("404 Not Found: {}", exchange.getRequestURI());
            exchange.sendResponseHeaders(404, -1);
        });
        server.start();
    }

    private static HttpsServer createServer(OptionSpec<Short> portFlag, OptionSpec<String> keystoreFlag, OptionSet options) throws Exception {
        // The amount of boilerplate this supposedly lightweight HTTPS server requires is stupid.
        KeyStore keyStore = KeyStore.getInstance("JKS");
        char[] password = "changeit".toCharArray();
        try (FileInputStream stream = new FileInputStream(options.valueOf(keystoreFlag))) {
            keyStore.load(stream, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(keyStore);
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        HttpsServer server = HttpsServer.create(new InetSocketAddress(portFlag.value(options)), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        return server;
    }
}
