package lighthouse.protocol;

import com.google.common.util.concurrent.*;
import org.bitcoinj.core.*;
import org.slf4j.*;

import javax.annotation.*;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.*;

/**
 * A UTXOSource that repeats the given query on all provided peers, waits for the response from all of them
 * to come in, and then verifies that they all match. If there's any inconsistency at all, or if any query fails,
 * the future completes exceptionally.
 */
public class PeerUTXOMultiplexor {
    private static final Logger log = LoggerFactory.getLogger(PeerUTXOMultiplexor.class);
    private List<Peer> peers;

    public PeerUTXOMultiplexor(List<Peer> peers) {
        checkArgument(!peers.isEmpty());
        this.peers = peers;
    }

    public CompletableFuture<UTXOsMessage> query(List<TransactionOutPoint> outPoints) {
        CompletableFuture<UTXOsMessage> result = new CompletableFuture<>();
        try {
            List<ListenableFuture<UTXOsMessage>> futures = new LinkedList<>();
            for (Peer peer: peers) {
                log.info("Sending UTXO query to {}", peer);
                futures.add(peer.getUTXOs(outPoints));
            }
            Futures.addCallback(Futures.allAsList(futures), new FutureCallback<List<UTXOsMessage>>() {
                @Override
                public void onSuccess(@Nullable List<UTXOsMessage> val) {
                    checkNotNull(val);
                    // Verify they are all the same.
                    UTXOsMessage template = val.get(0);
                    for (UTXOsMessage response : val) {
                        if (!response.equals(template)) {
                            log.error("Got inconsistent UTXO answers from peer: {}", response);
                            result.completeExceptionally(new Ex.InconsistentUTXOAnswers());
                            return;
                        }
                    }
                    result.complete(template);
                }

                @Override
                public void onFailure(@Nonnull Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }
}
