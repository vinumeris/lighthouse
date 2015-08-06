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
import kotlin.*;
import kotlin.jvm.functions.Function2;
import lighthouse.activities.*;
import lighthouse.controls.*;
import lighthouse.model.*;
import lighthouse.nav.*;
import lighthouse.protocol.*;
import lighthouse.subwindows.*;
import lighthouse.utils.*;
import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.bitcoinj.utils.*;
import org.fxmisc.easybind.*;
import org.slf4j.*;

import static javafx.beans.binding.Bindings.*;
import static lighthouse.UpdateCheckStrings.*;
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
        Main.reached("MainWindow.initialize()");
        AwesomeDude.setIcon(emptyWalletBtn, AwesomeIcon.SIGN_OUT, "12pt", ContentDisplay.LEFT);
        Tooltip.install(emptyWalletBtn, new Tooltip(tr("Send money out of the wallet")));
        AwesomeDude.setIcon(setupWalletBtn, AwesomeIcon.LOCK, "12pt", ContentDisplay.LEFT);
        Tooltip.install(setupWalletBtn, new Tooltip(tr("Make paper backup and encrypt your wallet")));

        AwesomeDude.setIcon(menuBtn, AwesomeIcon.BARS);


        LHUtils.stopwatch("Build overview activity", () -> overviewActivity = new OverviewActivity());
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
        configureEmptyWalletButton();

        if (Main.params != MainNetParams.get()) {
            networkIndicatorLabel.setVisible(true);
            if (Main.params == TestNet3Params.get())
                networkIndicatorLabel.setText("testnet");
            else if (Main.params == RegTestParams.get())
                networkIndicatorLabel.setText("regtest");
            else
                networkIndicatorLabel.setText("?");
        }

        configureOfflineNotification();

        // Don't do startup processing if the UI is being hot reloaded ...
        if (firstTime) {
            firstTime = false;
            // NotificationBarPane is set up by this point, so we can do things that need to show notifications.
            setupBitcoinSyncNotification();
            doOnlineUpdateCheck();
            maybeShowReleaseNotes();
        }
    }

    public void configureOfflineNotification() {
        synchronized (Main.bitcoin.getOffline()) {
            Main.bitcoin.getOffline().addListener(new InvalidationListener() {
                private NotificationBarPane.Item item;

                @Override
                public void invalidated(Observable ob) {
                    Platform.runLater(() -> {
                        if (Main.bitcoin.isOffline()) {
                            item = Main.instance.notificationBar.displayNewItem(
                                    tr("You are offline. You will not be able to use the app until you go online and restart."));
                            emptyWalletBtn.disableProperty().unbind();
                            emptyWalletBtn.setDisable(true);
                        } else {
                            if (item != null) {
                                item.cancel();
                                item = null;
                            }
                            emptyWalletBtn.setDisable(false);
                            configureEmptyWalletButton();
                        }
                    });
                }
            });
        }
    }

    public void configureEmptyWalletButton() {
        // Don't let the user click send money when the wallet is empty.
        emptyWalletBtn.disableProperty().bind(bitcoinUIModel.balanceProperty().isEqualTo(Coin.ZERO));
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
        balance.setStyle("-fx-text-fill: grey");
        TorClient torClient = Main.bitcoin.getPeers().getTorClient();
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
        }
        bitcoinUIModel.syncProgressProperty().addListener(x -> {
            double progress = bitcoinUIModel.syncProgressProperty().get();
            if (progress >= 1.0) {
                if (syncItem != null) {
                    // Hack around JFX progress animator leak bug.
                    GuiUtils.runOnGuiThreadAfter(500, () -> {
                        syncItem.cancel();
                        syncItem = null;
                        balance.setStyle("-fx-text-fill: black");
                    });
                }
            } else if (syncItem == null && progress > 0.0 && progress < 1.0) {
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

        if (Main.bitcoin.isOffline()) return;

        OnlineUpdateChecks checker = new OnlineUpdateChecks(new Function2<UpdateState, OnlineUpdateChecks, Unit>() {
            // Only bother to show the user a notification if we're actually going to download an update.
            private NotificationBarPane.Item downloadingItem;

            @Override
            public Unit invoke(UpdateState state, OnlineUpdateChecks updater) {
                switch (state) {
                    case DOWNLOADING:
                        downloadingItem = Main.instance.notificationBar.displayNewItem(DOWNLOADING_SOFTWARE_UPDATE, updater.getProgress());
                        break;
                    case AWAITING_APP_RESTART:
                        Button btn = new Button(RESTART);
                        btn.setOnAction(ev2 -> Main.restart());
                        NotificationBarPane.Item newItem = Main.instance.notificationBar.createItem(PLEASE_RESTART_NOW, btn);
                        Main.instance.notificationBar.items.replaceAll(item -> item == downloadingItem ? newItem : item);
                        break;
                    case FAILED:
                        downloadingItem.cancel();
                        break;
                }
                return null;
            }
        });

        MainWindow.updater = checker.getUpdater();   // TODO: Refactor this
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
