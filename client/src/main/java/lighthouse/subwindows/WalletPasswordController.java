package lighthouse.subwindows;

import com.google.common.primitives.*;
import com.google.protobuf.*;
import javafx.application.*;
import javafx.beans.property.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import lighthouse.*;
import lighthouse.utils.*;
import org.bitcoinj.crypto.*;
import org.slf4j.*;
import org.spongycastle.crypto.params.*;

import java.time.*;
import java.util.function.*;

import static com.google.common.base.Preconditions.*;
import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil.*;

/**
 * User interface for entering a password on demand, e.g. to send money. Also used when encrypting a wallet. Shows a
 * progress meter as we scrypt the password.
 */
public class WalletPasswordController {
    private static final Logger log = LoggerFactory.getLogger(WalletPasswordController.class);

    @FXML HBox buttonsBox;
    @FXML PasswordField pass1;
    @FXML ProgressIndicator progressMeter;
    @FXML HBox widgetBox;
    @FXML Button confirmButton;
    @FXML Button cancelButton;    
    @FXML Label passwordLabel;

    public Main.OverlayUI overlayUI;

    private SimpleObjectProperty<KeyParameter> aesKey = new SimpleObjectProperty<>();

    public void initialize() {
        progressMeter.setOpacity(0);
        Platform.runLater(pass1::requestFocus);
        
        // Load localized strings
        confirmButton.setText(tr("Confirm"));
        cancelButton.setText(tr("Cancel"));
        passwordLabel.setText(tr("Password"));
    }

    public static void requestPasswordWithNextWindow(Consumer<KeyParameter> keyConsumer) {
        Main.OverlayUI<WalletPasswordController> pwd = Main.instance.overlayUI("subwindows/wallet_password.fxml", tr("Password"));
        pwd.controller.aesKeyProperty().addListener((observable, old, cur) -> {
            // We only get here if the user found the right password. If they don't or they cancel, we end up back on
            // the main UI screen.
            checkGuiThread();
            keyConsumer.accept(cur);
        });
    }

    public static void requestPassword(Consumer<KeyParameter> keyConsumer) {
        Main.OverlayUI<WalletPasswordController> pwd = Main.instance.overlayUI("subwindows/wallet_password.fxml", tr("Password"));
        pwd.controller.aesKeyProperty().addListener((observable, old, cur) -> {
            // We only get here if the user found the right password. If they don't or they cancel, we end up back on
            // the main UI screen.
            checkGuiThread();
            keyConsumer.accept(cur);
            pwd.done();
        });
    }

    @FXML void confirmClicked(ActionEvent event) {
        String password = pass1.getText();
        if (password.isEmpty() || password.length() < 4) {
            informationalAlert(tr("Bad password"), tr("The password you entered is empty or too short."));
            return;
        }

        final KeyCrypterScrypt keyCrypter = (KeyCrypterScrypt) Main.bitcoin.wallet().getKeyCrypter();
        checkNotNull(keyCrypter);   // We should never arrive at this GUI if the wallet isn't actually encrypted.
        KeyDerivationTasks tasks = new KeyDerivationTasks(keyCrypter, password, getTargetTime()) {
            @Override
            protected void onFinish(KeyParameter aesKey, int timeTakenMsec) {
                checkGuiThread();
                if (Main.bitcoin.wallet().checkAESKey(aesKey)) {
                    WalletPasswordController.this.aesKey.set(aesKey);
                } else {
                    log.warn("User entered incorrect password");
                    fadeOut(progressMeter);
                    fadeIn(widgetBox);
                    fadeIn(buttonsBox);
                    informationalAlert(tr("Wrong password"),
                            tr("Please try entering your password again, carefully checking for typos or spelling errors."));
                }
            }
        };
        progressMeter.progressProperty().bind(tasks.progress);
        tasks.start();

        fadeIn(progressMeter);
        fadeOut(widgetBox);
        fadeOut(buttonsBox);
    }

    @FXML
    public void cancelClicked(ActionEvent event) {
        overlayUI.done();
    }

    public ReadOnlyObjectProperty<KeyParameter> aesKeyProperty() {
        return aesKey;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static final String TAG = WalletPasswordController.class.getName() + ".target-time";

    // Writes the given time to the wallet as a tag so we can find it again in this class.
    public static void setTargetTime(Duration targetTime) {
        ByteString bytes = ByteString.copyFrom(Longs.toByteArray(targetTime.toMillis()));
        Main.bitcoin.wallet().setTag(TAG, bytes);
    }

    // Reads target time or throws if not set yet (should never happen).
    public static Duration getTargetTime() throws IllegalArgumentException {
        return Duration.ofMillis(Longs.fromByteArray(Main.bitcoin.wallet().getTag(TAG).toByteArray()));
    }
}
