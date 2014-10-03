package lighthouse.controls;

import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.binding.NumberBinding;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableObjectValue;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import lighthouse.LighthouseBackend;
import lighthouse.Main;
import lighthouse.protocol.Project;
import lighthouse.subwindows.ExportWindow;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.ReactiveCoinFormatter;
import org.bitcoinj.utils.MonetaryFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import static javafx.beans.binding.Bindings.*;
import static lighthouse.protocol.LHUtils.uncheck;
import static lighthouse.utils.GuiUtils.animatedBind;

/** An entry in the project list that is shown on the overview page */
public class ProjectOverviewWidget extends HBox {
    private static final Logger log = LoggerFactory.getLogger(ProjectOverviewWidget.class);

    @FXML Label titleLabel;
    @FXML TextFlow blurbFlow;
    @FXML Label ownershipIcon;
    @FXML HBox titleHBox;
    @FXML Circle progressCircle;
    @FXML Line progressLine;
    @FXML ImageView coverImage;
    @FXML Node loadingIndicatorArea;
    @FXML ProgressIndicator loadingIndicator;

    private Project project;
    private final SimpleBooleanProperty isLoading;

    public ProjectOverviewWidget(Project project, LongProperty pledgedAmount,
                                 ObservableObjectValue<LighthouseBackend.ProjectState> state) {
        this.project = project;

        FXMLLoader loader = new FXMLLoader(GuiUtils.getResource("controls/project_overview_widget.fxml"));
        loader.setRoot(this);
        loader.setController(this);
        uncheck(loader::load);

        titleLabel.setText(project.getTitle());
        Text text = new Text(project.getMemo());
        blurbFlow.getChildren().setAll(text);

        if (Main.wallet.maybeGetTag(project.ownedTag()) != null) {
            AwesomeDude.setIcon(ownershipIcon, AwesomeIcon.HOME, "25");
        } else {
            titleHBox.getChildren().remove(ownershipIcon);
        }

        isLoading = new SimpleBooleanProperty();

        // Make the cover image go grey when claimed and blurred when loading. Make a loading indicator fade in/out.
        final Image image = new Image(project.getCoverImage().newInput());
        ColorAdjust colorAdjust = new ColorAdjust();
        colorAdjust.saturationProperty().bind(when(equal(state, LighthouseBackend.ProjectState.CLAIMED)).then(-0.9).otherwise(0.0));
        GaussianBlur blur = new GaussianBlur();
        blur.setInput(colorAdjust);
        animatedBind(coverImage, blur.radiusProperty(), when(isLoading).then(10).otherwise(0.0));
        coverImage.setImage(image);
        coverImage.setEffect(blur);

        animatedBind(loadingIndicatorArea, loadingIndicatorArea.opacityProperty(), when(isLoading).then(1.0).otherwise(0.0));
        // Hack around a bug in jfx: progress indicator leaks the spinner animation even if it's invisible so we have
        // to forcibly end the animation here to avoid burning cpu.
        loadingIndicator.progressProperty().bind(
                when(loadingIndicatorArea.opacityProperty().greaterThan(0.0)).then(-1).otherwise(0)
        );

        // Make the progress line+circle follow the pledged amount and disappear if there are no pledges yet.
        //
        // This is all calculated lazily, so changes in pledgedProperty propagate through to the widgets only
        // when they actually need to be drawn.
        DoubleBinding progress = min(1.0, divide(pledgedAmount, (double) project.getGoalAmount().value));
        NumberBinding pixelWidth = multiply(widthProperty(), progress);
        // These come pre-bound in the FXML just to make things look more clear in Scene Builder, so unbind them here.
        progressLine.endXProperty().unbind();
        progressCircle.centerXProperty().unbind();
        animatedBind(progressLine, progressLine.endXProperty(), pixelWidth);
        animatedBind(progressCircle, progressCircle.centerXProperty(), pixelWidth);

        progressLine.visibleProperty().bind(pixelWidth.greaterThan(0.0));
        progressCircle.visibleProperty().bind(progressLine.visibleProperty());
        Tooltip tooltip = new Tooltip();
        // TODO: Maybe use Adam's BtcFormat class here instead.
        tooltip.textProperty().bind(new ReactiveCoinFormatter("%s BTC raised so far", MonetaryFormat.BTC, pledgedAmount));
        Tooltip.install(progressCircle, tooltip);
    }

    public void onCheckStatusChanged(@Nullable LighthouseBackend.CheckStatus checkStatus) {
        isLoading.set(checkStatus != null && checkStatus.inProgress);
    }

    private ExportWindow.DragData dragData;

    public void dragDetected(MouseEvent event) {
        dragData = ExportWindow.startDrag(project.getSuggestedFileName(), project.getProto(),
                ExportWindow.PROJECT_MIME_TYPE, this);
    }

    public void dragDone(DragEvent event) {
        log.info("Drag of project done");
        dragData.done();
    }
}
