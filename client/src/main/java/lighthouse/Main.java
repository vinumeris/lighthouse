package lighthouse;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.Uninterruptibles;
import com.vinumeris.crashfx.CrashFX;
import com.vinumeris.crashfx.CrashWindow;
import com.vinumeris.updatefx.Crypto;
import com.vinumeris.updatefx.UpdateFX;
import javafx.animation.Animation;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import lighthouse.controls.NotificationBarPane;
import lighthouse.files.AppDirectory;
import lighthouse.protocol.LHUtils;
import lighthouse.subwindows.EmbeddedWindow;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.TextFieldValidator;
import lighthouse.wallet.PledgingWallet;
import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.net.discovery.PeerDiscoveryException;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bouncycastle.math.ec.ECPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;

import static lighthouse.LighthouseBackend.Mode.CLIENT;
import static lighthouse.protocol.LHUtils.uncheck;
import static lighthouse.protocol.LHUtils.unchecked;
import static lighthouse.utils.GuiUtils.*;

public class Main extends Application {
    public static final Logger log = LoggerFactory.getLogger(Main.class);

    public static final String APP_NAME = "Lighthouse";

    // UpdateFX stuff. Version is incremented monotonically after a new version is released.
    public static final int VERSION = 19;
    public static final String UPDATES_BASE_URL = "https://www.vinumeris.com/lighthouse/updates";
    public static final List<ECPoint> UPDATE_SIGNING_KEYS = Crypto.decode(
            // Two keys during temporary transition from a key that was not password protected to one that is.
            // At release the old key will be removed.
            "02A3CDE5D0EDC281637C67AA67C0CB009EA6573E0F101C6E018ACB91393C08C129",   // old
            "02AA4D7E966BFA942D3BEABD2049A49DB6AE92C417D8837C328BC02F8B50411A97"    // new
    );
    public static final int UPDATE_SIGNING_THRESHOLD = 1;

    public static NetworkParameters params;
    public static volatile WalletAppKit bitcoin;
    public static PledgingWallet wallet;
    public static Main instance;
    public static String demoName;
    public static LighthouseBackend backend;

    private StackPane uiStack;
    private Pane mainUI;
    public Scene scene;
    public Stage mainStage;
    public MainWindow mainWindow;
    private CountDownLatch walletLoadedLatch;

    public NotificationBarPane notificationBar;

    public UserPrefs prefs;

    private boolean useTor;
    private boolean slowGFX;
    public String updatesURL = UPDATES_BASE_URL;

    public static boolean offline = false;

    public static void main(String[] args) throws IOException {
        // Startup sequence: we use UpdateFX to allow for automatic online updates when the app is running. UpdateFX
        // applies binary deltas to our JAR to create a new JAR that's then dropped into the app directory.
        // Here we call into UpdateFX to ask it to locate the JAR with the highest version and then load it up.
        // Therefore this and realMain are the only methods that will *always* run: other parts of the code may not do
        // if they've been replaced by a higher version. Equally, this is the only part we can't upgrade automatically
        // so we need to do as little as possible here.
        AppDirectory.initAppDir(APP_NAME);
        UpdateFX.bootstrap(Main.class, AppDirectory.dir(), args);   // -> takes us to realMain, maybe in a newer version
    }

    // Need to pin this to work around a questionable design change in the JDK where it uses weak refs to hold loggers.
    private static boolean logToConsole = false;
    private static java.util.logging.Logger logger;
    private static void setupLogging() {
        logger = java.util.logging.Logger.getLogger("");
        final FileHandler handler = unchecked(() -> new FileHandler(AppDirectory.dir().resolve("log.txt").toString(), true));
        handler.setFormatter(new BriefLogFormatter());
        logger.addHandler(handler);
        if (logToConsole) {
            logger.getHandlers()[0].setFormatter(new BriefLogFormatter());
        } else {
            logger.removeHandler(logger.getHandlers()[0]);
        }
    }

    public static void realMain(String[] args) {
        launch(args);   // -> takes us to start() on a new thread, with JFX initialised.
    }

    @Override
    public void start(Stage stage) throws Exception {
        // For some reason the JavaFX launch process results in us losing the thread context class loader: reset it.
        // This is fixed in 8u40+, I think.
        Thread.currentThread().setContextClassLoader(Main.class.getClassLoader());
        instance = this;
        // Set up the app dir + logging again, because the first time we did this (in main) could have been in the
        // context of another class loader if we're now running a different app version to the one the user installed.
        // Anything that happened in main() therefore might have now been wiped.
        AppDirectory.initAppDir(APP_NAME);
        if (!parseCommandLineArgs()) {
            Platform.exit();
            return;
        }
        setupLogging();
        log.info("\n\nLighthouse {} starting up. It is {}\n", VERSION, LHUtils.nowAsString());
        log.info("App dir is {}. We have {} cores.", AppDirectory.dir(), Runtime.getRuntime().availableProcessors());
        // Show the crash dialog for any exceptions that we don't handle and that hit the main loop.
        CrashFX.setup("Lighthouse/" + Main.VERSION, AppDirectory.dir().resolve("crashes"), URI.create("https://www.vinumeris.com/crashfx/upload"));
        // Set up the basic window with an empty UI stack, and put a quick splash there.
        reached("JFX initialised");
        prefs = new UserPrefs();
        initGUI(stage);
        stage.show();
        Runnable setup = () -> {
            uncheck(() -> initBitcoin(null));   // This will happen mostly async.
            loadMainWindow();
        };
        runOnGuiThreadAfter(300, setup);
    }

    private boolean parseCommandLineArgs() {
        if (getParameters().getUnnamed().contains("--help") || getParameters().getUnnamed().contains("-h")) {
            System.out.println(String.format(
                    "Lighthouse version %d (C) 2014 Vinumeris GmbH%n%n" +
                    "Usage:%n" +
                    "  --use-tor:                      Enable experimental Tor mode (may freeze up)%n" +
                    "  --slow-gfx:                     Enable more eyecandy that may stutter on slow GFX cards%n" +
                    "  --net={regtest,main,testnet}:   Select Bitcoin network to operate on.%n" +
                    "  --name=alice                    Name is put in titlebar and pledge filenames, useful for testing%n" +
                    "                                  multiple instances on the same machine.%n" +
                    "  --appdir=/path/to/dir           Overrides the usual directory used, useful for testing multiple%n" +
                    "                                  instances on the same machine.%n" +
                    "  --debuglog                      Print logging data to the console.%n" +
                    "  --updates-url=http://xxx/       Override the default URL used for updates checking.%n" +
                    "  --resfiles=/path/to/dir         Load GUI resource files from the given directory instead of using%n" +
                    "                                  the included versions.%n",
                    VERSION
            ));
            return false;
        }

        useTor = getParameters().getUnnamed().contains("--use-tor");

        // TODO: Auto-detect adapter and configure this.
        slowGFX = !getParameters().getUnnamed().contains("--slow-gfx");

        // TODO: Reset to "production"
        String netname = "test";
        if (getParameters().getNamed().containsKey("net"))    // e.g. regtest or testnet
            netname = getParameters().getNamed().get("net");
        params = NetworkParameters.fromID("org.bitcoin." + netname);
        if (params == null) {
            informationalAlert("Unknown network ID", "The --net parameter must be production, regtest or testnet");
            return false;
        }
        demoName = getParameters().getNamed().get("name");
        String dir = getParameters().getNamed().get("appdir");
        if (dir != null) {
            AppDirectory.overrideAppDir(Paths.get(dir));
            uncheck(() -> AppDirectory.initAppDir(APP_NAME));
        }

        logToConsole = getParameters().getUnnamed().contains("--debuglog");

        String updatesURL = getParameters().getNamed().get("updates-url");
        if (updatesURL != null) {
            if (LHUtils.didThrow(() -> new URI(updatesURL))) {
                informationalAlert("Bad updates URL", "The --updates-url parameter is invalid: %s", updatesURL);
                return false;
            }
            this.updatesURL = updatesURL;
        }

        String resdir = getParameters().getNamed().get("resdir");
        if (resdir != null) {
            GuiUtils.resourceOverrideDirectory = Paths.get(resdir);
            if (!Files.isDirectory(GuiUtils.resourceOverrideDirectory) ||
                !Files.exists(GuiUtils.resourceOverrideDirectory.resolve("main.fxml"))) {
                informationalAlert("Not a directory", "The --resdir value must point to a directory containing UI resource files (fxml, css, etc).");
                return false;
            }
        }

        return true;
    }

    private static Stopwatch globalStopwatch = Stopwatch.createStarted();
    private static ThreadLocal<Stopwatch> threadStopwatch = ThreadLocal.withInitial(Stopwatch::createStarted);
    private static ThreadLocal<Long> last = ThreadLocal.withInitial(() -> 0L);
    public static void reached(String s) {
        final long elapsed = threadStopwatch.get().elapsed(TimeUnit.MILLISECONDS);
        log.info("[{}ms / {}ms / {}ms] {}", globalStopwatch.elapsed(TimeUnit.MILLISECONDS), elapsed, elapsed - last.get(), s);
        last.set(elapsed);
    }

    private void initGUI(Stage stage) throws IOException {
        if (GuiUtils.isSoftwarePipeline())
            log.warn("Prism is using software rendering");
        mainStage = stage;
        stage.getIcons().add(new Image(Main.class.getResourceAsStream("icon.png")));
        Font.loadFont(Main.class.getResource("nanlight-webfont.ttf").toString(), 10);
        Font.loadFont(Main.class.getResource("nanlightbold-webfont.ttf").toString(), 10);
        // Create the scene with a StackPane so we can overlay things on top of the main UI.
        final Node loadingUI = createLoadingUI();
        uiStack = new StackPane(loadingUI);
        scene = new Scene(uiStack);
        scene.getAccelerators().put(KeyCombination.valueOf("Shortcut+S"), () -> Platform.runLater(this::loadMainWindow));
        stage.setTitle(APP_NAME);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setWidth(1024);
        // Kind of empirical hacks - we could also just start maximised but then animations can get slow on big retina
        // displays. We try and fit on small screens with task bars / docks here.
        stage.setHeight(Math.max(750, Screen.getPrimary().getBounds().getHeight() - 150));
        stage.setScene(scene);

        if (demoName != null) {
            stage.setTitle(APP_NAME + " - " + demoName);
        }
    }

    private Node createLoadingUI() {
        ImageView lighthouseLogo = new ImageView(getResource("Logo.jpg").toString());
        lighthouseLogo.setFitWidth(500);
        lighthouseLogo.setPreserveRatio(true);
        StackPane.setAlignment(lighthouseLogo, Pos.CENTER);
//        ImageView vinumerisLogo = new ImageView(GuiUtils.getResource("vinumeris.png").toString());
//        vinumerisLogo.setFitHeight(25);
//        vinumerisLogo.setPreserveRatio(true);
//        StackPane.setAlignment(vinumerisLogo, Pos.TOP_RIGHT);
//        StackPane pane = new StackPane(lighthouseLogo, vinumerisLogo);
        StackPane pane = new StackPane(lighthouseLogo);
        pane.setPadding(new Insets(20));
        pane.setStyle("-fx-background-color: white");
        return pane;
    }

    private boolean firstRun = true;
    private void loadMainWindow() {
        try {
            refreshStylesheets(scene);

            // Load the main window.
            URL location = getResource("main.fxml");
            FXMLLoader loader = new FXMLLoader(location);
            Pane ui = LHUtils.stopwatched("Loading main.fxml", loader::load);
            ui.setMaxWidth(Double.MAX_VALUE);
            ui.setMaxHeight(Double.MAX_VALUE);
            MainWindow controller = loader.getController();

            // Embed it into a notification pane.
            notificationBar = new NotificationBarPane(ui);

            mainUI = notificationBar;
            mainWindow = controller;
            Uninterruptibles.awaitUninterruptibly(walletLoadedLatch);
            if (bitcoin == null)
                return;
            mainUI.setCache(true);
            reached("Showing main UI");
            uiStack.getChildren().add(0, mainUI);
            mainWindow.onBitcoinSetup();
            // When the app has just loaded fade out. If we're doing development and force refreshing the UI with a
            // hotkey, don't fade, it just slows me down.
            final Node node = uiStack.getChildren().get(1);
            if (firstRun) {
                fadeOutAndRemove(Duration.millis(slowGFX ? UI_ANIMATION_TIME_MSEC * 2 : UI_ANIMATION_TIME_MSEC), uiStack, node);
                firstRun = false;
            } else {
                uiStack.getChildren().remove(node);
            }
        } catch (Throwable e) {
            log.error("Failed to load UI: ", e);
            if (GuiUtils.resourceOverrideDirectory != null)
                informationalAlert("Failed to load UI", "Error: %s", e.getMessage());
            else
                CrashWindow.open(e);
        }
    }

    public void initBitcoin(@Nullable DeterministicSeed restoreFromSeed) throws IOException {
        walletLoadedLatch = new CountDownLatch(1);
        // Tell bitcoinj to run event handlers on the JavaFX UI thread. This keeps things simple and means
        // we cannot forget to switch threads when adding event handlers. Unfortunately, the DownloadListener
        // we give to the app kit is currently an exception and runs on a library thread. It'll get fixed in
        // a future version.
        Threading.USER_THREAD = Platform::runLater;
        // Create the app kit. It won't do any heavyweight initialization until after we start it.
        bitcoin = new WalletAppKit(params, AppDirectory.dir().toFile(), APP_NAME) {
            {
                walletFactory = PledgingWallet::new;
            }

            @Override
            protected void onSetupCompleted() {
                wallet = (PledgingWallet) bitcoin.wallet();
                backend = new LighthouseBackend(CLIENT, vPeerGroup, vChain, wallet);

                reached("onSetupCompleted");
                walletLoadedLatch.countDown();

                if (params == RegTestParams.get()) {
                    vPeerGroup.addAddress(new PeerAddress(unchecked(InetAddress::getLocalHost), RegTestParams.get().getPort()));
                    vPeerGroup.addAddress(new PeerAddress(unchecked(InetAddress::getLocalHost), RegTestParams.get().getPort() + 1));
                    vPeerGroup.setMinBroadcastConnections(1);
                    vPeerGroup.setUseLocalhostPeerWhenPossible(false);
                } else {
                    // TODO: Replace this with a DNS seed that crawls for NODE_GETUTXOS (or whatever it's renamed to).
                    InetSocketAddress[] result = new InetSocketAddress[2];
                    result[0] = new InetSocketAddress("vinumeris.com", params.getPort());
                    result[1] = new InetSocketAddress("riker.plan99.net", params.getPort());

                    if (result[0].getAddress() == null && result[1].getAddress() == null) {
                        log.warn("User appears to be offline");
                        offline = true;
                    } else {
                        PeerDiscovery hardCodedPeers = new PeerDiscovery() {
                            @Override
                            public InetSocketAddress[] getPeers(long timeoutValue, TimeUnit timeoutUnit) throws PeerDiscoveryException {
                                return result;
                            }

                            @Override
                            public void shutdown() {
                            }
                        };
                        vPeerGroup.addPeerDiscovery(hardCodedPeers);
                        vPeerGroup.setMaxConnections(2);
                        vPeerGroup.setConnectTimeoutMillis(10000);
                        // Sequence things so we *always* get both hard-coded peers for now.
                        vPeerGroup.waitForPeersOfVersion(2, GetUTXOsMessage.MIN_PROTOCOL_VERSION).addListener(() -> {
                            vPeerGroup.addPeerDiscovery(new DnsDiscovery(params));
                            vPeerGroup.setMaxConnections(6);
                        }, Threading.SAME_THREAD);
                    }
                    vPeerGroup.addEventListener(new AbstractPeerEventListener() {
                        @Override
                        public void onPeerConnected(Peer peer, int peerCount) {
                            if (peer.getAddress().getAddr().isLoopbackAddress() && !peer.getPeerVersionMessage().isGetUTXOsSupported()) {
                                // We connected to localhost but it doesn't have what we need.
                                log.warn("Localhost peer does not have support for NODE_GETUTXOS, ignoring");
                                // TODO: Once Bitcoin Core fork name is chosen and released, be more specific in this message.
                                informationalAlert("Local Bitcoin node not usable",
                                        "You have a Bitcoin (Core) node running on your computer, but it doesn't have the protocol support Lighthouse needs. Lighthouse will still " +
                                                "work but will use the peer to peer network instead, so you won't get upgraded security.");
                                vPeerGroup.setUseLocalhostPeerWhenPossible(false);
                                vPeerGroup.setMaxConnections(4);
                            }
                        }
                    });
                }
            }
        };
        if (bitcoin.isChainFileLocked()) {
            informationalAlert("Already running",
                    "This application is already running and cannot be started twice.");
            Platform.exit();
            bitcoin = null;
            walletLoadedLatch.countDown();
            return;
        }
        if (params == MainNetParams.get()) {
            // Checkpoints are block headers that ship inside our app: for a new user, we pick the last header
            // in the checkpoints file and then download the rest from the network. It makes things much faster.
            // Checkpoint files are made using the BuildCheckpoints tool and usually we have to download the
            // last months worth or more (takes a few seconds).
            bitcoin.setCheckpoints(getClass().getResourceAsStream("checkpoints"));
        } else if (params == TestNet3Params.get()) {
            bitcoin.setCheckpoints(getClass().getResourceAsStream("checkpoints.testnet"));
        }
        bitcoin.setPeerNodes(new PeerAddress[0])    // Hack to prevent WAK adding DnsDiscovery
               .setBlockingStartup(false)
               .setDownloadListener(MainWindow.bitcoinUIModel.getDownloadListener())
               .setUserAgent("Lighthouse", "" + VERSION)
               .restoreWalletFromSeed(restoreFromSeed);

        if (useTor && params != RegTestParams.get())
            bitcoin.useTor();

        reached("Starting bitcoin init");
        bitcoin.addListener(new Service.Listener() {
            @Override
            public void failed(Service.State from, Throwable failure) {
                bitcoin = null;
                walletLoadedLatch.countDown();
                CrashWindow.open(failure);
            }
        }, Threading.SAME_THREAD);
        bitcoin.startAsync();
    }

    private void refreshStylesheets(Scene scene) {
        scene.getStylesheets().clear();
        TextFieldValidator.configureScene(scene);
        // Generic styles first, then app specific styles.
        scene.getStylesheets().add(getResource("vinumeris-style.css").toString());
        scene.getStylesheets().add(getResource("main.css").toString());
    }

    private ImageView stopClickPane = new ImageView();

    public boolean waitForInit() {
        log.info("Waiting for bitcoin load ...");
        Uninterruptibles.awaitUninterruptibly(walletLoadedLatch);
        if (Main.backend != null) {
            log.info("Waiting for backend init ...");
            Main.backend.waitForInit();
            return true;
        } else {
            return false;
        }
    }

    public static void restart() {
        uncheck(UpdateFX::restartApp);
    }

    public static void restartBitcoinJ(DeterministicSeed seed) {
        new Thread(() -> {
            Main.bitcoin.stopAsync();
            Main.bitcoin.awaitTerminated();
            uncheck(() -> Main.instance.initBitcoin(seed));
            Main.instance.waitForInit();
            Platform.runLater(Main.instance.mainWindow::onBitcoinSetup);
        }, "Restart thread").start();
    }

    public class OverlayUI<T> {
        public Node ui;
        public T controller;

        public OverlayUI(Node ui, T controller) {
            this.ui = ui;
            this.controller = controller;
        }

        public void show() {
            checkGuiThread();
            if (currentOverlay == null) {
                uiStack.getChildren().add(stopClickPane);
                uiStack.getChildren().add(ui);
                // Workaround for crappy integrated graphics chips.
                if (GuiUtils.isSoftwarePipeline()) {
                    brightnessAdjust(mainUI, 0.9);
                } else {
                    WritableImage image = new WritableImage((int) scene.getWidth(), (int) scene.getHeight());
                    mainUI.setClip(new Rectangle(scene.getWidth(), scene.getHeight()));
                    ColorAdjust lighten = new ColorAdjust(0.0, 0.0, 0.7, 0.0);
                    GaussianBlur blur = new GaussianBlur(20);
                    blur.setInput(lighten);
                    mainUI.setEffect(blur);
                    mainUI.snapshot(new SnapshotParameters(), image);
                    mainUI.setClip(null);
                    mainUI.setEffect(null);

                    stopClickPane.setImage(image);
                    stopClickPane.setOpacity(0.0);
                    fadeIn(stopClickPane, 0, 1.0);
                }
                ui.setOpacity(0.0);
                fadeIn(ui);
                zoomIn(ui);
            } else {
                // Do a quick transition between the current overlay and the next.
                // Bug here: we don't pay attention to changes in outsideClickDismisses.
                explodeOut(currentOverlay.ui);
                fadeOutAndRemove(uiStack, currentOverlay.ui);
                uiStack.getChildren().add(ui);
                ui.setOpacity(0.0);
                fadeIn(ui, 100, 1.0);
                zoomIn(ui, 100);
            }
            currentOverlay = this;
        }

        private LinkedList<EventHandler<ActionEvent>> afterFade = new LinkedList<>();

        @Nullable
        public Animation done() {
            checkGuiThread();
            if (ui == null) return null;  // In the middle of being dismissed and got an extra click.
            explodeOut(ui);
            Animation animation = fadeOutAndRemove(uiStack, ui, stopClickPane);
            for (EventHandler<ActionEvent> handler : afterFade) {
                EventHandler<ActionEvent> prev = animation.getOnFinished();
                animation.setOnFinished(ev -> {
                    prev.handle(ev);
                    handler.handle(ev);
                });
            }
            if (GuiUtils.isSoftwarePipeline()) {
                brightnessUnadjust(mainUI);
            }
            this.ui = null;
            this.controller = null;
            currentOverlay = null;
            return animation;
        }

        public void runAfterFade(EventHandler<ActionEvent> runnable) {
            afterFade.add(runnable);
        }
    }

    @Nullable
    private OverlayUI currentOverlay;

    public <T> OverlayUI<T> overlayUI(Node node, T controller) {
        checkGuiThread();
        OverlayUI<T> pair = new OverlayUI<T>(node, controller);
        // Auto-magically set the overlayUi member, if it's there.
        try {
            controller.getClass().getField("overlayUI").set(controller, pair);
        } catch (IllegalAccessException | NoSuchFieldException ignored) {
        }
        pair.show();
        return pair;
    }

    public <T> OverlayUI<T> overlayUI(String name) {
        return overlayUI(name, null);
    }

    // TODO: Make title be loaded from FXML.

    /** Loads the FXML file with the given name, blurs out the main UI and puts this one on top. */
    public <T> OverlayUI<T> overlayUI(String name, @Nullable String title) {
        try {
            checkGuiThread();
            // Load the UI from disk.
            URL location = getResource(name);
            FXMLLoader loader = new FXMLLoader(location);
            Pane ui = loader.load();
            T controller = loader.getController();

            EmbeddedWindow window = null;
            if (title != null)
                ui = window = new EmbeddedWindow(title, ui);

            OverlayUI<T> pair = new OverlayUI<T>(ui, controller);
            // Auto-magically set the overlayUi member, if it's there.
            try {
                if (controller != null)
                    controller.getClass().getField("overlayUI").set(controller, pair);
            } catch (IllegalAccessException | NoSuchFieldException ignored) {
                ignored.printStackTrace();
            }

            if (window != null)
                window.setOnCloseClicked(pair::done);

            pair.show();
            return pair;
        } catch (IOException e) {
            throw new RuntimeException(e);  // Can't happen.
        }
    }

    @Override
    public void stop() throws Exception {
        if (bitcoin != null && bitcoin.isRunning()) {
            backend.shutdown();
            bitcoin.stopAsync();
            bitcoin.awaitTerminated();
        }
        super.stop();
    }
}
