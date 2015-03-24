package lighthouse.subwindows;

import com.google.common.base.*;
import javafx.application.Platform;
import javafx.beans.binding.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import lighthouse.*;
import lighthouse.utils.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.wallet.*;
import org.slf4j.*;
import org.spongycastle.crypto.params.*;

import javax.annotation.*;
import java.time.*;
import java.util.*;

import static com.google.common.base.Preconditions.*;
import static javafx.beans.binding.Bindings.*;
import static lighthouse.protocol.LHUtils.*;
import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil._;

public class WalletSettingsController {
    private static final Logger log = LoggerFactory.getLogger(WalletSettingsController.class);

    @FXML Button passwordButton;
    @FXML DatePicker datePicker;
    @FXML TextArea wordsArea;
    @FXML Button restoreButton;
    @FXML Label walletWordsLabel;
    @FXML Label createdOnLabel;
    @FXML Button closeButton;

    public Main.OverlayUI overlayUI;

    private KeyParameter aesKey;

    public static void open(@Nullable KeyParameter key) {
        checkGuiThread();
        Main.OverlayUI<WalletSettingsController> screen = Main.instance.overlayUI("subwindows/wallet_settings.fxml", _("Wallet settings"));
        screen.controller.initialize(key);
    }

    // Note: NOT called by FXMLLoader!
    public void initialize(@Nullable KeyParameter aesKey) {
        DeterministicSeed seed = Main.bitcoin.wallet().getKeyChainSeed();
        if (aesKey == null) {
            if (seed.isEncrypted()) {
                log.info("Wallet is encrypted, requesting password first.");
                // Delay execution of this until after we've finished initialising this screen.
                Platform.runLater(this::askForPasswordAndRetry);
                return;
            }
        } else {
            this.aesKey = aesKey;
            seed = seed.decrypt(checkNotNull(Main.bitcoin.wallet().getKeyCrypter()), "", aesKey);
            // Now we can display the wallet seed as appropriate.
            passwordButton.setText(_("Remove password"));
        }

        // Set the date picker to show the birthday of this wallet.
        Instant creationTime = Instant.ofEpochSecond(seed.getCreationTimeSeconds());
        LocalDate origDate = creationTime.atZone(ZoneId.systemDefault()).toLocalDate();
        datePicker.setValue(origDate);

        // Set the mnemonic seed words.
        final List<String> mnemonicCode = seed.getMnemonicCode();
        checkNotNull(mnemonicCode);    // Already checked for encryption.
        String origWords = Joiner.on(" ").join(mnemonicCode);
        wordsArea.setText(origWords);

        // Validate words as they are being typed.
        MnemonicCode codec = unchecked(MnemonicCode::new);
        TextFieldValidator validator = new TextFieldValidator(wordsArea, text ->
            !didThrow(() -> codec.check(Splitter.on(' ').splitToList(text)))
        );

        // Clear the date picker if the user starts editing the words, if it contained the current wallets date.
        // This forces them to set the birthday field when restoring.
        wordsArea.textProperty().addListener(o -> {
            if (origDate.equals(datePicker.getValue()))
                datePicker.setValue(null);
        });

        BooleanBinding datePickerIsInvalid = or(
                datePicker.valueProperty().isNull(),

                createBooleanBinding(() ->
                        datePicker.getValue().isAfter(LocalDate.now())
                , /* depends on */ datePicker.valueProperty())
        );

        // Don't let the user click restore if the words area contains the current wallet words, or are an invalid set,
        // or if the date field isn't set, or if it's in the future.
        restoreButton.disableProperty().bind(
                or(
                        or(
                                not(validator.valid),
                                equal(origWords, wordsArea.textProperty())
                        ),

                        datePickerIsInvalid
                )
        );

        // Highlight the date picker in red if it's empty or in the future, so the user knows why restore is disabled.
        datePickerIsInvalid.addListener((dp, old, cur) -> {
            if (cur) {
                datePicker.getStyleClass().add("validation_error");
            } else {
                datePicker.getStyleClass().remove("validation_error");
            }
        });
        
        // Load localized strings
        walletWordsLabel.setText(_("These are your wallet words. Write them down along with the creation date, and you can get your money back " +
        "even if you lose all your wallet backup files. Just type the details back in below to restore!"));
        createdOnLabel.setText(_("Created on:"));
        passwordButton.setText(_("Set password"));
        restoreButton.setText(_("Restore from words"));
        closeButton.setText(_("Close"));
    }

    private void askForPasswordAndRetry() {
        WalletPasswordController.requestPasswordWithNextWindow(WalletSettingsController::open);
    }

    @FXML
    public void closeClicked(ActionEvent event) {
        overlayUI.done();
    }

    @FXML
    public void restoreClicked(ActionEvent event) {
        // Don't allow a restore unless this wallet is presently empty. We don't want to end up with two wallets, too
        // much complexity, even though WalletAppKit will keep the current one as a backup file in case of disaster.
        if (Main.bitcoin.wallet().getBalance().value > 0) {
            informationalAlert(_("Wallet is not empty"),
                    _("You must empty this wallet out before attempting to restore an older one, as mixing wallets " +
                            "together can lead to invalidated backups."));
            return;
        }

        if (aesKey != null) {
            // This is weak. We should encrypt the new seed here.
            informationalAlert(_("Wallet is encrypted"),
                    _("After restore, the wallet will no longer be encrypted and you must set a new password."));
        }

        log.info("Attempting wallet restore using seed '{}' from date {}", wordsArea.getText(), datePicker.getValue());
        informationalAlert(_("Wallet restore in progress"),
                _("Your wallet will now be resynced from the Bitcoin network. This can take a long time for old wallets."));
        overlayUI.done();

        long birthday = datePicker.getValue().atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        DeterministicSeed seed = new DeterministicSeed(Splitter.on(' ').splitToList(wordsArea.getText()), null, "", birthday);
        // Shut down bitcoinj and restart it with the new seed.
        Main.restartBitcoinJ(seed);
    }

    @FXML
    public void passwordButtonClicked(ActionEvent event) {
        if (aesKey == null) {
            Main.instance.overlayUI("subwindows/wallet_set_password.fxml", _("Set password"));
        } else {
            Main.bitcoin.wallet().decrypt(aesKey);
            informationalAlert(_("Wallet decrypted"), _("A password will no longer be required to send money or edit settings."));
            passwordButton.setText(_("Set password"));
            aesKey = null;
        }
    }
}
