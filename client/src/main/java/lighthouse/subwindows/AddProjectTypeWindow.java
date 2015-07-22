package lighthouse.subwindows;

import com.google.common.base.*;
import com.google.common.net.*;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.text.*;
import lighthouse.*;
import lighthouse.model.*;
import lighthouse.protocol.*;
import lighthouse.utils.*;
import org.slf4j.*;

import java.io.*;

import static com.google.common.base.Preconditions.*;
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
    @FXML Label serverNameLabel;

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
                serverInstructionsLabel.setText(entry.getInstructions());
            } else {
                serverInstructionsLabel.setText("");
            }
        });
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
                Project project = editing ? Main.backend.editProject(model.getProject(), checkNotNull(model.originalProject)) : Main.backend.saveProject(model.getProject());
                ExportWindow.openForProject(project);
            } catch (IOException e) {
                log.error("Could not save project", e);
                informationalAlert(tr("Could not save project"),
                        // TRANS: %s = error message
                        tr("An error was encountered whilst trying to save the project: %s"),
                        Throwables.getRootCause(e));
            }
        });
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
