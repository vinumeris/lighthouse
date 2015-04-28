package lighthouse;

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
import javafx.scene.layout.*;
import lighthouse.activities.*;
import lighthouse.controls.*;
import lighthouse.model.*;
import lighthouse.nav.*;
import lighthouse.subwindows.*;
import lighthouse.utils.*;
import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.bitcoinj.utils.*;
import org.fxmisc.easybind.*;
import org.slf4j.*;

import java.net.*;

import static javafx.beans.binding.Bindings.*;
import static lighthouse.protocol.LHUtils.*;
import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil.*;

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
    @FXML HBox topBox;
    @FXML VBox root;
    @FXML ScrollPane contentScrollPane;
    @FXML StackPane contentStack;
    @FXML Label networkIndicatorLabel;
    @FXML Button backButton;

    public static BitcoinUIModel bitcoinUIModel = new BitcoinUIModel();
    private NotificationBarPane.Item syncItem;
    private ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;

    public static OverviewActivity overviewActivity;
    public static NavManager navManager;

    private static Updater updater;

    // Called by FXMLLoader.
    public void initialize() {
        AwesomeDude.setIcon(emptyWalletBtn, AwesomeIcon.SIGN_OUT, "12pt", ContentDisplay.LEFT);
        Tooltip.install(emptyWalletBtn, new Tooltip(tr("Send money out of the wallet")));
        AwesomeDude.setIcon(setupWalletBtn, AwesomeIcon.LOCK, "12pt", ContentDisplay.LEFT);
        Tooltip.install(setupWalletBtn, new Tooltip(tr("Make paper backup and encrypt your wallet")));

        AwesomeDude.setIcon(menuBtn, AwesomeIcon.BARS);

        // Wait for the backend to start up so we can populate the projects list without seeing laggards drop in
        // from the top, as otherwise the backend could still be loading projects by the time we're done loading
        // the UI.
        if (!Main.instance.waitForInit())
            return;  // Backend didn't start up e.g. app is already running.

        overviewActivity = new OverviewActivity();
        navManager = new NavManager(contentScrollPane, overviewActivity);

        // Slide back button in/out.
        AwesomeDude.setIcon(backButton, AwesomeIcon.ARROW_CIRCLE_LEFT, "30");
        animatedBind(topBoxLeftArea, topBoxLeftArea.translateXProperty(),
                when(navManager.getIsOnInitialActivity()).then(-45).otherwise(0),
                Interpolator.EASE_OUT);
    }

    @FXML
    public void backButtonClicked(ActionEvent event) {
        navManager.back();
    }

    private static boolean firstTime = true;
    public void onBitcoinSetup() {
        checkGuiThread();
        bitcoinUIModel.setWallet(Main.wallet);

        if (Main.wallet.getExtensions().isEmpty()) {
            // TODO: i18n this after next release
            informationalAlert("Error loading wallet",
                            "The Lighthouse specific wallet data failed to load properly. Your money is safe, but the " +
                            "application may not recognise that you have pledged to projects. Please email contact@vinumeris.com " +
                            "and request assistance. You should withdraw your funds using the 'empty wallet' button, which will " +
                            "revoke any pledges you have made.");
        }

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

    private void maybeShowReleaseNotes() {
        // Show release notes when we've upgraded to a new version (hard coded), but only if this is the first run
        // after the upgrade.
        int lastRunVersion = Main.instance.prefs.getLastRunVersion();
        if (lastRunVersion < Main.VERSION) {
            log.info("Was upgraded from v{} to v{}!", lastRunVersion, Main.VERSION);

            //
            // No release notes currently.
            //
        }
        Main.instance.prefs.setLastRunVersion(Main.VERSION);
    }

    private void setupBitcoinSyncNotification() {
        if (Main.offline) {
            Main.instance.notificationBar.displayNewItem(tr("You are offline. You will not be able to use the app until you go online and restart."));
            emptyWalletBtn.disableProperty().unbind();
            emptyWalletBtn.setDisable(true);
            return;
        }
        balance.setStyle("-fx-text-fill: grey");
        TorClient torClient = Main.bitcoin.peerGroup().getTorClient();
        if (torClient != null) {
            SimpleDoubleProperty torProgress = new SimpleDoubleProperty(-1);
            String torMsg = tr("Initialising Tor");
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
                        balance.setStyle("-fx-text-fill: black");
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
        updater = new Updater(URI.create(Main.instance.updatesURL), Main.APP_NAME, Main.unadjustedAppDir,
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
                        tr("Downloading software update"), updater.progressProperty());
                updater.setOnSucceeded(ev -> {
                    UpdateFXWindow.saveCachedIndex(unchecked(updater::get).updates);
                    Button restartButton = new Button(tr("Restart"));
                    restartButton.setOnAction(ev2 -> Main.restart());
                    NotificationBarPane.Item newItem = Main.instance.notificationBar.createItem(
                            tr("Please restart the app to upgrade to the new version."), restartButton);
                    Main.instance.notificationBar.items.replaceAll(item -> item == downloadingItem ? newItem : item);
                });
                updater.setOnFailed(ev -> {
                    downloadingItem.cancel();
                    log.error("Online update check failed", updater.getException());
                    // At this point the user has seen that we're trying to download something so tell them if it went
                    // wrong.
                    if (Main.params != RegTestParams.get())
                        GuiUtils.informationalAlert(tr("Online update failed"),
                                // TRANS: %s = error message
                                tr("An error was encountered whilst attempting to download or apply an online update: %s"),
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
        syncItem = Main.instance.notificationBar.displayNewItem(tr("Synchronising with the Bitcoin network"), bitcoinUIModel.syncProgressProperty());
    }

    @FXML
    public void menuClicked(ActionEvent event) {
        // For now just skip straight to the only menu item: the update control panel.
        UpdateFXWindow.open(updater);
    }

    public void tellUserToSendSomeMoney() {
        GuiUtils.arrowBubbleToNode(balanceArea, tr("You don't have any bitcoins in this wallet")).thenRun(() -> {
            GuiUtils.arrowBubbleToNode(addressControl, tr("Send some money to this address first"));
        });
    }

    @FXML
    public void emptyWallet(ActionEvent event) {
        EmptyWalletController.open();
    }

    @FXML
    public void setupWalletClicked(ActionEvent event) {
        WalletSettingsController.open(null);
    }
}
