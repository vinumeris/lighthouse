package lighthouse.nav

import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.input.KeyCombination
import javafx.scene.layout.StackPane
import lighthouse.Main

/**
 * A NavManager keeps track of a browser-style navigation experience, with a history list that keeps track of where
 * you came from. It manages the transition between pages and other such things.
 */
public class NavManager(public val scrollPane: ScrollPane, val initial: Activity) {
    // Stores information needed to go back one step.
    inner class HistoryItem(val scroll: Double, val activity: Activity, val asNode: Node)
    private val history = linkedListOf<HistoryItem>()
    public var currentActivity: Activity = initial
        get
        private set
    private val stackPane = scrollPane.content as StackPane
    private val backShortcut = KeyCombination.valueOf("Shortcut+LEFT")

    init {
        stackPane.children.add(initial as Node)
    }

    public val isOnInitialActivity: SimpleBooleanProperty = SimpleBooleanProperty(true)

    public fun navigate(activity: Activity) {
        if (activity !is Node) throw AssertionError()
        check(!(activity === currentActivity))
        check(!(activity === initial))

        currentActivity.onStop()
        history.push(HistoryItem(scrollPane.vvalue, currentActivity, currentActivity as Node))
        with (stackPane.children) {
            // TODO: Animate this
            remove(currentActivity)
            add(activity)
        }
        scrollPane.vvalue = 0.0
        currentActivity = activity
        currentActivity.onStart()

        isOnInitialActivity.set(false)

        // TODO: Have to delay this to work around bug in pre 8u20 JFX. Once everyone is on 8u40 remove.
        Platform.runLater() {
            Main.instance.scene.accelerators[backShortcut] = Runnable { back() }
        }
    }

    public fun back() {
        val prev = history.pop()
        currentActivity.onStop()
        with (stackPane.children) {
            // TODO: Animate this
            remove(currentActivity)
            add(prev.asNode)
        }
        scrollPane.vvalue = prev.scroll
        currentActivity = prev.activity
        currentActivity.onStart()

        if (currentActivity == initial)
            isOnInitialActivity.set(true)

        // TODO: Have to delay this to work around bug in pre 8u20 JFX. Once everyone is on 8u40 remove.
        Platform.runLater() {
            Main.instance.scene.accelerators.remove(backShortcut)
        }
    }
}