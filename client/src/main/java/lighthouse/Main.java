package lighthouse;

import com.google.common.base.*;
import com.google.common.util.concurrent.*;
import com.vinumeris.crashfx.*;
import com.vinumeris.updatefx.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.application.Platform;
import javafx.event.*;
import javafx.fxml.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.effect.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.scene.text.*;
import javafx.stage.*;
import javafx.util.*;
import lighthouse.controls.*;
import lighthouse.files.AppDirectory;
import lighthouse.protocol.*;
import lighthouse.subwindows.*;
import lighthouse.utils.*;
import lighthouse.utils.ipc.*;
import lighthouse.wallet.*;
import org.bitcoinj.core.*;
import org.bitcoinj.kits.*;
import org.bitcoinj.params.*;
import org.bitcoinj.utils.*;
import org.bitcoinj.wallet.*;
import org.bouncycastle.math.ec.*;
import org.slf4j.Logger;
import org.slf4j.*;

import javax.annotation.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import static lighthouse.LighthouseBackend.Mode.*;
import static lighthouse.protocol.LHUtils.*;
import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil.*;

public class Main extends Application {
    public static final Logger log = LoggerFactory.getLogger(Main.class);

    // This is not translated as it's used for directory paths and other system strings.
    public static final String APP_NAME = "Crowdfunding App";

    // UpdateFX stuff. Version is incremented monotonically after a new version is released.
    public static final int VERSION = 29;

    // No online updates URL for "Crowdfunding App".
    @Nullable public static final String UPDATES_BASE_URL = null;
    public static final List<ECPoint> UPDATE_SIGNING_KEYS = Crypto.decode(
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
    @Nullable private PeerAddress[] cmdLineRequestedIPs;
    public String updatesURL = UPDATES_BASE_URL;
    public static Path unadjustedAppDir;   // ignoring which network we're on.

    public static boolean offline = false;
    private PeerGroup xtPeers;

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
        final FileHandler handler;
        try {
            handler = new FileHandler(AppDirectory.dir().resolve("log.txt").toString(), true);
            handler.setEncoding("UTF-8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
        Project.GET_STATUS_USER_AGENT = APP_NAME + "/" + VERSION;
        // Set up the app dir + logging again, because the first time we did this (in main) could have been in the
        // context of another class loader if we're now running a different app version to the one the user installed.
        // Anything that happened in main() therefore might have now been wiped.
        AppDirectory.initAppDir(APP_NAME);
        unadjustedAppDir = AppDirectory.dir();
        List<Path> filesToOpen = new ArrayList<>();
        if (!parseCommandLineArgs() || FileOpenRequests.requestFileOpen(getParameters(), filesToOpen)) {
            Platform.exit();
            return;
        }
        setupLogging();
        log.info("\n\n{} {} starting up. It is {}\n", APP_NAME, VERSION, LHUtils.nowAsString());
        log.info("App dir is {}. We have {} cores.", AppDirectory.dir(), Runtime.getRuntime().availableProcessors());
        log.info("Command line arguments are: {}", String.join(" ", getParameters().getRaw()));
        log.info("We are running on: {} with Java {}", System.getProperty("os.name"), System.getProperty("java.version"));

        // Show the crash dialog for any exceptions that we don't handle and that hit the main loop.
        CrashFX.setup();
        // Set up the basic window with an empty UI stack, and put a quick splash there.
        reached("JFX initialised");
        prefs = new UserPrefs();
        initGUI(stage);
        stage.show();
        Runnable setup = () -> {
            uncheck(() -> initBitcoin(null));   // This will happen mostly async.
            loadMainWindow();
            if (isMac()) {
                FileOpenRequests.handleMacFileOpenRequests();
            } else {
                for (Path path : filesToOpen) {
                    Platform.runLater(() -> {
                        Main.instance.mainWindow.handleOpenedFile(path.toFile());
                    });
                }
            }
            runOnGuiThreadAfter(500, WalletSetPasswordController::estimateKeyDerivationTime);
        };
        // Give the splash time to render (lame hack as it turns out we can easily stall the splash rendering otherwise).
        runOnGuiThreadAfter(300, setup);
    }

    private boolean parseCommandLineArgs() {
        if (getParameters().getUnnamed().contains("--help") || getParameters().getUnnamed().contains("-h")) {
            System.out.println(String.format(
                    // TRANS: %s = App name, %s = App version
                    tr("%s version %d (C) 2014 Vinumeris GmbH\n\n" +
                            "Usage: lighthouse [args] [filename.lighthouse-project...] \n" +
                            "  --use-tor:                      Enable experimental Tor mode (may freeze up)\n" +
                            "  --slow-gfx:                     Enable more eyecandy that may stutter on slow GFX cards\n" +
                            "  --net={regtest,main,test}:      Select Bitcoin network to operate on.\n" +
                            "  --connect=ipaddr,ipaddr         Uses the given IP addresses for REGULAR (non-XT) Bitcoin usage.\n" +
                            "  --name=alice                    Name is put in titlebar and pledge filenames, useful for testing\n" +
                            "                                  multiple instances on the same machine.\n" +
                            "  --appdir=/path/to/dir           Overrides the usual directory used, useful for testing multiple\n" +
                            "                                  instances on the same machine.\n" +
                            "  --debuglog                      Print logging data to the console.\n" +
                            "  --updates-url=http://xxx/       Override the default URL used for updates checking.\n" +
                            "  --resfiles=/path/to/dir         Load GUI resource files from the given directory instead of using\n" +
                            "                                  the included versions.\n"),
                    APP_NAME, VERSION
            ));
            return false;
        }

        useTor = getParameters().getUnnamed().contains("--use-tor");

        slowGFX = !getParameters().getUnnamed().contains("--slow-gfx");

        // Handle network parameters.
        if (!selectNetwork()) return false;

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
                informationalAlert(tr("Bad updates URL"),
                    // TRANS: %s = updates URL
                    tr("The --updates-url parameter is invalid: %s"), updatesURL);
                return false;
            }
            this.updatesURL = updatesURL;
        }

        String resdir = getParameters().getNamed().get("resdir");
        if (resdir != null) {
            GuiUtils.resourceOverrideDirectory = Paths.get(resdir);
            if (!Files.isDirectory(GuiUtils.resourceOverrideDirectory) ||
                !Files.exists(GuiUtils.resourceOverrideDirectory.resolve("main.fxml"))) {
                informationalAlert(tr("Not a directory"), tr("The --resdir value must point to a directory containing UI resource files (fxml, css, etc)."));
                return false;
            }
        }

        String cmdLineIps = getParameters().getNamed().get("connect");
        if (cmdLineIps != null) {
            List<String> ips = Splitter.on(',').splitToList(cmdLineIps);
            cmdLineRequestedIPs = new PeerAddress[ips.size()];
            for (int i = 0; i < ips.size(); i++) {
                String ip = ips.get(i);
                try {
                    log.info("Resolving {} for usage", ip);
                    cmdLineRequestedIPs[i] = new PeerAddress(InetAddress.getByName(ip), params.getPort());
                } catch (UnknownHostException e) {
                    log.error("Unrecognised IP/DNS address, ignoring: " + ip);
                }
            }
        }
        return true;
    }

    private boolean selectNetwork() {
        String netname = "main";
        if (getParameters().getNamed().containsKey("net"))    // e.g. regtest or testnet
            netname = getParameters().getNamed().get("net");
        if (netname.equals("main"))
            netname = "production";
        params = NetworkParameters.fromID("org.bitcoin." + netname);
        if (params == null) {
            informationalAlert(tr("Unknown network ID"), tr("The --net parameter must be main, regtest or test"));
            return false;
        }
        // When not using testnet, use a subdirectory of the app directory to keep everything in, named after the
        // network. This is an upgrade path for alpha testers who have a directory with wallets/blockchains/projects
        // all on testnet.
        if (!netname.equals("test")) {
            Path path = AppDirectory.dir().resolve(netname);
            uncheck(() -> Files.createDirectories(path));
            AppDirectory.overrideAppDir(path);
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
        log.info("Primary screen scale factor is {}", GuiUtils.getPixelScale());
        mainStage = stage;
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
        // Kind of empirical hack.
        stage.setHeight(Math.max(750, Screen.getPrimary().getBounds().getHeight() - 150));

        // This might overwrite the settings above and will maximize the window by default.
        prefs.readStageSettings(stage);

        stage.setScene(scene);

        if (demoName != null) {
            stage.setTitle(APP_NAME + " - " + demoName);
        }
    }

    private Node createLoadingUI() {
        StackPane pane = new StackPane(new Label(tr("Crowdfunding app")));
        pane.setPadding(new Insets(20));
        pane.setStyle("-fx-background-color: white");
        return pane;
    }

    private boolean firstRun = true;
    private void loadMainWindow() {
        try {
            refreshStylesheets(scene);

            // Load the main window.
            FXMLLoader loader = new FXMLLoader(getResource("main.fxml"), I18nUtil.translations);
            Pane ui = LHUtils.stopwatched(tr("Loading main.fxml"), loader::load);
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
                informationalAlert(tr("Failed to load UI"),
                    // TRANS: %s = error message
                    tr("Error: %s"), e.getMessage());
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
        // Bring up a dedicated P2P connection group for Bitcoin XT nodes only. It'll be used for getutxo and nothing
        // else. Syncing to the network, Bloom filtering, etc, will be done by the WAK peer group. It's just easier
        // to do it this way than try to always maintain the correct balance of peers in a single PeerGroup, which
        // doesn't have great support for saying e.g. I want 1/3rd of peers to match this criteria and the other 2/3rds
        // can be anything.
        if (xtPeers == null) {
            xtPeers = connectXTPeers();
        } else {
            // Can be because bitcoinj appkit is being restarted for seed word restore.
        }

        // Create the app kit. It won't do any heavyweight initialization until after we start it.
        bitcoin = new WalletAppKit(params, AppDirectory.dir().toFile(), APP_NAME) {
            {
                walletFactory = PledgingWallet::new;
            }

            @Override
            protected void onSetupCompleted() {
                wallet = (PledgingWallet) bitcoin.wallet();
                backend = new LighthouseBackend(CLIENT, vPeerGroup, xtPeers, vChain, wallet);

                reached("onSetupCompleted");
                walletLoadedLatch.countDown();

                if (params == RegTestParams.get()) {
                    vPeerGroup.setMinBroadcastConnections(1);
                    vPeerGroup.setUseLocalhostPeerWhenPossible(false);
                }
            }
        };
        if (bitcoin.isChainFileLocked()) {
            informationalAlert(tr("Already running"),
                    tr("This application is already running and cannot be started twice."));
            bitcoin = null;
            if (!Main.offline)
                xtPeers.stopAsync();
            walletLoadedLatch.countDown();
            Platform.exit();
            return;
        }
        if (params == RegTestParams.get()) {
            InetAddress local = unchecked((UncheckedRun<InetAddress>) /* cast is ij bug workaround */ InetAddress::getLocalHost);
            bitcoin.setPeerNodes(
                    new PeerAddress(local, RegTestParams.get().getPort()),
                    new PeerAddress(local, RegTestParams.get().getPort() + 1)
            );
        }
        bitcoin.setBlockingStartup(false)
               .setDownloadListener(MainWindow.bitcoinUIModel.getDownloadListener())
               .setUserAgent(APP_NAME, "" + VERSION)
               .restoreWalletFromSeed(restoreFromSeed);

        if (cmdLineRequestedIPs != null) {
            bitcoin.setPeerNodes(cmdLineRequestedIPs);
        }

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

    public PeerGroup connectXTPeers() {
        // Assume google.com is the most reliable DNS name in the world.
        boolean isOffline = new InetSocketAddress("google.com", 80).getAddress() == null;
        if (isOffline) {
            log.warn("User appears to be offline");
            offline = true;
        }
        return LHUtils.connectXTPeers(params, isOffline, () -> {
            informationalAlert(tr("Local Bitcoin node not usable"),
                    // TRANS: %s = app name
                    tr("You have a Bitcoin (Core) node running on your computer, but it doesn't have the protocol support %s needs. %s will still " +
                            "work but will use the peer to peer network instead, so you won't get upgraded security. " +
                            "Try installing Bitcoin XT, which is a modified version of Bitcoin Core that has the upgraded protocol support."),
                    APP_NAME, APP_NAME);
        });
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
                // Workaround for crappy GPUs and people running inside VMs that don't do OpenGL/D3D.
                if (GuiUtils.isSoftwarePipeline()) {
                    brightnessAdjust(mainUI, 0.9);
                } else {
                    // Gaussian blur a screenshot and then fade it in. This is much faster than animating the radius
                    // of the blur itself which is intensive even on fast GPUs.
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
            FXMLLoader loader = new FXMLLoader(location, I18nUtil.translations);
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
        prefs.storeStageSettings(mainStage);
        if (bitcoin != null && bitcoin.isRunning()) {
            try {
                backend.shutdown();
                xtPeers.stopAsync();
                bitcoin.stopAsync();
                bitcoin.awaitTerminated();
            } catch (Exception e) {
                // Don't care.
            }
        }
        super.stop();
    }
}
