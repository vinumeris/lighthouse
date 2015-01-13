package lighthouse.subwindows;

import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import lighthouse.*;
import org.slf4j.*;

/**
 * Quick usability hint for people who don't RTFM and get confused what they do after creating a project :-)
 * This will go away at some point once issue 31 (smoother upload/review queue path) is implemented.
 */
public class ProjectSubmitInstructionsWindow {
    private static final Logger log = LoggerFactory.getLogger(ProjectSubmitInstructionsWindow.class);

    @FXML Label submitEmailAddr;
    public Main.OverlayUI<InnerWindow> overlayUI;

    public static void open(String submitEmail) {
        log.info("Showing project submit instructions: {}", submitEmail);
        ProjectSubmitInstructionsWindow window = Main.instance.<ProjectSubmitInstructionsWindow>overlayUI(
                        "subwindows/project_submit_instructions.fxml", "Information").controller;
        window.submitEmailAddr.setText(submitEmail);
        window.submitEmailAddr.setOnMouseClicked(ev -> {
            Main.instance.getHostServices().showDocument(String.format("mailto:%s", submitEmail));
        });
    }

    @FXML
    public void closeClicked(ActionEvent event) {
        overlayUI.done();
    }
}
