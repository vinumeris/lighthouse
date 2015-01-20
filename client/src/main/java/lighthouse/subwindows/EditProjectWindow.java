package lighthouse.subwindows;

import com.google.common.io.*;
import com.google.protobuf.*;
import javafx.embed.swing.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.effect.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import lighthouse.*;
import lighthouse.files.*;
import lighthouse.model.*;
import lighthouse.protocol.*;
import lighthouse.utils.*;
import org.bitcoinj.core.*;
import org.controlsfx.control.*;
import org.slf4j.*;

import javax.imageio.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.*;

import static lighthouse.protocol.LHUtils.*;
import static lighthouse.utils.GuiUtils.*;

/**
 * A window that lets you create a new project or edit an existing one.
 */
public class EditProjectWindow {
    private static final Logger log = LoggerFactory.getLogger(EditProjectWindow.class);
    private static final String COVERPHOTO_SITE = "coverphotofinder.com";

    @FXML BorderPane rootPane;
    @FXML Label coverPhotoSiteLink;
    @FXML Label coverImageLabel;
    @FXML ImageView coverImageView;
    @FXML TextField addressEdit;
    @FXML TextField goalAmountEdit;
    @FXML TextField titleEdit;
    @FXML TextField minPledgeEdit;
    @FXML TextArea descriptionEdit;
    @FXML Button nextButton;
    @FXML Pane createPane;

    private PopOver maxPledgesPopOver;

    public Main.OverlayUI<InnerWindow> overlayUI;

    private ProjectModel model;
    private boolean editing;

    public static void openForCreate() {
        ProjectModel projectModel = new ProjectModel(Main.wallet);
        projectModel.serverName.set("vinumeris.com");  // By default.
        open(projectModel, "Create new project", false);
    }

    public static void openForCreate(ProjectModel project) {
        open(project, "Create new project", false);
    }

    public static void openForEdit(Project project) {
        open(new ProjectModel(project.getProtoDetails().toBuilder()), "Edit project", true);
    }

    public static void openForEdit(ProjectModel project) {
        open(project, "Edit project", true);
    }

    private static void open(ProjectModel project, String title, boolean editing) {
        Main.OverlayUI<EditProjectWindow> ui = Main.instance.overlayUI("subwindows/add_edit_project.fxml", title);
        ui.controller.setupFor(project, editing);
    }

    private void setupFor(ProjectModel model, boolean editing) {
        this.model = model;
        this.editing = editing;

        // Copy data from model.
        addressEdit.setText(model.address.get());
        titleEdit.setText(model.title.get());
        descriptionEdit.setText(model.memo.get());
        Coin goalCoin = Coin.valueOf(model.goalAmount.get());
        if (goalCoin.value != 1) {  // 1 satoshi is sentinel value meaning new project.
            goalAmountEdit.setText(goalCoin.toPlainString());
        }
        if (editing)
            minPledgeEdit.setText(model.getMinPledgeAmount().toPlainString());
        else
            minPledgeEdit.setPromptText(model.getMinPledgeAmount().toPlainString());
        if (model.image.get() == null) {
            setupDefaultCoverImage();
        } else {
            InputStream stream = model.image.get().newInput();
            coverImageView.setImage(new Image(stream));
            uncheck(stream::close);
        }

        // Bind UI back to model.
        this.model.title.bind(titleEdit.textProperty());
        this.model.memo.bind(descriptionEdit.textProperty());

        coverPhotoSiteLink.setText(COVERPHOTO_SITE);

        ValidationLink goalValid = new ValidationLink(goalAmountEdit, str -> !LHUtils.didThrow(() -> valueOrThrow(str)));
        goalAmountEdit.textProperty().addListener((obj, prev, cur) -> {
            if (goalValid.isValid.get())
                this.model.goalAmount.set(valueOrThrow(cur).value);
        });
        // Figure out the smallest pledge that is allowed based on the goal divided by number of inputs we can have.
        model.minPledgeAmountProperty().addListener(o -> {
            minPledgeEdit.setPromptText(model.getMinPledgeAmount().toPlainString());
        });
        ValidationLink minPledgeValue = new ValidationLink(minPledgeEdit, str -> {
            if (str.isEmpty())
                return true;  // default is used
            Coin coin = valueOrNull(str);
            if (coin == null) return false;
            Coin amount = model.getMinPledgeAmount();
            // If min pledge == suggested amount it's ok, or if it's between min amount and goal.
            return coin.equals(amount) || (coin.isGreaterThan(amount) && coin.isLessThan(Coin.valueOf(this.model.goalAmount.get())));
        });
        minPledgeEdit.textProperty().addListener((obj, prev, cur) -> {
            if (minPledgeValue.isValid.get()) {
                if (cur.trim().equals(""))
                    model.resetMinPledgeAmount();
                else
                    model.setMinPledgeAmount(valueOrThrow(cur));
            }
        });
        ValidationLink addressValid = new ValidationLink(addressEdit, str -> !didThrow(() -> new Address(Main.params, str)));
        addressEdit.textProperty().addListener((obj, prev, cur) -> {
            if (addressValid.isValid.get())
                this.model.address.set(cur);
        });

        ValidationLink.autoDisableButton(nextButton,
                goalValid,
                new ValidationLink(titleEdit, str -> !str.isEmpty()),
                minPledgeValue,
                addressValid);

        roundCorners(coverImageView, 10);

        Label maxPledgesWarning = new Label(String.format("You can collect a maximum of %d pledges, due to limits in the Bitcoin protocol.", ProjectModel.MAX_NUM_INPUTS));
        maxPledgesWarning.setStyle("-fx-font-size: 12; -fx-padding: 10");
        maxPledgesPopOver = new PopOver(maxPledgesWarning);
        maxPledgesPopOver.setDetachable(false);
        maxPledgesPopOver.setArrowLocation(PopOver.ArrowLocation.BOTTOM_CENTER);
        minPledgeEdit.focusedProperty().addListener(o -> {
            if (minPledgeEdit.isFocused())
                maxPledgesPopOver.show(minPledgeEdit);
            else
                maxPledgesPopOver.hide();
        });
    }

    // Called by FXMLLoader.
    public void initialize() {
        // TODO: This fixed value won't work properly with internationalization.
        rootPane.setPrefWidth(618);
        rootPane.prefHeightProperty().bind(Main.instance.scene.heightProperty().multiply(0.8));
    }

    private void setupDefaultCoverImage() {
        // The default image is nice, so a lot of people (including possibly me) will be lazy and not change it. To
        // keep things interesting we randomly recolour the image here.
        try {
            ColorAdjust colorAdjust = new ColorAdjust();
            double randomHueAdjust = Math.random() * 2 - 1.0;
            colorAdjust.setHue(randomHueAdjust);
            // Draw into a canvas and then apply the effect, because if we snapshotted the image view, we'd end up
            // with the rounded corners which we don't want.
            Image image = new Image(getResource("default-cover-image.png").openStream());
            Canvas canvas = new Canvas(image.getWidth(), image.getHeight());
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.drawImage(image, 0, 0);
            gc.applyEffect(colorAdjust);
            WritableImage colouredImage = new WritableImage((int) image.getWidth(), (int) image.getHeight());
            canvas.snapshot(new SnapshotParameters(), colouredImage);
            coverImageView.setImage(colouredImage);
            // Convert to a PNG and store in the project model.
            ImageIO.setUseCache(false);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(SwingFXUtils.fromFXImage(colouredImage, null), "png", baos);
            model.image.set(ByteString.copyFrom(baos.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML
    public void nextClicked(ActionEvent event) {
        // Quick check that they haven't duplicated the title as otherwise that results in file name clashes
        // We could add (2) after the file name or whatever to avoid this, but multiple different projects with
        // the same title would be confusing anyway so just forbid it.
        if (!editing && Files.exists(AppDirectory.dir().resolve(Project.getSuggestedFileName(model.title.get())))) {
            informationalAlert("Title conflict",
                    "You already have a project with that title. Please choose another. If you are trying to create a " +
                            "different version, consider putting the date or a number in the title so people can distinguish them.");
            return;
        }
        AddProjectTypeWindow.open(model, editing);
    }

    @FXML
    public void imageSelectorClicked(MouseEvent event) {
        log.info("Image selector clicked");
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select an image file");
        chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Images (JPG/PNG/GIF)", "*.jpg", "*.jpeg", "*.png", "*.gif"));
        platformFiddleChooser(chooser);
        Path prevPath = Main.instance.prefs.getCoverPhotoFolder();
        if (prevPath != null)
            chooser.setInitialDirectory(prevPath.toFile());
        File result = chooser.showOpenDialog(Main.instance.mainStage);
        if (result == null) return;
        Main.instance.prefs.setCoverPhotoFolder(result.toPath().getParent());
        setImageTo(unchecked(() -> result.toURI().toURL()));
    }

    private void setImageTo(URL result) {
        try {
            log.info("Setting image to {}", result);
            if (result.getProtocol().startsWith("http")) {   // Also catch https
                final String oldLabel = coverImageLabel.getText();
                final DownloadProgress task = new DownloadProgress(result);
                task.setOnSucceeded(ev -> {
                    log.info("Image downloaded succeeded");
                    ByteString bytes = task.getValue();
                    coverImageLabel.setGraphic(null);
                    coverImageLabel.setText(oldLabel);
                    setImageTo(bytes);
                });
                task.setOnFailed(ev -> {
                    informationalAlert("Image load failed", "Could not download the image from the remote server: %s", task.getException().getLocalizedMessage());
                    coverImageLabel.setGraphic(null);
                    coverImageLabel.setText(oldLabel);
                });
                ProgressIndicator indicator = new ProgressIndicator();
                indicator.progressProperty().bind(task.progressProperty());
                indicator.setPrefHeight(50);
                indicator.setPrefWidth(50);
                coverImageLabel.setGraphic(indicator);
                coverImageLabel.setText("");
                Thread download = new Thread(task);
                download.setName("Download of " + result);
                download.setDaemon(true);
                download.start();
            } else {
                // Load in a blocking fashion.
                byte[] bits = ByteStreams.toByteArray(result.openStream());
                if (bits.length > 1024 * 1024 * 5) {
                    informationalAlert("Image too large", "Please make sure your image is smaller than 5mb, any larger is excessive.");
                    return;
                }
                final ByteString bytes = ByteString.copyFrom(bits);
                setImageTo(bytes);
            }
        } catch (Exception e) {
            log.error("Failed to load image", e);
            informationalAlert("Failed to load image", "%s", e.getLocalizedMessage());
        }
    }

    private void setImageTo(ByteString bytes) {
        coverImageView.setEffect(null);
        final Image image = new Image(bytes.newInput());
        if (image.getException() == null) {
            model.image.set(bytes);
            coverImageView.setImage(image);
        } else {
            log.error("Could not load image", image.getException());
        }
    }

    public void imageSelectorDragOver(DragEvent event) {
        Dragboard dragboard = event.getDragboard();
        if (dragboard.getFiles().size() == 1) {
            final String name = dragboard.getFiles().get(0).toString().toLowerCase();
            if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                return;
            }
        }
        if (dragboard.getUrl() != null) {
            // We accept all URLs and filter out the non-image ones later.
            event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
        }
    }

    @FXML
    public void imageSelectorDropped(DragEvent event) {
        log.info("Drop: {}", event);
        if (event.getDragboard().getFiles().size() == 1) {
            setImageTo(unchecked(() -> event.getDragboard().getFiles().get(0).toURI().toURL()));
        } else if (event.getDragboard().getUrl() != null) {
            setImageTo(unchecked(() -> new URL(event.getDragboard().getUrl())));
        }
    }

    @FXML
    public void openCoverPhotoFinder(MouseEvent event) {
        log.info("cover photo URL clicked");
        Main.instance.getHostServices().showDocument(String.format("http://%s/", COVERPHOTO_SITE));
        event.consume();
    }

    @FXML
    public void cancelClicked(ActionEvent event) {
        overlayUI.done();
    }
}
