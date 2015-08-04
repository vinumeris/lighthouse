package lighthouse.controls;

import de.jensd.fx.fontawesome.*;
import javafx.application.*;
import javafx.beans.binding.*;
import javafx.beans.property.*;
import javafx.beans.value.*;
import javafx.fxml.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.effect.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import lighthouse.*;
import lighthouse.protocol.*;
import lighthouse.subwindows.*;
import lighthouse.utils.*;
import org.bitcoinj.utils.*;
import org.slf4j.*;

import javax.annotation.*;

import static javafx.beans.binding.Bindings.*;
import static lighthouse.protocol.LHUtils.*;
import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil.*;

/** An entry in the project list that is shown on the overview page */
public class ProjectOverviewWidget extends HBox {
    private static final Logger log = LoggerFactory.getLogger(ProjectOverviewWidget.class);

    @FXML Label titleLabel;
    @FXML MarkDownNode blurbFlow;
    @FXML Label ownershipIcon;
    @FXML HBox titleHBox;
    @FXML Circle progressCircle;
    @FXML Line progressLine;
    @FXML ImageView coverImage;
    @FXML Node loadingIndicatorArea;
    @FXML ProgressIndicator loadingIndicator;

    private Project project;
    private final SimpleBooleanProperty isLoading = new SimpleBooleanProperty();

    public ProjectOverviewWidget(Project project, LongProperty pledgedAmount,
                                 ObservableObjectValue<LighthouseBackend.ProjectState> state) {
        this.project = project;

        FXMLLoader loader = new FXMLLoader(GuiUtils.getResource("controls/project_overview_widget.fxml"), I18nUtil.translations);
        loader.setRoot(this);
        loader.setController(this);
        uncheck(loader::load);

        titleLabel.setText(project.getTitle());
        blurbFlow.setText(project.getMemo());
        blurbFlow.setUrlOpener(url -> Main.instance.getHostServices().showDocument(url));

        if (Main.wallet.isProjectMine(project)) {
            AwesomeDude.setIcon(ownershipIcon, AwesomeIcon.HOME, "25");
        } else {
            titleHBox.getChildren().remove(ownershipIcon);
        }

        // Make the cover image go grey when claimed and blurred when loading. Make a loading indicator fade in/out.
        final Image image = new Image(project.getCoverImage().newInput(), Project.COVER_IMAGE_WIDTH, Project.COVER_IMAGE_HEIGHT, false, true);
        ColorAdjust colorAdjust = new ColorAdjust();
        colorAdjust.saturationProperty().bind(
                when(
                        or(
                                equal(state, LighthouseBackend.ProjectState.CLAIMED),
                                equal(state, LighthouseBackend.ProjectState.UNKNOWN)
                        )
                ).then(-0.9).otherwise(0.0)
        );
        if (GuiUtils.isSoftwarePipeline()) {
            // SW pipeline cannot handle gaussian blurs with acceptable performance.
            coverImage.setEffect(colorAdjust);
        } else {
            GaussianBlur blur = new GaussianBlur();
            blur.setInput(colorAdjust);
            animatedBind(coverImage, blur.radiusProperty(), when(isLoading).then(25).otherwise(0.0));
            coverImage.setEffect(blur);
        }
        coverImage.setImage(image);
        coverImage.setClip(new Rectangle(coverImage.getFitWidth(), coverImage.getFitHeight()));

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

        // Creating a tooltip is only possible on the FX thread, annoyingly enough.
        Platform.runLater(() -> {
            Tooltip tooltip = new Tooltip();
            // TODO: Maybe use Adam's BtcFormat class here instead.
            // TRANS: %s = amount in BTC
            tooltip.textProperty().bind(new ReactiveCoinFormatter(tr("%s BTC raised so far"), MonetaryFormat.BTC, pledgedAmount));
            Tooltip.install(progressCircle, tooltip);

            Tooltip tt = new Tooltip(tr("You created this project"));
            tt.getStyleClass().add("default-font");
            ownershipIcon.setTooltip(tt);
        });
    }

    public void onCheckStatusChanged(@Nullable LighthouseBackend.CheckStatus checkStatus) {
        isLoading.set(checkStatus != null && checkStatus.getInProgress());
    }

    private ExportWindow.DragData dragData;

    @FXML
    public void dragDetected(MouseEvent event) {
        dragData = ExportWindow.startDrag(project.getSuggestedFileName(), project.getProto(),
                ExportWindow.PROJECT_MIME_TYPE, this);
    }

    @FXML
    public void dragDone(DragEvent event) {
        log.info("Drag of project done");
        dragData.done();
    }
}
