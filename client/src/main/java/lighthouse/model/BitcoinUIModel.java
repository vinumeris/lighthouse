package lighthouse.model;


import javafx.application.*;
import javafx.beans.property.*;
import org.bitcoinj.core.*;
import org.bitcoinj.core.listeners.*;

import java.util.*;

/**
 * A class that exposes relevant bitcoin stuff as JavaFX bindable properties.
 */
public class BitcoinUIModel {
    private SimpleObjectProperty<Address> address = new SimpleObjectProperty<>();
    private SimpleObjectProperty<Coin> balance = new SimpleObjectProperty<>(Coin.ZERO);
    private SimpleDoubleProperty syncProgress = new SimpleDoubleProperty(-1);

    public void setWallet(Wallet wallet) {
        syncProgress.set(-1);
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onWalletChanged(Wallet wallet) {
                super.onWalletChanged(wallet);
                update(wallet);
            }
        }, Platform::runLater);
        update(wallet);
    }

    private void update(Wallet wallet) {
        balance.set(wallet.getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE));
        address.set(wallet.currentReceiveAddress());
    }

    private class ProgressBarUpdater extends DownloadProgressTracker {
        @Override
        protected void progress(double pct, int blocksSoFar, Date date) {
            super.progress(pct, blocksSoFar, date);
            Platform.runLater(() -> syncProgress.set(pct / 100.0));
        }

        @Override
        protected void doneDownload() {
            super.doneDownload();
            Platform.runLater(() -> syncProgress.set(1.0));
        }
    }

    public DownloadProgressTracker getDownloadListener() { return new ProgressBarUpdater(); }

    public ReadOnlyDoubleProperty syncProgressProperty() { return syncProgress; }

    public ReadOnlyObjectProperty<Address> addressProperty() {
        return address;
    }

    public ReadOnlyObjectProperty<Coin> balanceProperty() {
        return balance;
    }
}
