package lighthouse.subwindows;

import com.google.common.base.Throwables;
import com.google.common.net.HostAndPort;
import com.google.common.net.InternetDomainName;
import com.vinumeris.crashfx.CrashFX;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import lighthouse.Main;
import lighthouse.model.ProjectModel;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.Project;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.ValidationLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static javafx.beans.binding.Bindings.not;
import static lighthouse.utils.GuiUtils.informationalAlert;
import static lighthouse.utils.GuiUtils.platformFiddleChooser;

/**
 * Screen where user chooses between server assisted and serverless mode.
 */
public class AddProjectTypeWindow {
    private static final Logger log = LoggerFactory.getLogger(AddProjectTypeWindow.class);

    @FXML RadioButton fullyDecentralised;
    @FXML RadioButton serverAssisted;
    @FXML TextField serverNameEdit;
    @FXML Button saveButton;

    private ProjectModel model;
    private boolean editing;

    public Main.OverlayUI<InnerWindow> overlayUI;

    public static Main.OverlayUI<AddProjectTypeWindow> open(ProjectModel projectModel, boolean editing) {
        Main.OverlayUI<AddProjectTypeWindow> result = Main.instance.overlayUI("subwindows/add_project_type.fxml",
                editing ? "Change type" : "Select type");
        result.controller.setModel(projectModel);
        result.controller.editing = editing;
        return result;
    }

    private void setModel(ProjectModel model) {
        this.model = model;
        if (model.serverName.get() != null) {
            serverNameEdit.setText(model.serverName.get());
            serverAssisted.setSelected(true);
        } else {
            fullyDecentralised.setSelected(true);
        }
    }

    public void initialize() {
        ValidationLink serverName = new ValidationLink(serverNameEdit, this::isServerNameValid);
        serverNameEdit.textProperty().addListener(o -> {
            // Note that the validation link is updated AFTER this runs, so we must test directly.
            final String text = serverNameEdit.getText();
            if (isServerNameValid(text)) {
                this.model.serverName.set(text);
            }
        });
        saveButton.disableProperty().bind(
            serverAssisted.selectedProperty().and(
                serverNameEdit.textProperty().isEmpty().or(not(serverName.isValid))
            )
        );
        serverNameEdit.disableProperty().bind(fullyDecentralised.selectedProperty());
    }

    private boolean isServerNameValid(String str) {
        try {
            if (str.isEmpty() || str.equals("localhost")) return true;
            HostAndPort hostAndPort = HostAndPort.fromString(str);
            return (InternetDomainName.isValid(hostAndPort.getHostText()) &&
                    InternetDomainName.from(hostAndPort.getHostText()).isUnderPublicSuffix());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @FXML
    public void saveClicked(ActionEvent event) {
        // Work around ConcurrentModificationException error.
        Platform.runLater(() -> {
            final LHProtos.ProjectDetails detailsProto = model.getDetailsProto().build();
            log.info("Saving: {}", detailsProto.getExtraDetails().getTitle());
            try {
                Project project;
                if (!detailsProto.hasPaymentUrl()) {
                    GuiUtils.informationalAlert("Folder watching",
                            "The folder to which you save your project file will be watched for pledge files. When you receive them from backers, just put them in the same directory and they should appear.");
                    // Request directory first then save, so the animations are right.
                    DirectoryChooser chooser = new DirectoryChooser();
                    chooser.setTitle("Select a directory to store the project and pledges");
                    platformFiddleChooser(chooser);
                    File dir = chooser.showDialog(Main.instance.mainStage);
                    if (dir == null)
                        return;
                    final Path dirPath = dir.toPath();
                    project = model.getProject();
                    // Make sure we don't try and run too many animations simultaneously.
                    final Project fp = project;
                    overlayUI.runAfterFade(ev -> {
                        saveAndWatchDirectory(fp, dirPath);
                    });
                    overlayUI.done();
                } else {
                    // User has to explicitly export it somewhere (not watched) so they can get it to the server.
                    project = Main.backend.saveProject(model.getProject());
                    ExportWindow.openForProject(project);
                }
            } catch (IOException e) {
                log.error("Could not save project", e);
                informationalAlert("Could not save project",
                        "An error was encountered whilst trying to save the project: %s",
                        Throwables.getRootCause(e));
            }
        });
    }

    private void saveAndWatchDirectory(Project project, Path dirPath) {
        try {
            Path file = dirPath.resolve(project.getSuggestedFileName());
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(file))) {
                project.getProto().writeTo(stream);
            }
            Main.backend.importProjectFrom(file);
        } catch (IOException e) {
            CrashFX.propagate(e);
        }
    }

    @FXML
    public void cancelClicked(ActionEvent event) {
        overlayUI.done();
    }

    @FXML
    public void fullyDecentralisedPress(ActionEvent event) {
        serverNameEdit.setText("");
    }
}
