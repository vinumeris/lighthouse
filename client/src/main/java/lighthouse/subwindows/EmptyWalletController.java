package lighthouse.subwindows;

import com.google.common.util.concurrent.*;
import com.vinumeris.crashfx.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import lighthouse.*;
import lighthouse.controls.*;
import org.bitcoinj.core.*;
import org.spongycastle.crypto.params.*;

import javax.annotation.*;

import static com.google.common.base.Preconditions.*;
import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil.*;

public class EmptyWalletController {
    public Button sendBtn;
    public Button cancelBtn;
    public TextField address;
    public Label titleLabel;

    public Main.OverlayUI overlayUI;

    private Wallet.SendResult sendResult;
    private KeyParameter aesKey;

    // Called by FXMLLoader
    public void initialize() {
        checkState(!Main.bitcoin.wallet().getBalance().isZero());
        new BitcoinAddressValidator(Main.params, address, sendBtn);
    }

    @FXML
    public void cancel(ActionEvent event) {
        overlayUI.done();
    }

    public static Main.OverlayUI<EmptyWalletController> open() {
        return Main.instance.overlayUI("subwindows/send_money.fxml", tr("Empty wallet"));
    }

    @FXML
    public void send(@Nullable ActionEvent event) {
        // Address exception cannot happen as we validated it beforehand.
        try {
            Address destination = new Address(Main.params, address.getText());
            Wallet.SendRequest req = Wallet.SendRequest.emptyWallet(destination);
            req.aesKey = aesKey;
            sendResult = Main.bitcoin.wallet().sendCoins(req);
            Futures.addCallback(sendResult.broadcastComplete, new FutureCallback<Transaction>() {
                @Override
                public void onSuccess(Transaction result) {
                    checkGuiThread();
                    overlayUI.done();
                }

                @Override
                public void onFailure(Throwable t) {
                    // We died trying to empty the wallet.
                    CrashWindow.open(t);
                }
            });
            sendResult.tx.getConfidence().addEventListener((tx, reason) -> {
                if (reason == TransactionConfidence.Listener.ChangeReason.SEEN_PEERS)
                    updateTitleForBroadcast();
            });
            sendBtn.setDisable(true);
            address.setDisable(true);
            updateTitleForBroadcast();
        } catch (InsufficientMoneyException e) {
            informationalAlert(tr("Could not empty the wallet"),
                    tr("You may have too little money left in the wallet to make a transaction."));
            overlayUI.done();
        } catch (ECKey.KeyIsEncryptedException e) {
            askForPasswordAndRetry();
        } catch (AddressFormatException e) {
            // Cannot happen because we already validated it when the text field changed.
            throw new RuntimeException(e);
        }
    }

    private void askForPasswordAndRetry() {
        final String addressStr = address.getText();
        WalletPasswordController.requestPasswordWithNextWindow(key -> {
            Main.OverlayUI<EmptyWalletController> screen = open();
            screen.controller.aesKey = key;
            screen.controller.address.setText(addressStr);
            screen.controller.send(null);
        });
    }

    private void updateTitleForBroadcast() {
        final int peers = sendResult.tx.getConfidence().numBroadcastPeers();
        // TRANS: %d = number of peers
        titleLabel.setText(String.format(tr("Broadcasting ... seen by %d peers"), peers));
    }
}
