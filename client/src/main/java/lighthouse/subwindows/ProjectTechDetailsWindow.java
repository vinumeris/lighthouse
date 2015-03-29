package lighthouse.subwindows;

import javafx.application.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import lighthouse.*;
import lighthouse.protocol.*;
import org.bitcoinj.core.*;
import org.bitcoinj.script.*;

import java.net.*;
import java.time.*;
import java.time.format.*;

import static lighthouse.utils.I18nUtil.*;

/**
 * Window that shows protobuf fields like auth key, target addresses etc. Useful when the crowdfund is going to a well
 * known address, as users can check the project file is legit, and also for project author signing messages.
 */
public class ProjectTechDetailsWindow {
    @FXML VBox targetAddressVBox;
    @FXML TextField serverURL;
    @FXML TextField minPledgeSize;
    @FXML TextField authKey;
    @FXML TextField netParams;
    @FXML TextField creationTime;

    public Main.OverlayUI<ProjectTechDetailsWindow> overlayUI;

    private void initFromProject(Project project) {
        for (TransactionOutput output : project.getOutputs()) {
            Script script = output.getScriptPubKey();
            String text;
            if (script.isSentToAddress() || script.isPayToScriptHash())
                text = script.getToAddress(project.getParams()).toString();
            else if (script.isSentToMultiSig())
                text = tr("Raw multi-sig");
            else
                text = tr("Unrecognised project output type");
            TextField tf = new TextField(text);
            tf.setEditable(false);
            targetAddressVBox.getChildren().add(tf);
        }
        URI url = project.getPaymentURL();
        if (url != null) serverURL.setText(url.toString());
        minPledgeSize.setText(project.getMinPledgeAmount().toPlainString());
        authKey.setText(ECKey.fromPublicOnly(project.getAuthKey()).toAddress(project.getParams()).toString());
        netParams.setText(project.getProtoDetails().getNetwork());
        creationTime.setText(Instant.ofEpochSecond(project.getProtoDetails().getTime()).atZone(ZoneId.of("UTC")).format(DateTimeFormatter.RFC_1123_DATE_TIME));
    }

    public static void open(Project project) {
        Main.OverlayUI<ProjectTechDetailsWindow> ui = Main.instance.overlayUI("subwindows/project_tech_details.fxml", tr("Project details"));
        ui.controller.initFromProject(project);
    }

    @FXML
    public void closeClicked(ActionEvent event) {
        Platform.runLater(overlayUI::done);
    }
}
