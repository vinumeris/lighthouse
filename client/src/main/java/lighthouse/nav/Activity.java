package lighthouse.nav;

/**
 * An activity represents a screen or page in the app, which can be dismissed by clicking the global back button.
 * It's somewhat analogus to a web page or an Android activity if you've ever programmed that platform.
 *
 * Activities are supposed to subclass JavaFX nodes and be insertable into the scene graph. They have life cycles.
 * Once constructed, the onStart() method is called when the user navigates into that activity, and onStop() is called
 * when the activity is removed from the screen.
 *
 * This interface is pretty basic right now and will evolve over time.
 */
public interface Activity {
    /** Called when the activity is about to be animated onto the screen. */
    void onStart();
    /** Called when the activity is about to be animated off the screen. */
    void onStop();
}
