package lighthouse.subwindows;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import lighthouse.Main;
import lighthouse.protocol.Project;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.ValidationLink;
import lighthouse.wallet.PledgingWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkState;
import static lighthouse.utils.GuiUtils.checkGuiThread;
import static lighthouse.utils.GuiUtils.valueOrThrow;

/**
 * Window which asks user to specify the amount they want to pledge.
 */
public class PledgeWindow extends InnerWindow {
    private static final Logger log = LoggerFactory.getLogger(PledgeWindow.class);

    @FXML TextField amountEdit;
    @FXML Button confirmButton;

    // Will be initialised by the ProjectView.
    public Project project;

    private Coin max;
    public Runnable onSuccess;

    public void initialize() {
        ValidationLink amountLink = new ValidationLink(amountEdit, str -> {
            try {
                // Can't pledge more than our balance or more than the project is trying to actually raise
                // as excess would go to miners fees.
                return valueOrThrow(str).compareTo(max) <= 0;
            } catch (NumberFormatException e) {
                return false;
            }
        });
        ValidationLink.autoDisableButton(confirmButton, amountLink);
    }

    public void setLimit(Coin limit) {
        // Note that we don't subtract the fee here because if the user pledges their entire balance, we should not
        // require a dependency tx as all outputs can be included in the pledge.
        // TODO: Make sure that it actually works this way when we sent multiple payments to the app.
        max = Coin.valueOf(Math.min(limit.value, Main.wallet.getBalance().value));
        checkState(!max.isNegative());
        amountEdit.setPromptText("e.g. " + max.toPlainString());
    }

    @FXML
    public void confirmClicked() {
        log.info("Confirm pledge clicked: {}", amountEdit.getText());

        // runLater: shitty hack around RT-37821 (consider upgrading to 8u40 when available and/or applying fix locally)
        // otherwise pressing enter can cause a crash here when we open a new window with a default button
        Platform.runLater(() -> {
            if (Main.wallet.isEncrypted()) {
                log.info("Wallet is encrypted, requesting password");
                Main.OverlayUI<WalletPasswordController> pwd = Main.instance.overlayUI("subwindows/wallet_password.fxml", "Password");
                pwd.controller.aesKeyProperty().addListener((observable, old, cur) -> {
                    // We only get here if the user found the right password. If they don't or they cancel, we end up back on
                    // the main UI screen.
                    checkGuiThread();
                    tryMakePledge(cur);
                });
            } else {
                tryMakePledge(null);
            }
        });
    }

    private void tryMakePledge(@Nullable KeyParameter aesKey) {
        try {
            PledgingWallet.PendingPledge pledge = Main.wallet.createPledge(project, valueOrThrow(amountEdit.getText()), aesKey);
            log.info("Created pledge is {}", pledge);
            if (project.getPaymentURL() == null) {
                // Show drag/drop icon and file save button. This will automatically finish this overlay UI too.
                ExportWindow.openForPledge(project, pledge);
            } else {
                PledgeUploadWindow.open(project, pledge, onSuccess);
            }
        } catch (InsufficientMoneyException e) {
            // This should not be possible because we disable the confirm button if the amount doesn't validate.
            GuiUtils.crashAlert(e);
        }
    }

    @FXML
    public void allMoneyClicked(MouseEvent event) {
        log.info("Maximum amount possible clicked");
        amountEdit.setText(max.toPlainString());
    }
}
