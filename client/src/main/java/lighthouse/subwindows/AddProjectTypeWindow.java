package lighthouse.subwindows;

import com.google.common.base.Throwables;
import com.google.common.net.HostAndPort;
import com.google.common.net.InternetDomainName;
import com.google.protobuf.ByteString;
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
import static lighthouse.utils.GuiUtils.*;

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
    public Main.OverlayUI<InnerWindow> overlayUI;

    public static Main.OverlayUI<AddProjectTypeWindow> open(ProjectModel projectModel) {
        Main.OverlayUI<AddProjectTypeWindow> result = Main.instance.overlayUI("subwindows/add_project_type.fxml", "Type");
        result.controller.model = projectModel;
        return result;
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
                // Mark this project as being created by us in the wallet. We have to record this explicitly because
                // the target address might not actually be one of ours, e.g. if it's being paid directly to a TREZOR.
                Main.wallet.setTag("com.vinumeris.cc:owned:" + project.getID(), ByteString.EMPTY);
            } catch (IOException e) {
                informationalAlert("Could not save project",
                        "An error was encountered whilst trying to save the project: %s",
                        Throwables.getRootCause(e).getMessage());
            }
        });
    }

    private void saveAndWatchDirectory(Project project, Path dirPath) {
        try {
            // Write to tmp file and then rename, otherwise it's possible that the Linux kernel delivers directory
            // change events to the DiskManager whilst the file is partially written.
            Path file = dirPath.resolve(project.getSuggestedFileName() + ".tmp");
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(file))) {
                project.getProto().writeTo(stream);
            }
            Path realPath = dirPath.resolve(project.getSuggestedFileName());
            Files.move(file, realPath);
            Main.backend.addProjectFile(realPath);
        } catch (IOException e) {
            crashAlert(e);
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
