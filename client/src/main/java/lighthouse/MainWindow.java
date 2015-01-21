package lighthouse;

import com.google.protobuf.*;
import com.subgraph.orchid.*;
import com.vinumeris.updatefx.*;
import de.jensd.fx.fontawesome.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.beans.*;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.*;
import lighthouse.controls.*;
import lighthouse.files.AppDirectory;
import lighthouse.files.*;
import lighthouse.model.*;
import lighthouse.protocol.*;
import lighthouse.subwindows.*;
import lighthouse.utils.*;
import lighthouse.utils.easing.*;
import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.bitcoinj.utils.*;
import org.fxmisc.easybind.*;
import org.slf4j.*;

import java.io.*;
import java.nio.file.*;

import static javafx.beans.binding.Bindings.*;
import static lighthouse.protocol.LHUtils.*;
import static lighthouse.threading.AffinityExecutor.*;
import static lighthouse.utils.GuiUtils.*;

/**
 * Gets created auto-magically by FXMLLoader via reflection. The widget fields are set to the GUI controls they're named
 * after. This class handles all the updates and event handling for the main UI.
 */
public class MainWindow {
    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    @FXML HBox topBoxLeftArea;
    @FXML Label balance;
    @FXML Button emptyWalletBtn, setupWalletBtn, menuBtn;
    @FXML ClickableBitcoinAddress addressControl;
    @FXML HBox balanceArea;
    @FXML VBox projectsVBox;
    @FXML HBox topBox;
    @FXML VBox root;
    @FXML HBox contentHBox;
    @FXML ScrollPane contentScrollPane;
    @FXML ProjectView projectView;
    @FXML StackPane projectViewContainer;
    @FXML VBox overviewVbox;
    @FXML VBox contentStack;
    @FXML Label addProjectIcon;
    @FXML Label networkIndicatorLabel;
    @FXML Button backButton;

    // These are read-only mirrors of sets maintained by the backend. Changes made by LighthouseBackend are propagated
    // into the UI thread and applied there asynchronously, thus it is safe to connect them directly to UI widgets.
    private ObservableList<Project> projects;

    public static BitcoinUIModel bitcoinUIModel = new BitcoinUIModel();
    private NotificationBarPane.Item syncItem;
    private ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;
    // A map indicating the status of checking each project against the network (downloading, found an error, done, etc)
    // This is mirrored into the UI thread from the backend.
    private ObservableMap<Project, LighthouseBackend.CheckStatus> checkStates;

    private SimpleBooleanProperty inProjectView = new SimpleBooleanProperty();

    private int numInitialBoxes;

    private static Updater updater;

    enum Views {
        OVERVIEW,
        PROJECT
    }

    // Called by FXMLLoader.
    public void initialize() {
        numInitialBoxes = projectsVBox.getChildren().size();

        AwesomeDude.setIcon(emptyWalletBtn, AwesomeIcon.SIGN_OUT, "12pt", ContentDisplay.LEFT);
        Tooltip.install(emptyWalletBtn, new Tooltip("Send money out of the wallet"));
        AwesomeDude.setIcon(setupWalletBtn, AwesomeIcon.LOCK, "12pt", ContentDisplay.LEFT);
        Tooltip.install(setupWalletBtn, new Tooltip("Make paper backup and encrypt your wallet"));
        AwesomeDude.setIcon(addProjectIcon, AwesomeIcon.FILE_ALT, "50pt; -fx-text-fill: white" /* lame hack */);

        // Slide back button in/out.
        AwesomeDude.setIcon(backButton, AwesomeIcon.ARROW_CIRCLE_LEFT, "30");
        animatedBind(topBoxLeftArea, topBoxLeftArea.translateXProperty(), when(inProjectView).then(0).otherwise(-45), Interpolator.EASE_OUT);

        AwesomeDude.setIcon(menuBtn, AwesomeIcon.BARS);

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
        for (Project project : projects)
            projectsVBox.getChildren().add(0, buildProjectWidget(project));
        projects.addListener((ListChangeListener<Project>) change -> {
            while (change.next()) {
                if (change.wasReplaced()) {
                    updateExistingProject(change.getFrom(), change.getAddedSubList().get(0), change.getRemoved().get(0));
                } else if (change.wasAdded()) {
                    slideInNewProject(change.getAddedSubList().get(0));
                } else if (change.wasRemoved()) {
                    log.warn("Cannot animate project remove yet: {}", change);
                    projectsVBox.getChildren().remove(projectsVBox.getChildren().size() - 1 - numInitialBoxes - change.getFrom());
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
                inProjectView.set(false);
                break;
            case PROJECT:
                contentStack.getChildren().remove(overviewVbox);
                contentStack.getChildren().add(projectViewContainer);
                projectView.updateForVisibility(true, checkStates);
                inProjectView.set(true);
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
    private void updateExistingProject(int index, Project newProject, Project prevProject) {
        log.info("Update at index {}", index);
        int uiIndex =
                projectsVBox.getChildren().size()
                        - 1   // from size to index
                        - numInitialBoxes   // the vbox for buttons at the bottom
                        - index;
        if (uiIndex < 0)
            return;  // This can happen if the project which is updated is not even on screen yet; Windows fucks up sometimes and tells us this so just ignore it.
        projectsVBox.getChildren().set(uiIndex, buildProjectWidget(newProject));
        if (inProjectView.get() && projectView.getProject().equals(prevProject)) {
            projectView.setProject(newProject);
        }
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

        ProjectOverviewWidget projectWidget;
        if (Main.offline) {
            state.set(LighthouseBackend.ProjectState.UNKNOWN);
            projectWidget = new ProjectOverviewWidget(project, new SimpleLongProperty(0), state);
        } else {
            projectStates.addListener((javafx.beans.InvalidationListener) x -> state.set(getProjectState(project)));
            projectWidget = new ProjectOverviewWidget(project,
                    Main.backend.makeTotalPledgedProperty(project, UI_THREAD),
                    state);
            projectWidget.getStyleClass().add("project-overview-widget-clickable");
            projectWidget.onCheckStatusChanged(checkStates.get(project));
            checkStates.addListener((MapChangeListener<Project, LighthouseBackend.CheckStatus>) change -> {
                if (change.getKey().equals(project))
                    projectWidget.onCheckStatusChanged(change.wasAdded() ? change.getValueAdded() : null);
            });
            projectWidget.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> switchToProject(project));
        }
        return projectWidget;
    }

    private LighthouseBackend.ProjectState getProjectState(Project project) {
        LighthouseBackend.ProjectStateInfo info = projectStates.get(project.getID());
        return info == null ? LighthouseBackend.ProjectState.OPEN : info.state;
    }

    @FXML
    public void addProjectClicked(ActionEvent event) {
        EditProjectWindow.openForCreate();
    }

    @FXML
    public void importClicked(ActionEvent event) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a bitcoin project file to import");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Project/contract files", "*" + DiskManager.PROJECT_FILE_EXTENSION));
                platformFiddleChooser(chooser);
        File file = chooser.showOpenDialog(Main.instance.mainStage);
        if (file == null)
            return;
        log.info("Import clicked: {}", file);
        importProject(file);
    }

    @FXML
    public void backToOverview(ActionEvent event) {
        switchView(Views.OVERVIEW);
    }

    @FXML
    public void dragOver(DragEvent event) {
        boolean accept = false;
        if (event.getGestureSource() != null)
            return;   // Coming from us.
        for (File file : event.getDragboard().getFiles()) {
            if (file.toString().endsWith(DiskManager.PROJECT_FILE_EXTENSION) || file.toString().endsWith(DiskManager.PLEDGE_FILE_EXTENSION)) {
                accept = true;
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
            handleOpenedFile(file);
    }

    public void handleOpenedFile(File file) {
        // Can be called either due to a drop, or user double clicking a file in a file explorer.
        checkGuiThread();
        if (file.toString().endsWith(DiskManager.PROJECT_FILE_EXTENSION)) {
            importProject(file);
        } else if (file.toString().endsWith(DiskManager.PLEDGE_FILE_EXTENSION)) {
            importPledge(file);
        } else
            log.error("Unknown file type open requested: should not happen: " + file);
    }

    public static void importPledge(File file) {
        try {
            Sha256Hash hash = Sha256Hash.hashFileContents(file);
            Files.copy(file.toPath(), AppDirectory.dir().resolve(hash + DiskManager.PLEDGE_FILE_EXTENSION));
        } catch (IOException e) {
            GuiUtils.informationalAlert("Import failed",
                    "Could not copy the dropped pledge into the %s application directory: " + e, Main.APP_NAME);
        }
    }


    public static void importProject(File file) {
        importProject(file.toPath());
    }

    public static void importProject(Path file) {
        try {
            Main.backend.importProjectFrom(file);
        } catch (IOException e) {
            GuiUtils.informationalAlert("Failed to import project",
                    "Could not read project file: " + e.getLocalizedMessage());
        }
    }

    private static boolean firstTime = true;
    public void onBitcoinSetup() {
        checkGuiThread();
        bitcoinUIModel.setWallet(Main.wallet);
        addressControl.addressProperty().bind(bitcoinUIModel.addressProperty());
        balance.textProperty().bind(EasyBind.map(bitcoinUIModel.balanceProperty(), coin -> MonetaryFormat.BTC.noCode().format(coin).toString()));
        // Don't let the user click send money when the wallet is empty.
        emptyWalletBtn.disableProperty().bind(bitcoinUIModel.balanceProperty().isEqualTo(Coin.ZERO));

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
        if (Main.offline) {
            Main.instance.notificationBar.displayNewItem("You are offline. You will not be able to use the app until you go online and restart.");
            emptyWalletBtn.disableProperty().unbind();
            emptyWalletBtn.setDisable(true);
            return;
        }
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

    @SuppressWarnings("ConstantConditions")
    private void doOnlineUpdateCheck() {
        if (Main.UPDATES_BASE_URL == null) {
            ((HBox)menuBtn.getParent()).getChildren().remove(menuBtn);
            return;
        }
        updater = new Updater(Main.instance.updatesURL, Main.APP_NAME, Main.VERSION, Main.unadjustedAppDir,
                UpdateFX.findCodePath(Main.class), Main.UPDATE_SIGNING_KEYS, Main.UPDATE_SIGNING_THRESHOLD);

        if (Main.offline) return;

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
                    UpdateFXWindow.saveCachedIndex(unchecked(updater::get).updates);
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
        // Save the updates list to disk so we can still display the updates screen even if we're offline.
        updater.setOnSucceeded(ev -> UpdateFXWindow.saveCachedIndex(unchecked(updater::get).updates));
        // Don't bother the user if update check failed: assume some temporary server error that can be fixed silently.
        updater.setOnFailed(ev -> log.error("Online update check failed", updater.getException()));
        Thread thread = new Thread(updater, "Online update check");
        thread.setDaemon(true);
        thread.start();
    }

    public void showBitcoinSyncMessage() {
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

    @FXML
    public void menuClicked(ActionEvent event) {
        // For now just skip straight to the only menu item: the update control panel.
        UpdateFXWindow.open(updater);
    }

    public void tellUserToSendSomeMoney() {
        GuiUtils.arrowBubbleToNode(balanceArea, "You don't have any bitcoins in this wallet").thenRun(() -> {
            GuiUtils.arrowBubbleToNode(addressControl, "Send some money to this address first");
        });
    }

    @FXML
    public void onRedditAdClicked(MouseEvent event) {
        log.info("reddit ad clicked");
        Main.instance.getHostServices().showDocument("https://www.reddit.com/r/LighthouseProjects");
    }

    //region Generic Bitcoin wallet related code
    @FXML
    public void emptyWallet(ActionEvent event) {
        EmptyWalletController.open();
    }

    @FXML
    public void setupWalletClicked(ActionEvent event) {
        Main.OverlayUI<WalletSettingsController> screen = Main.instance.overlayUI("subwindows/wallet_settings.fxml", "Wallet settings");
        screen.controller.initialize(null);
    }
    //endregion
}
