package lighthouse.controls;

import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import javafx.beans.binding.StringExpression;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import lighthouse.Main;
import lighthouse.subwindows.EmbeddedWindow;
import lighthouse.utils.GuiUtils;
import net.glxn.qrgen.QRCode;
import net.glxn.qrgen.image.ImageType;
import org.bitcoinj.core.Address;
import org.bitcoinj.uri.BitcoinURI;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;

import static javafx.beans.binding.Bindings.convert;

/**
 * A custom control that implements a clickable, copyable Bitcoin address. Clicking it opens a local wallet app. The
 * address looks like a blue hyperlink. Next to it there are two icons, one that copies to the clipboard and another
 * that shows a QRcode.
 */
public class ClickableBitcoinAddress extends AnchorPane {
    @FXML protected Label addressLabel;
    @FXML protected ContextMenu addressMenu;
    @FXML protected Label copyWidget;
    @FXML protected Label qrCode;

    protected SimpleObjectProperty<Address> address = new SimpleObjectProperty<>();
    private final StringExpression addressStr;

    public ClickableBitcoinAddress() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("bitcoin_address.fxml"));
            loader.setRoot(this);
            loader.setController(this);
            // The following line is supposed to help Scene Builder, although it doesn't seem to be needed for me.
            loader.setClassLoader(getClass().getClassLoader());
            loader.load();

            copyWidget.setCursor(Cursor.HAND);
            AwesomeDude.setIcon(copyWidget, AwesomeIcon.COPY);
            Tooltip.install(copyWidget, new Tooltip("Copy address to clipboard"));

            qrCode.setCursor(Cursor.HAND);
            AwesomeDude.setIcon(qrCode, AwesomeIcon.QRCODE);
            Tooltip.install(qrCode, new Tooltip("Show a barcode scannable with a mobile phone for this address"));

            addressStr = convert(address);
            addressLabel.textProperty().bind(addressStr);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String uri() {
        return BitcoinURI.convertToBitcoinURI(address.get(), null, Main.APP_NAME, null);
    }

    public Address getAddress() {
        return address.get();
    }

    public void setAddress(Address address) {
        this.address.set(address);
    }

    public ObjectProperty<Address> addressProperty() {
        return address;
    }

    @FXML
    protected void copyAddress(ActionEvent event) {
        // User clicked icon or menu item.
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(addressStr.get());
        content.putHtml(String.format("<a href='%s'>%s</a>", uri(), addressStr.get()));
        clipboard.setContent(content);
    }

    @FXML
    protected void requestMoney(MouseEvent event) {
        if (event.getButton() == MouseButton.SECONDARY || (event.getButton() == MouseButton.PRIMARY && event.isMetaDown())) {
            // User right clicked or the Mac equivalent. Show the context menu.
            addressMenu.show(addressLabel, event.getScreenX(), event.getScreenY());
        } else {
            // User left clicked.
            try {
                Desktop.getDesktop().browse(URI.create(uri()));
            } catch (IOException e) {
                GuiUtils.informationalAlert("Opening wallet app failed", "Perhaps you don't have one installed?");
            }
        }
    }

    @FXML
    protected void copyWidgetClicked(MouseEvent event) {
        copyAddress(null);
        GuiUtils.arrowBubbleToNode(copyWidget, "Address copied to clipboard");
    }

    @FXML
    protected void showQRCode(MouseEvent event) {
        // Serialize to PNG and back into an image. Pretty lame but it's the shortest code to write and I'm feeling
        // lazy tonight.
        final byte[] imageBytes = QRCode
                .from(uri())
                .withSize(512, 384)
                .to(ImageType.PNG)
                .stream()
                .toByteArray();
        Image qrImage = new Image(new ByteArrayInputStream(imageBytes));
        ImageView view = new ImageView(qrImage);
        Label label = new Label(address.get().toString());
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        label.setPadding(new Insets(0, 0, 30, 0));
        VBox vbox = new VBox(view, label);
        vbox.setPrefWidth(qrImage.getWidth());
        vbox.setStyle("-fx-background-color: white");
        vbox.setPrefHeight(qrImage.getHeight() + label.getHeight() + vbox.getSpacing());
        EmbeddedWindow window = new EmbeddedWindow("QR code", vbox);
        final Main.OverlayUI<ClickableBitcoinAddress> overlay = Main.instance.overlayUI(window, this);
        window.setOnCloseClicked(overlay::done);
        //overlay.outsideClickDismisses();
        view.setOnMouseClicked(event1 -> overlay.done());
    }
}
