package lighthouse.threading;

import com.google.common.collect.ImmutableList;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedList;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ObservableMirrorsTest {
    private AffinityExecutor.Gate gate;

    @Before
    public void setup() {
        gate = new AffinityExecutor.Gate(true);
    }

    @Test
    public void mirroredSet() throws Exception {
        ObservableSet<String> source = FXCollections.observableSet();
        source.add("alpha");
        source.add("beta");
        ObservableSet<String> dest = ObservableMirrors.mirrorSet(source, gate);
        assertEquals(0, gate.getTaskQueueSize());
        assertEquals(2, dest.size());
        source.add("delta");
        assertEquals(1, gate.getTaskQueueSize());
        assertEquals(2, dest.size());
        gate.waitAndRun();
        assertEquals(0, gate.getTaskQueueSize());
        assertEquals(3, dest.size());
        source.removeAll(ImmutableList.of("alpha", "beta"));
        assertEquals(2, gate.getTaskQueueSize());
        gate.waitAndRun();
        gate.waitAndRun();
        assertEquals(1, dest.size());
        assertTrue(dest.contains("delta"));
    }

    @Test
    public void observableList() throws Exception {
        ObservableList<String> source = FXCollections.observableArrayList();
        source.addAll("alpha", "beta");
        ObservableList<String> dest = ObservableMirrors.mirrorList(source, gate);
        Queue<ListChangeListener.Change<? extends String>> changes = new LinkedList<>();
        dest.addListener(changes::add);

        // Expect a single change with two added items.
        source.addAll("gamma", "delta");
        assertEquals(1, gate.getTaskQueueSize());
        gate.waitAndRun();
        ListChangeListener.Change<? extends String> change = changes.poll();
        change.next();
        assertTrue(change.wasAdded());
        assertEquals(2, change.getAddedSize());
        assertEquals(ImmutableList.of("gamma", "delta"), change.getAddedSubList());

        // Expect four queued changes with coherent/correct deltas unaffected by later changes to the src list.
        source.remove(3);   // remove delta
        source.add("phi");
        source.remove(3);   // remove phi
        source.add("epsilon");
        assertEquals(4, gate.getTaskQueueSize());
        gate.waitAndRun();
        gate.waitAndRun();
        change = changes.poll();
        change.next();
        assertTrue(change.wasRemoved());
        assertEquals(1, change.getRemovedSize());
        assertEquals("delta", change.getRemoved().get(0));
        change = changes.poll();
        change.next();
        assertTrue(change.wasAdded());
        assertEquals("phi", change.getAddedSubList().get(0));
    }
}
