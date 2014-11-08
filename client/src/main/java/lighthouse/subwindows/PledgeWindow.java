package lighthouse.subwindows;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import lighthouse.Main;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.Project;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.ValidationLink;
import lighthouse.wallet.PledgingWallet;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkState;
import static lighthouse.utils.GuiUtils.valueOrNull;
import static lighthouse.utils.GuiUtils.valueOrThrow;

/**
 * Window which asks user to specify the amount they want to pledge.
 */
public class PledgeWindow extends InnerWindow {
    private static final Logger log = LoggerFactory.getLogger(PledgeWindow.class);

    @FXML TextField amountEdit;
    @FXML Button confirmButton;
    @FXML Label minersFeeLabel;
    @FXML TextArea messageEdit;
    @FXML TextField emailEdit;

    // Will be initialised by the ProjectView.
    public Project project;

    private Coin max, min;
    public Runnable onSuccess;

    public void initialize() {
        ValidationLink amountLink = new ValidationLink(amountEdit, str -> {
            // Can't pledge more than our balance or more than the project is trying to actually raise
            // as excess would go to miners fees.
            Coin coin = valueOrNull(str);
            boolean valid = coin != null && coin.compareTo(max) <= 0 && coin.compareTo(min) >= 0;
            minersFeeLabel.setVisible(valid && !coin.equals(Main.wallet.getBalance()));
            return valid;
        });
        ValidationLink emailLink = new ValidationLink(emailEdit, str -> str.contains("@"));
        ValidationLink.autoDisableButton(confirmButton, amountLink, emailLink);

        String savedContact = Main.instance.prefs.getContactAddress();
        if (savedContact != null)
            emailEdit.setText(savedContact);
    }

    public void setLimits(Coin limit, Coin min) {
        // Note that we don't subtract the fee here because if the user pledges their entire balance, we should not
        // require a dependency tx as all outputs can be included in the pledge.
        // TODO: Make sure that it actually works this way when we sent multiple payments to the app.
        this.max = Coin.valueOf(Math.min(limit.value, Main.wallet.getBalance().value));
        checkState(!max.isNegative());
        this.min = min;
        log.info("Max {}    Min {}", max, min);
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
                WalletPasswordController.requestPassword(this::tryMakePledge);
            } else {
                tryMakePledge(null);
            }
        });
    }

    private void tryMakePledge(@Nullable KeyParameter aesKey) {
        try {
            LHProtos.PledgeDetails.Builder details = LHProtos.PledgeDetails.newBuilder();
            if (!emailEdit.getText().isEmpty()) {
                details.setContactAddress(emailEdit.getText());
                Main.instance.prefs.setContactAddress(emailEdit.getText());
            }
            if (!messageEdit.getText().isEmpty())
                details.setMemo(messageEdit.getText());
            PledgingWallet.PendingPledge pledge = Main.wallet.createPledge(project, valueOrThrow(amountEdit.getText()), aesKey, details.build());
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

    @FXML
    public void minMoneyClicked(MouseEvent event) {
        log.info("Minimum amount possible clicked");
        amountEdit.setText(min.toPlainString());
    }
}
