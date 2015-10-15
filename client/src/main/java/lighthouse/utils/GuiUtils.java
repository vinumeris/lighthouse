package lighthouse.utils;

import com.google.common.util.concurrent.*;
import com.sun.prism.*;
import com.sun.prism.sw.*;
import com.vinumeris.crashfx.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.beans.binding.*;
import javafx.beans.property.*;
import javafx.beans.value.*;
import javafx.fxml.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.effect.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.*;
import javafx.stage.*;
import javafx.util.*;
import lighthouse.*;
import lighthouse.protocol.*;
import org.bitcoinj.core.*;
import org.controlsfx.control.*;
import org.slf4j.*;

import javax.annotation.*;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static lighthouse.protocol.LHUtils.*;

public class GuiUtils {
    public static final Logger log = LoggerFactory.getLogger(GuiUtils.class);

    public static void runAlert(BiConsumer<Stage, AlertWindowController> setup) {
        try {
            // JavaFX doesn't actually have a standard alert template. Instead the Scene Builder app will create FXML
            // files for an alert window for you, and then you customise it as you see fit.
            Stage dialogStage = new Stage();
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            FXMLLoader loader = new FXMLLoader(GuiUtils.class.getResource("alert.fxml"), I18nUtil.translations);
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

    public static void informationalAlert(String message, String details, Object... args) {
        String formattedDetails = String.format(details, args);
        Runnable r = () -> runAlert((stage, controller) -> controller.informational(stage, message, formattedDetails));
        if (Platform.isFxApplicationThread())
            r.run();
        else
            Platform.runLater(r);
    }

    public static final int UI_ANIMATION_TIME_MSEC = 500;
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
        if (!Platform.isFxApplicationThread()) {
            // Don't just throw directly here to avoid missing the problem when buggy code swallows the exceptions.
            IllegalStateException ex = new IllegalStateException();
            log.error("Threading violation: not on FX UI thread", ex);
            CrashWindow.open(ex);
            throw ex;
        }
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

    @Nullable
    public static Coin valueOrNull(String str) {
        try {
            return valueOrThrow(str);
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static HashMap<Node, Runnable> currentPops = new HashMap<>();

    public static CompletableFuture<Void> arrowBubbleToNode(Node target, String text) {
        checkGuiThread();
        // Make any bubble that's currently pointing to the same node finish up.
        if (currentPops.get(target) != null)
            currentPops.get(target).run();
        Label content = new Label(text);
        content.setStyle("-fx-font-size: 12; -fx-padding: 0 20 0 20");
        PopOver popover = new PopOver(content);
        popover.setDetachable(false);
        popover.setArrowLocation(PopOver.ArrowLocation.TOP_CENTER);
        popover.show(target);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Runnable finish = new Runnable() {
            private boolean ran = false;

            @Override
            public void run() {
                checkGuiThread();
                if (!ran) {
                    currentPops.remove(target);
                    ran = true;
                    popover.hide();
                    runOnGuiThreadAfter(200 /* from PopOver sources */, () -> {
                        if (!future.isDone()) future.complete(null);
                    });
                }
            }
        };
        currentPops.put(target, finish);
        // Make bubble disappear after 4 seconds.
        runOnGuiThreadAfter(4000, finish);
        // Make bubble disappear if the node it's pointing to disappears.
        target.sceneProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null)
                finish.run();
        });
        return future;
    }

    public static class AnimatedBindInfo {
        @Nullable public Timeline timeline;
        public NumberBinding bindFrom;
        public Runnable onAnimFinish;
    }

    public static AnimatedBindInfo animatedBind(Node node, WritableDoubleValue bindTo, NumberBinding bindFrom) {
        return animatedBind(node, bindTo, bindFrom, null);
    }

    public static AnimatedBindInfo animatedBind(Node node, WritableDoubleValue bindTo, NumberBinding bindFrom, @Nullable Interpolator interpolator) {
        bindTo.set(bindFrom.doubleValue());   // Initialise.
        bindFrom.addListener((o, prev, cur) -> {
            AnimatedBindInfo info = (AnimatedBindInfo) node.getUserData();
            if (info.timeline != null)
                info.timeline.stop();
            info.timeline = new Timeline(new KeyFrame(UI_ANIMATION_TIME,
                    interpolator != null ? new KeyValue(bindTo, cur, interpolator) : new KeyValue(bindTo, cur)));
            info.timeline.setOnFinished(ev -> {
                ((AnimatedBindInfo) node.getUserData()).timeline = null;
                if (info.onAnimFinish != null)
                    info.onAnimFinish.run();
            });
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

    public static boolean isSoftwarePipeline() {
        return GraphicsPipeline.getPipeline() instanceof SWPipeline;
    }
}
