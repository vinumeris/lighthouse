package lighthouse.controls;


import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableDoubleValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lighthouse.protocol.LHUtils;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.easing.EasingMode;
import lighthouse.utils.easing.ElasticInterpolator;

import javax.annotation.Nullable;

/**
 * Wraps the given Node in a BorderPane and allows a thin bar to slide in from the bottom or top, squeezing the content
 * node. The API allows different "items" to be added/removed and they will be displayed one at a time, fading between
 * them when the topmost is removed. Each item is meant to be used for e.g. a background task and can contain a button
 * and/or a progress bar.
 */
public class NotificationBarPane extends BorderPane {
    public static final Duration ANIM_IN_DURATION = GuiUtils.UI_ANIMATION_TIME.multiply(2);
    public static final Duration ANIM_OUT_DURATION = GuiUtils.UI_ANIMATION_TIME;

    private VBox bar;
    private double barHeight;

    public class Item {
        public final StringProperty label;
        @Nullable public final ObservableDoubleValue progress;

        protected HBox entry;
        private Label labelControl;
        @Nullable private ProgressBar progressBar;

        public Item(String initialLabel, @Nullable ObservableDoubleValue progress, @Nullable Button button) {
            this.progress = progress;

            labelControl = new Label();
            labelControl.setText(initialLabel);
            labelControl.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(labelControl, Priority.ALWAYS);
            label = labelControl.textProperty();
            entry = new HBox(labelControl);
            if (progress != null) {
                progressBar = new ProgressBar();
                progressBar.setMinWidth(200);
                progressBar.progressProperty().bind(progress);
                entry.getChildren().add(progressBar);
            }
            if (button != null) {
                entry.getChildren().add(button);
            }
            entry.getStyleClass().add("notification-bar-item");
            entry.setFillHeight(true);
            entry.setAlignment(Pos.CENTER_LEFT);
        }

        public void cancel() {
            items.remove(this);
        }
    }

    public final ObservableList<Item> items;

    public NotificationBarPane(Node content) {
        super(content);

        // Just for sizing
        Item fakeItem = new Item("infobar!", null, new Button("foo"));
        bar = new VBox(fakeItem.entry);
        bar.setMinHeight(0.0);
        bar.getStyleClass().add("info-bar");
        bar.setFillWidth(true);
        setBottom(bar);
        // Figure out the height of the bar based on the CSS. Must wait until after we've been added to the parent node.
        sceneProperty().addListener(o -> {
            if (getParent() == null) return;
            getParent().applyCss();
            getParent().layout();
            barHeight = bar.getHeight();
            bar.setPrefHeight(0.0);
            bar.getChildren().remove(fakeItem.entry);
        });
        items = FXCollections.observableArrayList();
        items.addListener(this::processItemChange);
    }

    private void processItemChange(ListChangeListener.Change<? extends Item> change) {
        while (change.next()) {
            if (change.wasRemoved()) {
                bar.getChildren().removeAll(LHUtils.mapList(change.getRemoved(), item -> item.entry));
            }
            if (change.wasAdded()) {
                bar.getChildren().addAll(LHUtils.mapList(change.getAddedSubList(), item -> item.entry));
            }
        }
        showOrHide();
    }

    private void showOrHide() {
        if (items.isEmpty())
            animateOut();
        else
            animateIn();
    }

    public boolean isShowing() {
        return bar.getPrefHeight() > 0;
    }

    private void animateIn() {
        animate(barHeight * items.size());
    }

    private void animateOut() {
        animate(0.0);
    }

    private Timeline timeline;
    protected void animate(Number target) {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        Duration duration;
        Interpolator interpolator;
        if (target.intValue() > 0) {
            interpolator = new ElasticInterpolator(EasingMode.EASE_OUT, 1, 2);
            duration = ANIM_IN_DURATION;
        } else {
            interpolator = Interpolator.EASE_OUT;
            duration = ANIM_OUT_DURATION;
        }
        KeyFrame kf = new KeyFrame(duration, new KeyValue(bar.prefHeightProperty(), target, interpolator));
        timeline = new Timeline(kf);
        timeline.setOnFinished(x -> timeline = null);
        timeline.play();
    }

    public Item displayNewItem(String string) {
        Item item = createItem(string, null, null);
        items.add(item);
        return item;
    }

    public Item displayNewItem(String string, ObservableDoubleValue progress) {
        Item item = createItem(string, progress);
        items.add(item);
        return item;
    }

    public Item displayNewItem(String string, Button btn) {
        Item item = createItem(string, btn);
        items.add(item);
        return item;
    }

    public Item createItem(String string, @Nullable ObservableDoubleValue progress) {
        return new Item(string, progress, null);
    }

    public Item createItem(String string, @Nullable Button button) {
        return new Item(string, null, button);
    }

    public Item createItem(String string, @Nullable ObservableDoubleValue progress, @Nullable Button button) {
        return new Item(string, progress, button);
    }
}
