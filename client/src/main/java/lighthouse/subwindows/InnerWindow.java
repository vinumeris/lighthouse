package lighthouse.subwindows;

import lighthouse.Main;
import javafx.animation.*;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.Arrays;
import java.util.List;

import static javafx.util.Duration.millis;

/**
 * A class that manages an FXML based on the window template (with titletabs).
 */
public class InnerWindow {
    public Main.OverlayUI<InnerWindow> overlayUI;

    public static class TabSection {
        public Label label;
        public Pane content;

        public TabSection(String label, Pane content) {
            this.label = new Label(label);
            this.label.getStyleClass().add(INACTIVE_STYLE);
            this.content = content;
        }
    }

    @FXML public BorderPane rootPane;
    @FXML public HBox tabLabelBox;
    @FXML public Label closeWidget;

    private List<TabSection> tabs;
    protected IntegerProperty currentTabIndex = new SimpleIntegerProperty(-1);
    private static final String INACTIVE_STYLE = "windows-titlebar-tab-inactive";

    public void initialize(TabSection... tabs) {
        // Make the window fit into the main window and stretch.
        rootPane.prefWidthProperty().bind(Main.instance.scene.widthProperty().multiply(0.8));
        rootPane.prefHeightProperty().bind(Main.instance.scene.heightProperty().multiply(0.6));

        this.tabs = Arrays.asList(tabs);

        for (int i = 0; i < this.tabs.size(); i++) {
            final int index = i;
            final Label label = this.tabs.get(index).label;
            label.setOnMouseClicked(ev -> currentTabIndex.set(index));
            tabLabelBox.getChildren().add(0, label);
        }

        currentTabIndex.addListener((val, before, now) -> {
            final TabSection prevSection = this.tabs.get(before.intValue() == -1 ? 0 : before.intValue());
            final TabSection nextSection = this.tabs.get(now.intValue());
            Label prevLabel = prevSection.label;
            Label nextLabel = nextSection.label;
            // It'd be nice to find a way of eliminating this duplication with the CSS file, but during initialisation
            // it seems styles were not applied yet. And JFX doesn't support CSS animations yet.
            Color inactiveColor = Color.web("#ff8888");
            Color activeColor = Color.RED;

            // Don't animate the tab transition the first time we appear.
            if (before.intValue() != -1) {
                final Animation colorAnim = new Transition() {
                    {
                        setCycleDuration(millis(400));
                    }

                    @Override
                    protected void interpolate(double v) {
                        Color color = inactiveColor.interpolate(activeColor, v);
                        nextLabel.setBackground(new Background(new BackgroundFill(color, null, null)));
                    }
                };
                colorAnim.play();

                TranslateTransition slide = new TranslateTransition(millis(200));
                slide.setFromX(0.0);
                slide.setByX(-50);
                FadeTransition fade = new FadeTransition(millis(200));
                fade.setFromValue(1.0);
                fade.setToValue(0.0);

                prevSection.content.setCache(true);
                ParallelTransition together = new ParallelTransition(prevSection.content, slide, fade);
                together.play();
            } else {
                nextLabel.setBackground(new Background(new BackgroundFill(activeColor, null, null)));
            }

            prevLabel.getStyleClass().add(INACTIVE_STYLE);
            nextLabel.getStyleClass().removeAll(INACTIVE_STYLE);
        });
        currentTabIndex.set(0);
    }

    public void cancelClicked(ActionEvent event) {
        overlayUI.done();
    }

    public void closeWidgetClicked(MouseEvent event) {
        overlayUI.done();
    }
}
