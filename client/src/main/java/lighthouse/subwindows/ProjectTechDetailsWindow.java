package lighthouse.subwindows;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import lighthouse.Main;
import lighthouse.protocol.Project;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static lighthouse.utils.I18nUtil._;

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
    @FXML Button closeButton;
    @FXML Label targetAddressLabel;
    @FXML Label serverUrlLabel;
    @FXML Label minPledgeLabel;
    @FXML Label authKeyLabel;
    @FXML Label networkParamsLabel;
    @FXML Label creationTimeLabel;

    public Main.OverlayUI<ProjectTechDetailsWindow> overlayUI;

    private void initFromProject(Project project) {
        for (TransactionOutput output : project.getOutputs()) {
            Script script = output.getScriptPubKey();
            String text;
            if (script.isSentToAddress() || script.isPayToScriptHash())
                text = script.getToAddress(project.getParams()).toString();
            else if (script.isSentToMultiSig())
                text = _("Raw multi-sig");
            else
                text = _("Unrecognised project output type");
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
        
        // Load localized strings
        closeButton.setText(_("Close"));
        targetAddressLabel.setText(_("Target address"));
        serverUrlLabel.setText(_("Server URL"));
        minPledgeLabel.setText(_("Min pledge size"));
        authKeyLabel.setText(_("Auth key"));
        networkParamsLabel.setText(_("Network parameters"));
        creationTimeLabel.setText(_("Creation time"));
        if (url == null) serverURL.setPromptText(_("Serverless project"));
    }

    public static void open(Project project) {
        Main.OverlayUI<ProjectTechDetailsWindow> ui = Main.instance.overlayUI("subwindows/project_tech_details.fxml", _("Project details"));
        ui.controller.initFromProject(project);
    }

    @FXML
    public void closeClicked(ActionEvent event) {
        Platform.runLater(overlayUI::done);
    }
}
