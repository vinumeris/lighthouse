package lighthouse.protocol;

import org.bitcoinj.core.*;

import java.util.*;
import java.util.concurrent.*;

public interface UTXOSource {
    CompletableFuture<List<TransactionOutput>> getUTXOs(List<TransactionOutPoint> outPoints);
}
