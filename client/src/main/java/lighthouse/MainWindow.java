package lighthouse;

import com.google.protobuf.ByteString;
import com.subgraph.orchid.TorClient;
import com.subgraph.orchid.TorInitializationListener;
import com.vinumeris.updatefx.UpdateFX;
import com.vinumeris.updatefx.Updater;
import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lighthouse.controls.ClickableBitcoinAddress;
import lighthouse.controls.NotificationBarPane;
import lighthouse.controls.ProjectOverviewWidget;
import lighthouse.controls.ProjectView;
import lighthouse.files.AppDirectory;
import lighthouse.model.BitcoinUIModel;
import lighthouse.protocol.Project;
import lighthouse.subwindows.WalletSettingsController;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.easing.EasingMode;
import lighthouse.utils.easing.ElasticInterpolator;
import org.bitcoinj.core.Coin;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.MonetaryFormat;
import org.fxmisc.easybind.EasyBind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static lighthouse.threading.AffinityExecutor.UI_THREAD;

/**
 * Gets created auto-magically by FXMLLoader via reflection. The widget fields are set to the GUI controls they're named
 * after. This class handles all the updates and event handling for the main UI.
 */
public class MainWindow {
    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    public Label balance;
    public Button sendMoneyOutBtn, setupWalletBtn;
    public ClickableBitcoinAddress addressControl;

    public HBox balanceArea;
    public VBox projectsVBox;
    public HBox topBox;
    public VBox root;
    public HBox contentHBox;
    public ScrollPane contentScrollPane;
    public ProjectView projectView;
    public StackPane projectViewContainer;
    public VBox overviewVbox;
    public VBox contentStack;
    public Label addProjectIcon;
    public Label networkIndicatorLabel;

    // These are read-only mirrors of sets maintained by the backend. Changes made by LighthouseBackend are propagated
    // into the UI thread and applied there asynchronously, thus it is safe to connect them directly to UI widgets.
    private ObservableList<Project> projects;

    public static BitcoinUIModel bitcoinUIModel = new BitcoinUIModel();
    private NotificationBarPane.Item syncItem;
    private ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;
    // A map indicating the status of checking each project against the network (downloading, found an error, done, etc)
    // This is mirrored into the UI thread from the backend.
    private ObservableMap<Project, LighthouseBackend.CheckStatus> checkStates;

    enum Views {
        OVERVIEW,
        PROJECT
    }

    // Called by FXMLLoader.
    public void initialize() {
        AwesomeDude.setIcon(sendMoneyOutBtn, AwesomeIcon.SIGN_OUT, "12pt", ContentDisplay.LEFT);
        Tooltip.install(sendMoneyOutBtn, new Tooltip("Send money out of the wallet"));
        AwesomeDude.setIcon(setupWalletBtn, AwesomeIcon.LOCK, "12pt", ContentDisplay.LEFT);
        Tooltip.install(setupWalletBtn, new Tooltip("Make paper backup and encrypt your wallet"));
        AwesomeDude.setIcon(addProjectIcon, AwesomeIcon.FILE_ALT, "50pt; -fx-text-fill: white" /* lame hack */);

        // Avoid duplicate add errors.
        contentStack.getChildren().remove(projectViewContainer);
        contentStack.getChildren().remove(overviewVbox);

        // Some UI init is done in onBitcoinSetup
        switchView(Views.OVERVIEW);

        // Wait for the backend to start up so we can populate the projects list without seeing laggards drop in
        // from the top, as otherwise the backend could still be loading projects by the time we're done loading
        // the UI.
        if (!Main.instance.waitForInit())
            return;  // Backend didn't start up e.g. app is already running.

        projects = Main.backend.mirrorProjects(UI_THREAD);
        projectStates = Main.backend.mirrorProjectStates(UI_THREAD);
        checkStates = Main.backend.mirrorCheckStatuses(UI_THREAD);
        for (Project project : projects) {
            projectsVBox.getChildren().add(0, buildProjectWidget(project));
        }
        projects.addListener((ListChangeListener<Project>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    // Hack: delay until the next iteration of the main loop to give the calling code time to mark the
                    // project as owned by us in the wallet.
                    Platform.runLater(() -> slideInNewProject(change.getAddedSubList().get(0)));
                } else {
                    log.warn("Cannot animate project remove yet");
                }
            }
        });
    }

    private void switchView(Views view) {
        switch (view) {
            case OVERVIEW:
                contentStack.getChildren().remove(projectViewContainer);
                contentStack.getChildren().add(overviewVbox);
                projectView.updateForVisibility(false, null);
                break;
            case PROJECT:
                contentStack.getChildren().remove(overviewVbox);
                contentStack.getChildren().add(projectViewContainer);
                projectView.updateForVisibility(true, checkStates);
                break;
            default: throw new IllegalStateException();
        }
    }

    private void switchToProject(Project next) {
        log.info("Switching to project: {}", next.getTitle());
        projectView.project.set(next);
        switchView(Views.PROJECT);
    }

    // Triggered by the project disk model being adjusted.
    private void slideInNewProject(Project project) {
        if (contentScrollPane.getVvalue() != contentScrollPane.getVmin()) {
            // Need to scroll to the top before dropping the project widget in.
            scrollToTop().setOnFinished(ev -> slideInNewProject(project));
            return;
        }
        ProjectOverviewWidget projectWidget = buildProjectWidget(project);

        // Hack: Add at the end for the size calculation, then we'll move it to the start after the next frame.
        projectWidget.setVisible(false);
        projectsVBox.getChildren().add(projectWidget);

        // Slide in from above.
        Platform.runLater(() -> {
            double amount = projectWidget.getHeight();
            amount += projectsVBox.getSpacing();
            contentHBox.setTranslateY(-amount);
            TranslateTransition transition = new TranslateTransition(Duration.millis(1500), contentHBox);
            transition.setFromY(-amount);
            transition.setToY(0);
            transition.setInterpolator(new ElasticInterpolator(EasingMode.EASE_OUT));
            transition.setDelay(Duration.millis(1000));
            transition.play();
            // Re-position at the start.
            projectsVBox.getChildren().remove(projectWidget);
            projectsVBox.getChildren().add(0, projectWidget);
            projectWidget.setVisible(true);
        });
    }

    private ProjectOverviewWidget buildProjectWidget(Project project) {
        SimpleObjectProperty<LighthouseBackend.ProjectState> state = new SimpleObjectProperty<>(getProjectState(project));
        projectStates.addListener((javafx.beans.InvalidationListener) x -> state.set(getProjectState(project)));
        ProjectOverviewWidget projectWidget = new ProjectOverviewWidget(project,
                Main.backend.makeTotalPledgedProperty(project, UI_THREAD),
                state);
        projectWidget.onCheckStatusChanged(checkStates.get(project));
        checkStates.addListener((MapChangeListener<Project, LighthouseBackend.CheckStatus>) change -> {
            if (change.getKey().equals(project))
                projectWidget.onCheckStatusChanged(change.wasAdded() ? change.getValueAdded() : null);
        });
        projectWidget.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> switchToProject(project));
        return projectWidget;
    }

    private LighthouseBackend.ProjectState getProjectState(Project project) {
        LighthouseBackend.ProjectStateInfo info = projectStates.get(project.getID());
        return info == null ? LighthouseBackend.ProjectState.OPEN : info.state;
    }

    @FXML
    public void addProjectClicked(ActionEvent event) {
        Main.instance.overlayUI("subwindows/add_project.fxml", "Create/import");
    }

    @FXML
    public void backToOverview(ActionEvent event) {
        switchView(Views.OVERVIEW);
    }

    @FXML
    public void dragOver(DragEvent event) {
        boolean accept = true;
        for (File file : event.getDragboard().getFiles()) {
            if (!file.toString().endsWith(".lighthouse-project")) {
                accept = false;
                break;
            }
        }
        if (accept)
            event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
    }

    @FXML
    public void dragDropped(DragEvent event) {
        log.info("Drop: {}", event);
        for (File file : event.getDragboard().getFiles())
            importProject(file);
    }

    public static void importProject(File file) {
        Main.backend.addProjectFile(file.toPath());
    }

    private static boolean firstTime = true;
    public void onBitcoinSetup() {
        bitcoinUIModel.setWallet(Main.wallet);
        addressControl.addressProperty().bind(bitcoinUIModel.addressProperty());
        balance.textProperty().bind(EasyBind.map(bitcoinUIModel.balanceProperty(), coin -> MonetaryFormat.BTC.noCode().format(coin).toString()));
        // Don't let the user click send money when the wallet is empty.
        sendMoneyOutBtn.disableProperty().bind(bitcoinUIModel.balanceProperty().isEqualTo(Coin.ZERO));

        if (Main.params != MainNetParams.get()) {
            networkIndicatorLabel.setVisible(true);
            if (Main.params == TestNet3Params.get())
                networkIndicatorLabel.setText("testnet");
            else if (Main.params == RegTestParams.get())
                networkIndicatorLabel.setText("regtest");
            else
                networkIndicatorLabel.setText("?");
        }

        // Don't do startup processing if the UI is being hot reloaded ...
        if (firstTime) {
            firstTime = false;
            // NotificationBarPane is set up by this point, so we can do things that need to show notifications.
            setupBitcoinSyncNotification();
            doOnlineUpdateCheck();
            maybeShowReleaseNotes();
        }
    }

    private static final String LAST_VER_TAG = "com.vinumeris.lighthouse.lastVer";
    private void maybeShowReleaseNotes() {
        // Show release notes when we've upgraded to a new version (hard coded), but only if this is the first run
        // after the upgrade.
        ByteString bytes = Main.wallet.maybeGetTag(LAST_VER_TAG);
        if (bytes != null) {
            int lastVer = Integer.parseInt(bytes.toStringUtf8());
            if (Main.VERSION > lastVer) {
                log.info("Was upgraded from v{} to v{}!", lastVer, Main.VERSION);

                //
                // No release notes currently.
                //
            }
        }
        Main.wallet.setTag(LAST_VER_TAG, ByteString.copyFromUtf8("" + Main.VERSION));
    }

    private void setupBitcoinSyncNotification() {
        TorClient torClient = Main.bitcoin.peerGroup().getTorClient();
        if (torClient != null) {
            SimpleDoubleProperty torProgress = new SimpleDoubleProperty(-1);
            String torMsg = "Initialising Tor";
            syncItem = Main.instance.notificationBar.displayNewItem(torMsg, torProgress);
            torClient.addInitializationListener(new TorInitializationListener() {
                @Override
                public void initializationProgress(String message, int percent) {
                    Platform.runLater(() -> {
                        syncItem.label.set(torMsg + ": " + message);
                        torProgress.set(percent / 100.0);
                    });
                }

                @Override
                public void initializationCompleted() {
                    Platform.runLater(() -> {
                        syncItem.cancel();
                        showBitcoinSyncMessage();
                    });
                }
            });
        } else {
            showBitcoinSyncMessage();
        }
        bitcoinUIModel.syncProgressProperty().addListener(x -> {
            if (bitcoinUIModel.syncProgressProperty().get() >= 1.0) {
                if (syncItem != null) {
                    // Hack around JFX progress animator leak bug.
                    GuiUtils.runOnGuiThreadAfter(500, () -> {
                        syncItem.cancel();
                        syncItem = null;
                    });
                }
            } else if (syncItem == null) {
                showBitcoinSyncMessage();
            }
        });
    }

    private void doOnlineUpdateCheck() {
        Updater updater = new Updater(Main.instance.updatesURL, Main.APP_NAME, Main.VERSION, AppDirectory.dir(),
                UpdateFX.findCodePath(Main.class), Main.UPDATE_SIGNING_KEYS, Main.UPDATE_SIGNING_THRESHOLD) {
            @Override
            protected void updateProgress(long workDone, long max) {
                super.updateProgress(workDone, max);
                //Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
            }
        };

        if (!Main.instance.updatesURL.equals(Main.UPDATES_BASE_URL))
            updater.setOverrideURLs(true);    // For testing.

        // Only bother to show the user a notification if we're actually going to download an update.
        updater.progressProperty().addListener(new InvalidationListener() {
            private boolean shown = false;

            @Override
            public void invalidated(Observable x) {
                if (shown) return;
                NotificationBarPane.Item downloadingItem = Main.instance.notificationBar.displayNewItem(
                        "Downloading software update", updater.progressProperty());
                updater.setOnSucceeded(ev -> {
                    Button restartButton = new Button("Restart");
                    restartButton.setOnAction(ev2 -> Main.restart());
                    NotificationBarPane.Item newItem = Main.instance.notificationBar.createItem(
                            "Please restart the app to upgrade to the new version.", restartButton);
                    Main.instance.notificationBar.items.replaceAll(item -> item == downloadingItem ? newItem : item);
                });
                updater.setOnFailed(ev -> {
                    downloadingItem.cancel();
                    log.error("Online update check failed", updater.getException());
                    // At this point the user has seen that we're trying to download something so tell them if it went
                    // wrong.
                    if (Main.params != RegTestParams.get())
                        GuiUtils.informationalAlert("Online update failed",
                                "An error was encountered whilst attempting to download or apply an online update: %s",
                                updater.getException());
                });
                shown = true;
            }
        });
        // Don't bother the user if update check failed: assume some temporary server error that can be fixed silently.
        updater.setOnFailed(ev -> log.error("Online update check failed", updater.getException()));
        Thread thread = new Thread(updater, "Online update check");
        thread.setDaemon(true);
        thread.start();
    }

    private void showBitcoinSyncMessage() {
        syncItem = Main.instance.notificationBar.displayNewItem("Synchronising with the Bitcoin network", bitcoinUIModel.syncProgressProperty());
    }

    private Animation scrollToTop() {
        Animation animation = new Timeline(
                new KeyFrame(GuiUtils.UI_ANIMATION_TIME,
                        new KeyValue(contentScrollPane.vvalueProperty(), contentScrollPane.getVmin(), Interpolator.EASE_BOTH)
                )
        );
        animation.play();
        return animation;
    }

    //region Generic Bitcoin wallet related code
    @FXML
    public void sendMoneyOut(ActionEvent event) {
        // Hide this UI and show the send money UI. This UI won't be clickable until the user dismisses send_money.
        Main.instance.overlayUI("subwindows/send_money.fxml", "Send money");
    }

    @FXML
    public void setupWalletClicked(ActionEvent event) {
        Main.OverlayUI<WalletSettingsController> screen = Main.instance.overlayUI("subwindows/wallet_settings.fxml", "Wallet settings");
        screen.controller.initialize(null);
    }
    //endregion
}
