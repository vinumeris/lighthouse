package lighthouse.subwindows;

import com.google.bitcoin.core.InsufficientMoneyException;
import com.google.bitcoin.core.Transaction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import lighthouse.Main;
import lighthouse.protocol.LHProtos;
import lighthouse.utils.GuiUtils;
import lighthouse.wallet.PledgingWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;

import static lighthouse.utils.GuiUtils.checkGuiThread;
import static lighthouse.utils.GuiUtils.informationalAlert;

/**
 * Tells the user there's a fee to pay and shows a progress bar that tracks network propagation.
 */
public class PledgeRevokeWindow {
    private static final Logger log = LoggerFactory.getLogger(PledgeRevokeWindow.class);

    public LHProtos.Pledge pledgeToRevoke;
    public Runnable onSuccess;
    public Main.OverlayUI overlayUI;
    public Button cancelBtn;
    public Button confirmBtn;

    @FXML ProgressBar revokeProgress;

    public void confirmClicked(ActionEvent event) {
        // runLater: shitty hack around RT-37821 (consider upgrading to 8u40 when available and/or applying fix locally)
        // otherwise pressing enter can cause a crash here when we open a new window with a default button
        Platform.runLater(this::confirmClicked);
    }

    private void confirmClicked() {
        if (Main.wallet.isEncrypted()) {
            log.info("Wallet is encrypted, requesting password");
            Main.OverlayUI<WalletPasswordController> pwd = Main.instance.overlayUI("subwindows/wallet_password.fxml", "Password");
            pwd.controller.aesKeyProperty().addListener((observable, old, cur) -> {
                // We only get here if the user found the right password. If they don't or they cancel, we end up back on
                // the main UI screen.
                checkGuiThread();
                Main.OverlayUI<PledgeRevokeWindow> screen = Main.instance.overlayUI("subwindows/pledge_revoke.fxml", "Revoke pledge");
                screen.controller.pledgeToRevoke = pledgeToRevoke;
                screen.controller.onSuccess = onSuccess;
                screen.controller.doRevoke(cur);
            });
        } else {
            doRevoke(null);
        }
    }

    private void doRevoke(@Nullable KeyParameter aesKey) {
        try {
            confirmBtn.setDisable(true);
            cancelBtn.setDisable(true);
            PledgingWallet.Revocation revocation = Main.wallet.revokePledge(pledgeToRevoke, aesKey);
            revocation.tx.getConfidence().addEventListener((tx, reason) -> {
                revokeProgress.setProgress(tx.getConfidence().numBroadcastPeers() / (double) Main.bitcoin.peerGroup().getMinBroadcastConnections());
            }, Platform::runLater);
            Futures.addCallback(revocation.broadcastFuture, new FutureCallback<Transaction>() {
                @Override
                public void onSuccess(@Nullable Transaction result) {
                    onSuccess.run();
                    overlayUI.done();
                }

                @Override
                public void onFailure(Throwable t) {
                    GuiUtils.crashAlert(t);
                    overlayUI.done();
                }
            }, Platform::runLater);
        } catch (InsufficientMoneyException e) {
            // This really sucks. In future we should make it a free tx, when we know if we have sufficient
            // priority to meet the relay rules.
            informationalAlert("Cannot revoke pledge",
                    "Revoking a pledge requires making another Bitcoin transaction on the block chain, but " +
                            "you don't have sufficient funds to pay the required fee. Add more money and try again."
            );
        }
    }

    public void cancelClicked(ActionEvent event) {
        overlayUI.done();
    }
}
