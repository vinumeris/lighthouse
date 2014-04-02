package lighthouse.subwindows;

import de.jensd.fx.fontawesome.AwesomeIcon;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * A fake window with a big fat titlebar, a drop shadow around the edges, and an X close button in that titlebar.
 * Not draggable at the moment. Can't exceed the bounds of the containing window. In other words is worse than
 * a regular window in almost every possible way, except that it looks cooler and the same across platforms.
 */
public class EmbeddedWindow extends BorderPane {
    private final StackPane closeButton;

    public EmbeddedWindow(String title, Pane content) {
        super(wrapContent(content));

        getStyleClass().add("fat-buttons");
        getStyleClass().add("windows-root");
        setEffect(new DropShadow());

        Label label = new Label(title);
        label.getStyleClass().add("title");
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);

        Label x1 = new Label(AwesomeIcon.TIMES_CIRCLE.toString());
        x1.getStyleClass().add("awesome");
        x1.getStyleClass().add("windows-x");
        Circle circle = new Circle(10);
        circle.setFill(Color.WHITE);
        closeButton = new StackPane(circle, x1);

        HBox titlebar = new HBox(label, closeButton);
        titlebar.getStyleClass().add("titlebar");
        setTop(titlebar);

        sceneProperty().addListener(x -> {
            if (getParent() == null) return;
            getParent().applyCss();
            getParent().layout();
            maxWidthProperty().bind(content.prefWidthProperty());
            maxHeightProperty().bind(content.prefHeightProperty().add(titlebar.heightProperty()));
        });
    }

    private static Pane wrapContent(Pane content) {
        Pane pane = new Pane(content);
        pane.setStyle("-fx-background-color: white");
        return pane;
    }

    public void setOnCloseClicked(Runnable runnable) {
        closeButton.setOnMouseClicked(ev -> runnable.run());
    }
}
