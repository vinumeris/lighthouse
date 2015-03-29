package lighthouse.subwindows;

import com.vinumeris.crashfx.*;
import javafx.application.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import lighthouse.*;
import lighthouse.protocol.*;
import lighthouse.utils.*;
import lighthouse.wallet.*;
import org.bitcoinj.core.*;
import org.slf4j.*;
import org.spongycastle.crypto.params.*;

import javax.annotation.*;

import static com.google.common.base.Preconditions.*;
import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil.*;

/**
 * Window which asks user to specify the amount they want to pledge.
 */
public class PledgeWindow extends InnerWindow {
    private static final Logger log = LoggerFactory.getLogger(PledgeWindow.class);

    @FXML TextField amountEdit;
    @FXML Button confirmButton;
    @FXML Label minersFeeLabel;
    @FXML Label pubPrivLabel;
    @FXML TextArea messageEdit;
    @FXML TextField emailEdit;
    @FXML TextField nameEdit;

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
        ValidationLink emailLink = new ValidationLink(emailEdit, str -> str.matches("^[\\w-_\\.+]*[\\w-_\\.]\\@([\\w]+\\.)+[\\w]+[\\w]$"));
        ValidationLink.autoDisableButton(confirmButton, amountLink, emailLink);

        String savedContact = Main.instance.prefs.getContactAddress();
        if (savedContact != null)
            emailEdit.setText(savedContact);

        minersFeeLabel.setText(String.format(minersFeeLabel.getText(), Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.toFriendlyString()));
    }

    public void setProject(Project project) {
        this.project = project;
        // Until we do encryption of data in pledges, serverless projects are different to server assisted.
        if (project.getPaymentURL() != null) {
            pubPrivLabel.setText(tr("Name and message will be public."));
        } else {
            pubPrivLabel.setText(tr("Name, email and message will be public."));
        }
    }

    public void setLimits(Coin limit, Coin min) {
        // Note that we don't subtract the fee here because if the user pledges their entire balance, we should not
        // require a dependency tx as all outputs can be included in the pledge.
        // TODO: Make sure that it actually works this way when we sent multiple payments to the app.
        this.max = Coin.valueOf(Math.min(limit.value, Main.wallet.getBalance().value));
        checkState(!max.isNegative());
        this.min = min;
        log.info("Max {}    Min {}", max, min);
        // TRANS: %s = example BTC amount limit
        amountEdit.setPromptText(String.format(tr("e.g. %s"), max.toPlainString()));
    }

    @FXML
    public void confirmClicked() {
        log.info("Confirm pledge clicked: {}", amountEdit.getText());

        // runLater: shitty hack around RT-37821 (consider upgrading to 8u40 when available and/or applying fix locally)
        // otherwise pressing enter can cause a crash here when we open a new window with a default button
        Platform.runLater(() -> {
            if (Main.wallet.isEncrypted()) {
                log.info("Wallet is encrypted, requesting password");
                WalletPasswordController.requestPasswordWithNextWindow(this::tryMakePledge);
            } else {
                tryMakePledge(null);
            }
        });
    }

    private void tryMakePledge(@Nullable KeyParameter aesKey) {
        try {
            LHProtos.PledgeDetails.Builder details = LHProtos.PledgeDetails.newBuilder();
            details.setContactAddress(emailEdit.getText());
            Main.instance.prefs.setContactAddress(emailEdit.getText());
            if (!messageEdit.getText().isEmpty())
                details.setMemo(messageEdit.getText());
            if (!nameEdit.getText().isEmpty())
                details.setName(nameEdit.getText());
            PledgingWallet.PendingPledge pledge = Main.wallet.createPledge(project, valueOrThrow(amountEdit.getText()), aesKey, details.buildPartial());
            log.info("Created pledge is {}", pledge);
            if (project.getPaymentURL() == null) {
                // Show drag/drop icon and file save button. This will automatically finish this overlay UI too.
                ExportWindow.openForPledge(project, pledge);
            } else {
                PledgeUploadWindow.open(project, pledge, onSuccess);
            }
        } catch (InsufficientMoneyException e) {
            // This should not be possible because we disable the confirm button if the amount doesn't validate.
            CrashWindow.open(e);
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
