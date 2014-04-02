package lighthouse.protocol;

import com.google.bitcoin.core.TransactionOutPoint;
import com.google.bitcoin.core.TransactionOutput;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface UTXOSource {
    public CompletableFuture<List<TransactionOutput>> getUTXOs(List<TransactionOutPoint> outPoints);
}
