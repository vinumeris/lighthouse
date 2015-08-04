package lighthouse.server;

import com.google.common.collect.*;
import com.sun.net.httpserver.*;
import joptsimple.*;
import kotlin.*;
import lighthouse.*;
import lighthouse.files.*;
import lighthouse.protocol.*;
import lighthouse.threading.*;
import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.bitcoinj.utils.*;
import org.slf4j.Logger;
import org.slf4j.*;

import javax.net.ssl.*;
import java.io.*;
import java.lang.management.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.logging.*;

import static lighthouse.LighthouseBackend.Mode.*;

/**
 * PledgeServer is a standalone HTTP server that knows how to accept pledges and vend statuses (lists of pledges).
 * It can help simplify the workflow when the project owner is capable of running a web server.
 */
public class PledgeServer {
    private static final Logger log = LoggerFactory.getLogger(PledgeServer.class);
    public static final short DEFAULT_LOCALHOST_PORT = (short) LHUtils.HTTP_LOCAL_TEST_PORT;

    public static final String DEFAULT_PID_FILENAME = "lighthouse-server.pid";

    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();
        OptionSpec<String> dirFlag = parser.accepts("dir").withRequiredArg();
        OptionSpec<String> netFlag = parser.accepts("net").withRequiredArg().defaultsTo("main");
        OptionSpec<Short>  portFlag = parser.accepts("port").withRequiredArg().ofType(Short.class).defaultsTo(DEFAULT_LOCALHOST_PORT);
        OptionSpec<String> keystoreFlag = parser.accepts("keystore").withRequiredArg();
        OptionSpec<String> pidFileFlag = parser.accepts("pidfile").withRequiredArg().defaultsTo(DEFAULT_PID_FILENAME);
        parser.accepts("local-node");
        OptionSpec<Void> logToConsole = parser.accepts("log-to-console");
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

        HttpServer server = createServer(portFlag, keystoreFlag, options);

        // Where will we store our projects and received pledges?
        if (options.has(dirFlag))
            AppDirectory.overrideAppDir(Paths.get(options.valueOf(dirFlag)));
        Path appDir = AppDirectory.initAppDir("lighthouse-server");   // Create dir if necessary.

        setupLogging(appDir, options.has(logToConsole));

        Context bitcoinContext = new Context(params);
        BitcoinBackend bitcoin;
        try {
            bitcoin = new BitcoinBackend(bitcoinContext, "Lighthouse Server", "2.0", null, false);
            bitcoin.start(new DownloadProgressTracker());
        } catch (ChainFileLockedException e) {
            log.error("This server is already running");
            return;
        } catch (Exception e) {
            log.error("Failed to start bitcoinj", e);
            return;
        }
        writePidFile(appDir, pidFileFlag.value(options));

        // This app is mostly single threaded. It handles all requests and state changes on a single thread.
        // Speed should ideally not be an issue, as the backend blocks only rarely. If it's a problem then
        // we'll have to split the backend thread from the http server thread.
        AffinityExecutor.ServiceAffinityExecutor executor = new AffinityExecutor.ServiceAffinityExecutor("server");
        server.setExecutor(executor);
        LighthouseBackend backend = new LighthouseBackend(SERVER, params, bitcoin, executor);
        backend.start();

        DirectoryWatcher.watch(appDir, executor, (path, kind) -> {
            if (path.toString().endsWith(LighthouseBackend.PROJECT_FILE_EXTENSION)) {
                try {
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        backend.importProjectFrom(path);
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        // TODO: Unload.
                    }
                } catch (IOException e) {
                    log.error("Failed to load project from {}", path);
                }
            }
            return Unit.INSTANCE$;
        });

        server.createContext(LHUtils.HTTP_PATH_PREFIX, new ProjectHandler(backend));
        server.createContext("/", exchange -> {
            log.warn("404 Not Found: {}", exchange.getRequestURI());
            exchange.sendResponseHeaders(404, -1);
        });
        log.info("****** STARTING WEB SERVER ON PORT {} ******", portFlag.value(options));
        server.start();
    }

    private static void writePidFile(Path appDir, String fileNameOrPath) {
        Path path = appDir.resolve(fileNameOrPath);   // If fileNameOrPath starts with / then it will override the default.
        Path filePath;
        if (Files.isDirectory(path))
            filePath = path.resolve(DEFAULT_PID_FILENAME);
        else
            filePath = path;
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        log.info("Our process ID is {}", pid);
        LHUtils.ignoreAndLog(() -> {
            Files.write(filePath, ImmutableList.of(pid), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            filePath.toFile().deleteOnExit();
        });
    }

    private static HttpServer createServer(OptionSpec<Short> portFlag, OptionSpec<String> keystoreFlag, OptionSet options) throws Exception {
        if (options.has(keystoreFlag)) {
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
        } else {
            return HttpServer.create(new InetSocketAddress(portFlag.value(options)), 0);
        }
    }

    // Work around JDK misdesign/bug.
    private static java.util.logging.Logger loggerPin;
    private static void setupLogging(Path dir, boolean logToConsole) throws IOException {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("");
        Handler handler = new FileHandler(dir.resolve("log.txt").toString(), true);
        handler.setFormatter(new BriefLogFormatter());
        logger.addHandler(handler);
        if (logToConsole) {
            logger.getHandlers()[0].setFormatter(new BriefLogFormatter());
        } else {
            logger.removeHandler(logger.getHandlers()[0]);
        }
        logger.setLevel(Level.INFO);
        loggerPin = logger;
    }
}
