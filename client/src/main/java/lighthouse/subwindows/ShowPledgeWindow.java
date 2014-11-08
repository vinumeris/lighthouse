package lighthouse.subwindows;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import lighthouse.Main;
import lighthouse.protocol.LHProtos;
import org.bitcoinj.core.Coin;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Allows the user to view the details of the pledge and copy/paste the
 */
public class ShowPledgeWindow {
    @FXML Label amountLabel;
    @FXML Label contactLabel;
    @FXML Label dateLabel;
    @FXML TextArea messageField;

    public Main.OverlayUI<ShowPledgeWindow> overlayUI;

    public static Main.OverlayUI<ShowPledgeWindow> open(LHProtos.Pledge pledge) {
        Main.OverlayUI<ShowPledgeWindow> ui = Main.instance.overlayUI("subwindows/show_pledge.fxml", "View pledge");
        ui.controller.init(pledge);
        return ui;
    }

    private void init(LHProtos.Pledge pledge) {
        amountLabel.setText(Coin.valueOf(pledge.getTotalInputValue()).toFriendlyString());
        if (pledge.hasPledgeDetails()) {
            contactLabel.setText(pledge.getPledgeDetails().getContactAddress());
            // Looks like an email address?
            if (contactLabel.getText().matches("[a-zA-Z0-9\\._]+@[^ ]+")) {
                contactLabel.setStyle(contactLabel.getStyle() + "; -fx-cursor: hand; -fx-text-fill: blue; -fx-underline: true");
                contactLabel.setOnMouseClicked(ev -> Main.instance.getHostServices().showDocument(String.format("mailto:%s", contactLabel.getText())));
            }
            messageField.setText(pledge.getPledgeDetails().getMemo());
        } else {
            contactLabel.setText("<unknown>");
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM uuuu HH:mm");
        LocalDateTime time = LocalDateTime.ofEpochSecond(pledge.getTimestamp(), 0, ZoneOffset.UTC);
        dateLabel.setText(time.format(formatter));
    }

    @FXML
    public void closeClicked(ActionEvent event) {
        overlayUI.done();
    }
}
