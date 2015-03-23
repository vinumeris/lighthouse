package lighthouse.subwindows;

import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import lighthouse.*;
import org.slf4j.*;

import java.net.*;

import static lighthouse.utils.I18nUtil._;

/**
 * Quick usability hint for people who don't RTFM and get confused what they do after creating a project :-)
 * This will go away at some point once issue 31 (smoother upload/review queue path) is implemented.
 */
public class ProjectSubmitInstructionsWindow {
    private static final Logger log = LoggerFactory.getLogger(ProjectSubmitInstructionsWindow.class);

    @FXML Label submitAddressLabel;
    @FXML Label submitProjectLabel;
    @FXML Button closeButton;
    public Main.OverlayUI<InnerWindow> overlayUI;
    
    public void initialize() {
        // Load localized strings
        closeButton.setText(_("Close"));
        submitProjectLabel.setText(_("Now submit your project for hosting:"));
    }

    public static void open(String submitAddress, ServerList.SubmitType submitType) {
        log.info("Showing project submit instructions: {}", submitAddress);
        ProjectSubmitInstructionsWindow window = Main.instance.<ProjectSubmitInstructionsWindow>overlayUI(
                        "subwindows/project_submit_instructions.fxml", _("Information")).controller;
        if (submitType == ServerList.SubmitType.EMAIL) {
            window.submitAddressLabel.setText(submitAddress);
            window.submitAddressLabel.setOnMouseClicked(ev -> {
                Main.instance.getHostServices().showDocument(String.format("mailto:%s", submitAddress));
            });
        } else if (submitType == ServerList.SubmitType.WEB) {
            String hostname = URI.create(submitAddress).getHost();
            window.submitAddressLabel.setText(hostname);
            window.submitAddressLabel.setOnMouseClicked(ev -> {
                Main.instance.getHostServices().showDocument(submitAddress);
            });
        }
    }

    @FXML
    public void closeClicked(ActionEvent event) {
        overlayUI.done();
    }
}
