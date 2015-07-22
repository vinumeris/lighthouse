package lighthouse.protocol;

import org.bitcoinj.core.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * A UTXOSource that overlays a real source, but queues up queries until told it can proceed and then queries all of
 * them at once.
 */
public class BatchingUTXOSource implements UTXOSource, Runnable {
    private int totalOutpoints = 0;
    private static class Element {
        private List<TransactionOutPoint> queued = new ArrayList<>();
        private CompletableFuture<List<TransactionOutput>> future = new CompletableFuture<>();
    }
    private List<Element> elements = new ArrayList<>();
    private PeerUTXOMultiplexor multiplexor;

    public BatchingUTXOSource(PeerUTXOMultiplexor multiplexor) {
        this.multiplexor = multiplexor;
    }

    @Override
    public CompletableFuture<List<TransactionOutput>> getUTXOs(List<TransactionOutPoint> outPoints) {
        Element element = new Element();
        element.queued.addAll(outPoints);
        totalOutpoints += outPoints.size();
        elements.add(element);
        return element.future;
    }

    @Override
    public void run() {
        List<TransactionOutPoint> all = new ArrayList<>(totalOutpoints);
        for (Element element : elements) all.addAll(element.queued);
        if (all.isEmpty()) return;
        multiplexor.query(all).handle((result, ex) -> {
            if (ex != null) {
                for (Element element : elements) {
                    element.future.completeExceptionally(ex);
                }
            } else {
                ListIterator<Element> iterator = elements.listIterator();
                // Walk through each element in the hitmap, matching to the queried elements.
                int cursor = 0;
                for (int i = 0; i < all.size();) {
                    Element element = iterator.next();
                    List<TransactionOutput> results = new ArrayList<>();
                    for (int j = 0; j < element.queued.size(); j++) {
                        boolean hit = Utils.checkBitLE(result.getHitMap(), i++);
                        if (hit)
                            results.add(result.getOutputs().get(cursor++));
                    }
                    element.future.complete(results);
                }
            }
            return null;
        });
    }
}
