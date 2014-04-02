package lighthouse.threading;

import javafx.beans.WeakListener;
import javafx.collections.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;

/**
 * Utility functions that mirror changes from one list into another list. JavaFX already provides this functionality
 * of course under the name "content binding"; a mirror is a content binding that relays changes into other threads
 * first. Thus you can have an ObservableList which is updated in one thread, but still bound to directly in the UI
 * thread, without needing to worry about cross-thread interference.
 */
public class ObservableMirrors {
    private static final Logger log = LoggerFactory.getLogger(ObservableMirrors.class);

    /**
     * Creates an unmodifiable list that asynchronously follows changes in mirrored, with changes applied using
     * the given executor. This should only be called on the thread that owns the list to be mirrored, as the contents
     * will be read.
     */
    public static <T> ObservableList<T> mirrorList(ObservableList<T> mirrored, AffinityExecutor runChangesIn) {
        ObservableList<T> result = FXCollections.observableArrayList();
        result.setAll(mirrored);
        mirrored.addListener(new ListMirror<T>(result, runChangesIn));
        return FXCollections.unmodifiableObservableList(result);
    }

    private static class ListMirror<E> implements ListChangeListener<E>, WeakListener {
        private final WeakReference<ObservableList<E>> targetList;
        private final AffinityExecutor runChangesIn;

        public ListMirror(ObservableList<E> list, AffinityExecutor runChangesIn) {
            this.targetList = new WeakReference<>(list);
            this.runChangesIn = runChangesIn;
        }

        @Override
        public void onChanged(Change<? extends E> change) {
            final List<E> list = targetList.get();
            if (list == null) {
                change.getList().removeListener(this);
            } else {
                // If we're already in the right thread this will just run the change immediately, as per normal.
                // Change objects are not thread safe. They may be reused by listeners following this one. However,
                // we cheat here and exploit knowledge of the implementation: a change is basically immutable and
                // self contained except for the iteration state. So we synchronize on the change and reset it at the
                // start to ensure we can iterate over it safely. Note that set changes actually are immutable and
                // so don't need this.
                runChangesIn.executeASAP(() -> {
                    synchronized (change) {
                        change.reset();
                        while (change.next()) {
                            if (change.wasPermutated()) {
                                list.subList(change.getFrom(), change.getTo()).clear();
                                list.addAll(change.getFrom(), change.getList().subList(change.getFrom(), change.getTo()));
                            } else {
                                if (change.wasRemoved()) {
                                    list.subList(change.getFrom(), change.getFrom() + change.getRemovedSize()).clear();
                                }
                                if (change.wasAdded()) {
                                    list.addAll(change.getFrom(), change.getAddedSubList());
                                }
                            }
                        }
                    }
                });
            }
        }

        @Override
        public boolean wasGarbageCollected() {
            return targetList.get() == null;
        }

        // Do we really need these?
        @Override
        public int hashCode() {
            final List<E> list = targetList.get();
            return (list == null)? 0 : list.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            final List<E> list1 = targetList.get();
            if (list1 == null) {
                return false;
            }

            if (obj instanceof ListMirror) {
                final ListMirror<?> other = (ListMirror<?>) obj;
                final List<?> list2 = other.targetList.get();
                return list1 == list2;
            }
            return false;
        }
    }

    public static <K, V> ObservableMap<K, V> mirrorMap(ObservableMap<K, V> mirrored, AffinityExecutor runChangesIn) {
        ObservableMap<K, V> result = FXCollections.observableHashMap();
        result.putAll(mirrored);
        mirrored.addListener(new MapMirror<K, V>(result, runChangesIn));
        return result;
    }

    private static class MapMirror<K, V> implements MapChangeListener<K, V>, WeakListener {
        private final WeakReference<ObservableMap<K, V>> targetMap;
        private final AffinityExecutor runChangesIn;

        public MapMirror(ObservableMap<K, V> targetMap, AffinityExecutor runChangesIn) {
            this.targetMap = new WeakReference<>(targetMap);
            this.runChangesIn = runChangesIn;
        }

        @Override
        public boolean wasGarbageCollected() {
            return targetMap.get() == null;
        }

        @Override
        public void onChanged(Change<? extends K, ? extends V> change) {
            final ObservableMap<K, V> map = targetMap.get();
            if (map == null) {
                change.getMap().removeListener(this);
            } else {
                runChangesIn.executeASAP(() -> {
                    if (change.wasAdded()) {
                        map.put(change.getKey(), change.getValueAdded());
                    } else if (change.wasRemoved()) {
                        map.remove(change.getKey());
                    }
                });
            }
        }
    }


    /**
     * Creates an unmodifiable list that asynchronously follows changes in mirrored, with changes applied using
     * the given executor. This should only be called on the thread that owns the list to be mirrored, as the contents
     * will be read.
     */
    public static <T> ObservableSet<T> mirrorSet(ObservableSet<T> mirrored, AffinityExecutor runChangesIn) {
        @SuppressWarnings("unchecked") ObservableSet<T> result = FXCollections.observableSet();
        result.addAll(mirrored);
        mirrored.addListener(new SetMirror<T>(result, runChangesIn));
        return FXCollections.unmodifiableObservableSet(result);
    }

    private static class SetMirror<E> implements SetChangeListener<E>, WeakListener {
        private final WeakReference<ObservableSet<E>> targetSet;
        private final AffinityExecutor runChangesIn;

        public SetMirror(ObservableSet<E> set, AffinityExecutor runChangesIn) {
            this.targetSet = new WeakReference<>(set);
            this.runChangesIn = runChangesIn;
        }

        @Override
        public void onChanged(final Change<? extends E> change) {
            final ObservableSet<E> set = targetSet.get();
            if (set == null) {
                change.getSet().removeListener(this);
            } else {
                // If we're already in the right thread this will just run the change immediately, as per normal.
                runChangesIn.executeASAP(() -> {
                    if (change.wasAdded())
                        set.add(change.getElementAdded());
                    if (change.wasRemoved())
                        set.remove(change.getElementRemoved());
                });
            }
        }

        @Override
        public boolean wasGarbageCollected() {
            return targetSet.get() == null;
        }

        @Override
        public int hashCode() {
            final ObservableSet<E> set = targetSet.get();
            return (set == null)? 0 : set.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            final Set<E> set1 = targetSet.get();
            if (set1 == null) {
                return false;
            }

            if (obj instanceof SetMirror) {
                final SetMirror<?> other = (SetMirror<?>) obj;
                final Set<?> list2 = other.targetSet.get();
                return set1 == list2;
            }
            return false;
        }
    }
}
