package lighthouse.subwindows;

import com.google.common.base.*;
import com.google.common.net.*;
import com.vinumeris.crashfx.*;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.text.*;
import javafx.stage.*;
import lighthouse.*;
import lighthouse.model.*;
import lighthouse.protocol.*;
import lighthouse.utils.*;
import org.slf4j.*;

import java.io.*;
import java.nio.file.*;

import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil.*;

/**
 * Screen where user chooses between server assisted and serverless mode.
 */
public class AddProjectTypeWindow {
    private static final Logger log = LoggerFactory.getLogger(AddProjectTypeWindow.class);

    @FXML RadioButton fullyDecentralised;
    @FXML RadioButton serverAssisted;
    @FXML ComboBox<String> serverNameCombo;
    @FXML Button saveButton;
    @FXML Text serverInstructionsLabel;
    @FXML Button backButton;
    @FXML Text serverCollectsPledgesText;
    @FXML Label serverNameLabel;
    @FXML Text fullyDecentralisedDescText;

    private ProjectModel model;
    private boolean editing;

    public Main.OverlayUI<InnerWindow> overlayUI;

    public static Main.OverlayUI<AddProjectTypeWindow> open(ProjectModel projectModel, boolean editing) {
        Main.OverlayUI<AddProjectTypeWindow> result = Main.instance.overlayUI("subwindows/add_project_type.fxml",
                editing ? tr("Change type") : tr("Select type"));
        result.controller.setModel(projectModel);
        result.controller.editing = editing;
        return result;
    }

    private void setModel(ProjectModel model) {
        this.model = model;
        if (model.serverName.get() != null) {
            serverNameCombo.setValue(model.serverName.get());
            serverAssisted.setSelected(true);
        } else {
            fullyDecentralised.setSelected(true);
        }
    }

    public void initialize() {
        ObservableList<String> hostnames = FXCollections.observableArrayList(ServerList.hostnameToServer.keySet());
        serverNameCombo.itemsProperty().set(hostnames);
        serverNameCombo.disableProperty().bind(fullyDecentralised.selectedProperty());

        serverNameCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            ServerList.Entry entry = ServerList.hostnameToServer.get(newValue);
            if (entry != null) {
                serverInstructionsLabel.setText(entry.instructions);
            } else {
                serverInstructionsLabel.setText("");
            }
        });
        
        // Load localized strings
        saveButton.setText(tr("Save"));
        backButton.setText(tr("Back"));
        serverCollectsPledgesText.setText(tr("A server collects and monitors pledges on your behalf. You can use a community run server or run your own."));
        serverNameLabel.setText(tr("Server name"));
        serverInstructionsLabel.setText(tr("Server instructions"));
        serverAssisted.setText(tr("Server assisted"));
        fullyDecentralised.setText(tr("Fully decentralised"));
        fullyDecentralisedDescText.setText(tr("This style of project does not require any server. " +
                "Backers will be given a pledge file that they must get back to you via email, shared folder, instant messaging etc. " +
                "People will not be able to see how much money was raised so far unless they download the pledge files themselves.\n\n" +
                "Fully decentralised can be an appropriate choice when you are fund raising from a group of friends or will be managing the pledges " +
                "in some other way. It's less convenient but doesn't require any infrastructure."));
    }

    private boolean isServerNameValid(String str) {
        try {
            if (str.equals("localhost")) return true;
            HostAndPort hostAndPort = HostAndPort.fromString(str);
            return (InternetDomainName.isValid(hostAndPort.getHostText()) &&
                    InternetDomainName.from(hostAndPort.getHostText()).isUnderPublicSuffix());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Returns true if form is valid.
    private boolean validateAndSync() {
        if (serverAssisted.isSelected()) {
            if (serverNameCombo.getValue() == null || serverNameCombo.getValue().equals("")) {
                GuiUtils.arrowBubbleToNode(serverNameCombo, tr("You must pick a server."));
                return false;
            } else if (!isServerNameValid(serverNameCombo.getValue())) {
                GuiUtils.arrowBubbleToNode(serverNameCombo, tr("The server name is not considered valid."));
                return false;
            }
        }

        model.serverName.set(serverAssisted.isSelected() ? serverNameCombo.getValue() : "");
        return true;
    }

    @FXML
    public void saveClicked(ActionEvent event) {
        // Work around ConcurrentModificationException error.
        Platform.runLater(() -> {
            if (!validateAndSync())
                return;

            final LHProtos.ProjectDetails detailsProto = model.getDetailsProto().build();
            log.info("Saving: {}", detailsProto.getExtraDetails().getTitle());
            try {
                Project project;
                if (detailsProto.hasPaymentUrl()) {
                    // User has to explicitly export it somewhere (not watched) so they can get it to the server.
                    project = Main.backend.saveProject(model.getProject());
                    ExportWindow.openForProject(project);
                } else {
                    GuiUtils.informationalAlert(tr("Folder watching"),
                            tr("The folder to which you save your project file will be watched for pledge files. When you receive them from backers, just put them in the same directory and they will appear."));
                    // Request directory first then save, so the animations are right.
                    DirectoryChooser chooser = new DirectoryChooser();
                    chooser.setTitle(tr("Select a directory to store the project and pledges"));
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
                }
            } catch (IOException e) {
                log.error("Could not save project", e);
                informationalAlert(tr("Could not save project"),
                        // TRANS: %s = error message
                        tr("An error was encountered whilst trying to save the project: %s"),
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
        // Work around ConcurrentModificationException error in framework.
        Platform.runLater(() -> {
            if (editing)
                EditProjectWindow.openForEdit(model);
            else
                EditProjectWindow.openForCreate(model);
        });
    }
}
