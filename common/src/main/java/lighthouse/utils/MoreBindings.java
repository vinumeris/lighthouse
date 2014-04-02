package lighthouse.utils;

import com.sun.javafx.collections.ObservableSetWrapper;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;

import java.util.HashSet;

public class MoreBindings {
    public static <T> void bindSetToList(ObservableSet<T> set, ObservableList<T> list) {
        list.setAll(set);
        set.addListener((SetChangeListener<T>) change -> {
            if (change.wasAdded())
                list.add(change.getElementAdded());
            else if (change.wasRemoved())
                list.remove(change.getElementRemoved());
        });
    }

    @SuppressWarnings("unchecked")
    public static <T> ObservableSet<T> mergeSets(ObservableSet<T>... sets) {
        return new ObservableSetWrapper<T>(new HashSet<>()) {
            // GC pin in case the observable sets aren't pinned by anything else.
            private ObservableSet<T>[] backingSets = sets;

            {
                for (ObservableSet<T> set : backingSets) {
                    addAll(set);
                    set.addListener((SetChangeListener<T>) change -> {
                        if (change.wasAdded())
                            add(change.getElementAdded());
                        if (change.wasRemoved()) {
                            // Check if any other set still has this element, if so, don't remove.
                            boolean otherHas = false;
                            for (ObservableSet<T> set2 : backingSets) {
                                if (set2 == change.getSet()) continue;
                                if (set2.contains(change.getElementRemoved())) {
                                    otherHas = true;
                                    break;
                                }
                            }
                            if (!otherHas)
                                remove(change.getElementRemoved());
                        }
                    });
                }
            }
        };
    }
}
