package lighthouse.protocol;

import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface UTXOSource {
    public CompletableFuture<List<TransactionOutput>> getUTXOs(List<TransactionOutPoint> outPoints);
}
