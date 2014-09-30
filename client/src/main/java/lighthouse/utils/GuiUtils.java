package lighthouse.utils;

import org.bitcoinj.core.Coin;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Uninterruptibles;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.NumberBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.WritableDoubleValue;
import javafx.fxml.FXMLLoader;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import lighthouse.Main;
import lighthouse.protocol.LHUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkState;
import static lighthouse.protocol.LHUtils.unchecked;

public class GuiUtils {
    public static final Logger log = LoggerFactory.getLogger(GuiUtils.class);

    public static void runAlert(BiConsumer<Stage, AlertWindowController> setup) {
        try {
            // JavaFX doesn't actually have a standard alert template. Instead the Scene Builder app will create FXML
            // files for an alert window for you, and then you customise it as you see fit.
            Stage dialogStage = new Stage();
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            FXMLLoader loader = new FXMLLoader(GuiUtils.class.getResource("alert.fxml"));
            Pane pane = loader.load();
            AlertWindowController controller = loader.getController();
            setup.accept(dialogStage, controller);
            dialogStage.setScene(new Scene(pane));
            dialogStage.showAndWait();
        } catch (Throwable e) {
            // We crashed whilst trying to show the alert dialog. This can happen if we're being crashed by inbound
            // closures onto the event thread which will execute in the nested event loop. Just give up here: at the
            // moment we have no way to filter them out of the event queue.
            e.printStackTrace();
            Runtime.getRuntime().exit(1);
        }
    }

    public static void crashAlert(Throwable t) {
        Throwable rootCause = Throwables.getRootCause(t);
        log.error("CRASH!", rootCause);
        Runnable r = () -> {
            runAlert((stage, controller) -> controller.crashAlert(stage, rootCause.toString()));
            Platform.exit();
        };
        if (Platform.isFxApplicationThread())
            r.run();
        else
            Platform.runLater(r);
    }

    /** Show a GUI alert box for any unhandled exceptions that propagate out of this thread. */
    public static void handleCrashesOnThisThread() {
        Thread.currentThread().setUncaughtExceptionHandler((thread, exception) -> {
            Platform.runLater(() -> {
                Main.instance.mainStage.hide();
                GuiUtils.crashAlert(Throwables.getRootCause(exception));
            });
        });
    }

    public static void informationalAlert(String message, String details, Object... args) {
        String formattedDetails = String.format(details, args);
        Runnable r = () -> runAlert((stage, controller) -> controller.informational(stage, message, formattedDetails));
        if (Platform.isFxApplicationThread())
            r.run();
        else
            Platform.runLater(r);
    }

    public static final int UI_ANIMATION_TIME_MSEC = 300;
    public static final Duration UI_ANIMATION_TIME = Duration.millis(UI_ANIMATION_TIME_MSEC);

    public static Animation fadeIn(Node ui) {
        return fadeIn(ui, 0, 1.0);
    }

    public static Animation fadeIn(Node ui, int delayMillis, double targetValue) {
        ui.setCache(true);
        ui.setCacheHint(CacheHint.SPEED);
        FadeTransition ft = new FadeTransition(Duration.millis(UI_ANIMATION_TIME_MSEC), ui);
        ft.setFromValue(ui.getOpacity());
        ft.setToValue(targetValue);
        ft.setOnFinished(ev -> ui.setCache(false));
        ft.setDelay(Duration.millis(delayMillis));
        ft.play();
        return ft;
    }

    public static Animation fadeOut(Node ui) {
        ui.setCache(true);
        ui.setCacheHint(CacheHint.SPEED);
        FadeTransition ft = new FadeTransition(Duration.millis(UI_ANIMATION_TIME_MSEC), ui);
        ft.setFromValue(ui.getOpacity());
        ft.setToValue(0.0);
        ft.setOnFinished(ev -> ui.setCache(false));
        ft.play();
        return ft;
    }

    public static Animation fadeOutAndRemove(Pane parentPane, Node... nodes) {
        Animation animation = fadeOut(nodes[0]);
        animation.setOnFinished(actionEvent -> parentPane.getChildren().removeAll(nodes));
        for (int i = 1; i < nodes.length; i++) {
            fadeOut(nodes[i]);
        }
        return animation;
    }

    public static Animation fadeOutAndRemove(Duration duration, Pane parentPane, Node... nodes) {
        nodes[0].setCache(true);
        FadeTransition ft = new FadeTransition(duration, nodes[0]);
        ft.setFromValue(nodes[0].getOpacity());
        ft.setToValue(0.0);
        ft.setOnFinished(ev -> parentPane.getChildren().removeAll(nodes));
        ft.play();
        return ft;
    }

    public static void blurOut(Node node) {
        GaussianBlur blur = new GaussianBlur(0.0);
        node.setEffect(blur);
        Timeline timeline = new Timeline();
        KeyValue kv = new KeyValue(blur.radiusProperty(), 10.0);
        KeyFrame kf = new KeyFrame(UI_ANIMATION_TIME, kv);
        timeline.getKeyFrames().add(kf);
        timeline.play();
    }

    public static void blurIn(Node node, Duration duration) {
        GaussianBlur blur = (GaussianBlur) node.getEffect();
        if (blur == null) {
            Main.log.error("BUG: Attempted to cancel non-existent blur.");
            return;
        }
        Timeline timeline = new Timeline();
        KeyValue kv = new KeyValue(blur.radiusProperty(), 0.0);
        KeyFrame kf = new KeyFrame(duration, kv);
        timeline.getKeyFrames().add(kf);
        timeline.setOnFinished(actionEvent -> node.setEffect(null));
        timeline.play();
    }

    /*
    public static void blurOut(Node node) {
        BoxBlur blur = new BoxBlur();
        blur.setIterations(1);
        blur.setWidth(0.0);
        blur.setHeight(0.0);
        Timeline timeline = new Timeline();
        KeyFrame kf = new KeyFrame(Duration.millis(UI_ANIMATION_TIME_MSEC),
                new KeyValue(blur.widthProperty(), 8.0),
                new KeyValue(blur.heightProperty(), 8.0));
        timeline.getKeyFrames().add(kf);
        timeline.play();
        node.setEffect(blur);
    }

    public static void blurIn(Node node) {
        BoxBlur blur = (BoxBlur) node.getEffect();
        Timeline timeline = new Timeline();
        KeyFrame kf = new KeyFrame(Duration.millis(UI_ANIMATION_TIME_MSEC),
                new KeyValue(blur.widthProperty(), 0.0),
                new KeyValue(blur.heightProperty(), 0.0));
        timeline.getKeyFrames().add(kf);
        timeline.setOnFinished(actionEvent -> node.setEffect(null));
        timeline.play();
    }
    */

    public static ScaleTransition zoomIn(Node node) {
        return zoomIn(node, 0);
    }

    public static ScaleTransition zoomIn(Node node, int delayMillis) {
        return scaleFromTo(node, 0.95, 1.0, delayMillis);
    }

    public static ScaleTransition explodeOut(Node node) {
        return scaleFromTo(node, 1.0, 1.05, 0);
    }

    private static ScaleTransition scaleFromTo(Node node, double from, double to, int delayMillis) {
        //node.setCache(true);
        //node.setCacheHint(CacheHint.SPEED);
        ScaleTransition scale = new ScaleTransition(Duration.millis(UI_ANIMATION_TIME_MSEC), node);
        scale.setFromX(from);
        scale.setFromY(from);
        scale.setToX(to);
        scale.setToY(to);
        scale.setDelay(Duration.millis(delayMillis));
        //scale.setOnFinished(ev -> node.setCache(false));
        scale.play();
        return scale;
    }

    public static void dropShadowOn(Node node) {
        DropShadow dropShadow = node.getEffect() != null ? (DropShadow) node.getEffect() : new DropShadow(BlurType.THREE_PASS_BOX, Color.BLACK, 0.0, 0.0, 0, 0);
        node.setEffect(dropShadow);
        Timeline timeline = new Timeline();
        timeline.getKeyFrames().add(
                new KeyFrame(Duration.millis(UI_ANIMATION_TIME_MSEC / 3),
                        new KeyValue(dropShadow.radiusProperty(), 3.0))
        );
        timeline.play();
    }

    public static void dropShadowOff(Node node) {
        DropShadow dropShadow = (DropShadow) node.getEffect();
        Timeline timeline = new Timeline();
        timeline.getKeyFrames().add(
                new KeyFrame(Duration.millis(UI_ANIMATION_TIME_MSEC / 3),
                        new KeyValue(dropShadow.radiusProperty(), 0.0))
        );
        timeline.setOnFinished((ev) -> node.setEffect(null));
        timeline.play();
    }

    public static void brightnessAdjust(Node node, double adjustment) {
        node.setCache(true);
        node.setCacheHint(CacheHint.SPEED);
        ColorAdjust adjust = new ColorAdjust();
        adjust.setBrightness(0.0);
        node.setEffect(adjust);
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(UI_ANIMATION_TIME_MSEC * 0.7),
                new KeyValue(adjust.brightnessProperty(), adjustment)));
        timeline.play();
    }

    public static void brightnessUnadjust(Node node) {
        ColorAdjust effect = (ColorAdjust) node.getEffect();
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(UI_ANIMATION_TIME_MSEC * 0.7),
                new KeyValue(effect.brightnessProperty(), 0.0)));
        timeline.setOnFinished(ev -> node.setCache(false));
        timeline.play();
    }

    public static void checkGuiThread() {
        checkState(Platform.isFxApplicationThread());
    }

    public static BooleanBinding conjunction(List<BooleanProperty> list) {
        BooleanBinding accumulator = new SimpleBooleanProperty(true).and(list.get(0));
        for (int i = 1; i < list.size(); i++) {
            accumulator = accumulator.and(list.get(i));
        }
        return accumulator;
    }

    public static Path resourceOverrideDirectory;

    public static URL getResource(String name) {
        if (resourceOverrideDirectory != null)
            return unchecked(() -> new URL("file://" + resourceOverrideDirectory.resolve(name).toAbsolutePath()));
        else
            return Main.class.getResource(name);
    }

    public static Coin valueOrThrow(String str) throws NumberFormatException {
        long value = BitcoinValue.userInputToSatoshis(str);
        if (value > 0)
            return Coin.valueOf(value);
        throw new NumberFormatException();
    }

    public static void runOnGuiThreadAfter(long millis, Runnable runnable) {
        new Thread(() -> {
            Uninterruptibles.sleepUninterruptibly(millis, TimeUnit.MILLISECONDS);
            Platform.runLater(runnable);
        }).start();
    }

    public static void runAfterFrame(Runnable runnable) {
        AnimationTimer frameWaiter = new AnimationTimer() {
            private int frames;

            @Override
            public void handle(long l) {
                frames++;
                System.err.println("frame!");
                if (frames > 2) {
                    stop();
                    runnable.run();
                }
            }
        };
        frameWaiter.start();
    }

    public static void platformFiddleChooser(FileChooser chooser) {
        // Work around FileChooser bugs.
        if (LHUtils.isUnix()) {
            chooser.setInitialDirectory(new File(System.getProperty("user.home")));
        }
    }

    public static void platformFiddleChooser(DirectoryChooser chooser) {
        // Work around DirectoryChooser bugs.
        if (LHUtils.isUnix()) {
            chooser.setInitialDirectory(new File(System.getProperty("user.home")));
        }
    }

    public static void roundCorners(ImageView view, double amount) {
        // This should be easier to do just with CSS.
        Rectangle clipRect = new Rectangle(view.getFitWidth(), view.getFitHeight());
        clipRect.setArcWidth(amount);
        clipRect.setArcHeight(amount);
        view.setClip(clipRect);
    }

    private static class AnimatedBindInfo {
        @Nullable public Timeline timeline;
        public NumberBinding bindFrom;
    }

    public static AnimatedBindInfo animatedBind(Node node, WritableDoubleValue bindTo, NumberBinding bindFrom) {
        bindTo.set(bindFrom.doubleValue());   // Initialise.
        bindFrom.addListener((o, prev, cur) -> {
            AnimatedBindInfo info = (AnimatedBindInfo) node.getUserData();
            if (info.timeline != null)
                info.timeline.stop();
            info.timeline = new Timeline(new KeyFrame(UI_ANIMATION_TIME, new KeyValue(bindTo, cur)));
            info.timeline.setOnFinished(ev -> ((AnimatedBindInfo)node.getUserData()).timeline = null);
            info.timeline.play();
        });
        // We must pin bindFrom into the object graph, otherwise something like:
        //    animatedBind(node, node.opacityProperty(), when(a).then(1).otherwise(2))
        // will mysteriously stop working when the result of when() gets garbage collected and the listener with it.
        AnimatedBindInfo info = new AnimatedBindInfo();
        info.bindFrom = bindFrom;
        node.setUserData(info);
        return info;
    }
}
