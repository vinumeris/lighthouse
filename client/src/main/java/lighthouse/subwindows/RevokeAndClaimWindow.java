package lighthouse.subwindows;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.vinumeris.crashfx.CrashWindow;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import lighthouse.Main;
import lighthouse.protocol.Ex;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.Project;
import lighthouse.wallet.PledgingWallet;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static lighthouse.utils.GuiUtils.informationalAlert;

/**
 * Tells the user there's a fee to pay and shows a progress bar that tracks network propagation. Possibly request the
 * users password first. This is used for both revocation and claiming the contract.
 */
public class RevokeAndClaimWindow {
    private static final Logger log = LoggerFactory.getLogger(RevokeAndClaimWindow.class);

    // Either this ...
    public LHProtos.Pledge pledgeToRevoke;
    // Or these ...
    public Project projectToClaim;
    public Set<LHProtos.Pledge> pledgesToClaim;
    // ... are set. pledgesToClaim is IGNORED in server assisted projects however because one of the pledges might have
    // been revoked whilst the user was sitting on the claim screen. So we always refetch the status and recheck just
    // before doing the claim.
    public Runnable onSuccess;
    public Main.OverlayUI overlayUI;
    public Button cancelBtn;
    public Button confirmBtn;
    public Label explanationLabel;

    @FXML ProgressBar progressBar;

    public static Main.OverlayUI<RevokeAndClaimWindow> openForRevoke(LHProtos.Pledge pledgeToRevoke) {
        Main.OverlayUI<RevokeAndClaimWindow> overlay = Main.instance.overlayUI("subwindows/revoke_and_claim.fxml", "Revoke pledge");
        overlay.controller.setForRevoke(pledgeToRevoke);
        return overlay;
    }

    public static Main.OverlayUI<RevokeAndClaimWindow> openForClaim(Project project, Set<LHProtos.Pledge> pledgesToClaim) {
        Main.OverlayUI<RevokeAndClaimWindow> overlay = Main.instance.overlayUI("subwindows/revoke_and_claim.fxml", "Claim pledges");
        overlay.controller.setForClaim(project, pledgesToClaim);
        return overlay;
    }

    private void setForClaim(Project project, Set<LHProtos.Pledge> claim) {
        projectToClaim = project;
        pledgesToClaim = claim;
        explanationLabel.setText("Claiming a project sends all the pledged money to the project's goal address. " + explanationLabel.getText());
    }

    private void setForRevoke(LHProtos.Pledge revoke) {
        pledgeToRevoke = revoke;
        explanationLabel.setText("Revoking a pledge returns the money to your wallet. " + explanationLabel.getText());
    }

    @FXML
    public void confirmClicked(ActionEvent event) {
        // runLater: shitty hack around RT-37821 (consider upgrading to 8u40 when available and/or applying fix locally)
        // otherwise pressing enter can cause a crash here when we open a new window with a default button
        Platform.runLater(this::confirmClicked);
    }

    private void confirmClicked() {
        if (Main.wallet.isEncrypted()) {
            log.info("Wallet is encrypted, requesting password");
            WalletPasswordController.requestPassword(key -> {
                Main.OverlayUI<RevokeAndClaimWindow> screen = Main.instance.overlayUI("subwindows/revoke_and_claim.fxml", "Revoke pledge");
                screen.controller.pledgeToRevoke = pledgeToRevoke;
                screen.controller.projectToClaim = projectToClaim;
                screen.controller.pledgesToClaim = pledgesToClaim;
                screen.controller.onSuccess = onSuccess;
                screen.controller.go(key);
            });
        } else {
            go(null);
        }
    }

    private void go(@Nullable KeyParameter aesKey) {
        confirmBtn.setDisable(true);
        cancelBtn.setDisable(true);
        if (pledgeToRevoke != null) {
            revoke(aesKey);
        } else {
            checkState(projectToClaim != null);
            claim(aesKey);
        }
    }

    private void claim(@Nullable KeyParameter key) {
        if (projectToClaim.getPaymentURL() != null) {
            claimServerAssisted(key);
        } else {
            log.info("Attempting to claim serverless project, proceeding to merge and broadcast");
            broadcastClaim(pledgesToClaim, key);
        }
    }

    private void claimServerAssisted(@Nullable KeyParameter key) {
        // Need to refresh here because we're polling the server and might be lagging behind the true state.
        // This is kind of a hack. Better solutions would be:
        //
        // 1) Check the pledges returned ourselves against p2p network using getutxo: lowers trust in the server
        // 2) Have server stream updates to client so we are never more than a second or two behind, instead of
        //    a block interval like today.
        log.info("Attempting to claim server assisted project, refreshing");
        progressBar.setProgress(-1);
        Main.backend.refreshProjectStatusFromServer(projectToClaim, key).handleAsync((status, ex) -> {
            // On backend thread.
            if (ex != null) {
                log.error("Unable to fetch project status", ex);
                informationalAlert("Unable to claim", "Could not fetch project status from server: %s", ex);
                overlayUI.done();
            } else {
                HashSet<LHProtos.Pledge> newPledges = new HashSet<>(status.getPledgesList());
                if (status.getValuePledgedSoFar() < projectToClaim.getGoalAmount().value) {
                    log.error("Refreshed project status indicates value has changed, is now {}", status.getValuePledgedSoFar());
                    informationalAlert("Unable to claim", "One or more pledges have been revoked whilst you were waiting.");
                    overlayUI.done();
                } else {
                    // Must use newPledges here because a pledge might have been revoked and replaced in the
                    // waiting interval.
                    broadcastClaim(newPledges, key);
                }
            }
            return null;
        }, Platform::runLater);
    }

    private void broadcastClaim(Set<LHProtos.Pledge> pledges, @Nullable KeyParameter key) {
        try {
            PledgingWallet.CompletionProgress progress = Main.wallet.completeContractWithFee(projectToClaim, pledges, key);
            double total = Main.bitcoin.peerGroup().getMinBroadcastConnections() * 2;  // two transactions.
            progress.peersSeen = seen -> {
                if (seen == -1) {
                    Platform.runLater(onSuccess::run);
                } else {
                    progressBar.setProgress(seen / total);
                }
            };
            progress.txFuture.handleAsync((t, ex) -> {
                if (ex != null) {
                    CrashWindow.open(ex);
                } else {
                    onSuccess.run();
                }
                overlayUI.done();
                return null;
            }, Platform::runLater);
        }  catch (Ex.ValueMismatch e) {
            // TODO: Solve value mismatch errors. We have a few options.
            // 1) Try taking away pledges to see if we can get precisely to the target value, e.g. this can
            //    help if everyone agrees up front to pledge 1 BTC exactly, and the goal is 10, but nobody
            //    knows how many people will pledge so we might end up with 11 or 12 BTC. In this situation
            //    we can just randomly drop pledges until we get to the right amount (or allow the user to choose).
            // 2) Find a way to extend the Bitcoin protocol so the additional money can be allocated to the
            //    project owner and not miners. For instance by allowing new SIGHASH modes that control which
            //    parts of which outputs are signed. This would require a Script 2.0 effort though.
            //
            // This should never happen in server assisted mode.
            log.error("Value mismatch: " + e);
            informationalAlert("Too much money",
                    "You have gathered pledges that add up to more than the goal. The excess cannot be " +
                            "redeemed in the current version of the software and would end up being paid completely " +
                            "to miners fees. Please remove some pledges and try to hit the goal amount exactly. " +
                            "There is %s too much.", Coin.valueOf(e.byAmount).toFriendlyString());
            overlayUI.done();
        } catch (InsufficientMoneyException e) {
            log.error("Insufficient money to claim", e);
            informationalAlert("Cannot claim pledges",
                    "Closing the contract requires paying Bitcoin network fees, but you don't have enough " +
                            "money in the wallet. Add more money and try again."
            );
            overlayUI.done();
        }
    }

    private void revoke(@Nullable KeyParameter aesKey) {
        try {
            PledgingWallet.Revocation revocation = Main.wallet.revokePledge(pledgeToRevoke, aesKey);
            revocation.tx.getConfidence().addEventListener((conf, reason) -> {
                progressBar.setProgress(conf.numBroadcastPeers() / (double) Main.bitcoin.peerGroup().getMinBroadcastConnections());
            }, Platform::runLater);
            Futures.addCallback(revocation.broadcastFuture, new FutureCallback<Transaction>() {
                @Override
                public void onSuccess(@Nullable Transaction result) {
                    onSuccess.run();
                    overlayUI.done();
                }

                @Override
                public void onFailure(Throwable t) {
                    CrashWindow.open(t);
                    overlayUI.done();
                }
            }, Platform::runLater);
        } catch (InsufficientMoneyException e) {
            // This really sucks. In future we should make it a free tx, when we know if we have sufficient
            // priority to meet the relay rules.
            log.error("Could not revoke due to insufficient money to pay the fee", e);
            informationalAlert("Cannot revoke pledge",
                    "Revoking a pledge requires making another Bitcoin transaction on the block chain, but " +
                            "you don't have sufficient funds to pay the required fee. Add more money and try again."
            );
        }
    }

    @FXML
    public void cancelClicked(ActionEvent event) {
        overlayUI.done();
    }
}
