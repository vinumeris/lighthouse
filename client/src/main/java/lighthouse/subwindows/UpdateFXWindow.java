package lighthouse.subwindows;

import com.google.common.util.concurrent.Futures;
import com.vinumeris.updatefx.UFXProtocol;
import com.vinumeris.updatefx.UpdateFX;
import com.vinumeris.updatefx.UpdateSummary;
import com.vinumeris.updatefx.Updater;
import de.jensd.fx.fontawesome.AwesomeIcon;
import de.jensd.fx.fontawesome.Icon;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.text.Text;
import lighthouse.Main;
import lighthouse.files.AppDirectory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.nio.file.Files.exists;
import static lighthouse.utils.GuiUtils.informationalAlert;

/**
 * Lets the user select a version to pin themselves to.
 */
public class UpdateFXWindow {
    public Main.OverlayUI<UpdateFXWindow> overlayUI;

    @FXML Text descriptionLabel;
    @FXML ListView<UFXProtocol.Update> updatesList;
    @FXML Button pinBtn;

    private ObservableList<UFXProtocol.Update> updates = FXCollections.observableArrayList();
    private SimpleIntegerProperty currentPin = new SimpleIntegerProperty();
    private UpdateSummary summary;

    public void initialize() {
        currentPin.set(UpdateFX.getVersionPin(AppDirectory.dir()));

        updatesList.setCellFactory(param -> new ListCell<UFXProtocol.Update>() {
            @Override
            protected void updateItem(@Nullable UFXProtocol.Update item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || summary == null) {
                    setText("");
                    setGraphic(null);
                    setStyle("");
                } else {
                    setStyle("");
                    setText(item == null ? "Latest version" : item.getDescription(0).getOneLiner());
                    Icon icon = Icon.create().icon(AwesomeIcon.THUMB_TACK);
                    icon.setStyle("-fx-font-family: FontAwesome; -fx-font-size: 1.5em");
                    icon.visibleProperty().bind(currentPin.isEqualTo(item != null ? item.getVersion() : 0));
                    setGraphic(icon);
                    // Bold the current version that's running, or latest if we're up to date.
                    if ((item == null && Main.VERSION == summary.newVersion) ||
                            (item != null && item.getVersion() == Main.VERSION && item.getVersion() != summary.newVersion))
                        setStyle("-fx-font-weight: bold");
                    else
                        setStyle("");
                }
            }
        });
        updatesList.setItems(updates);
        updatesList.getSelectionModel().selectFirst();
        updatesList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        updatesList.getSelectionModel().selectedIndexProperty().addListener((x, prev, cur) -> {
            if (cur.intValue() > 0)
                descriptionLabel.setText(updates.get(cur.intValue()).getDescription(0).getDescription());
            else
                descriptionLabel.setText("");
        });
    }

    public static Main.OverlayUI<UpdateFXWindow> open(Updater updater) {
        Main.OverlayUI<UpdateFXWindow> result = Main.instance.<UpdateFXWindow>overlayUI("subwindows/updatefx.fxml", "Application updates");
        result.controller.setUpdater(checkNotNull(updater));
        return result;
    }

    @FXML
    public void pinClicked(ActionEvent event) {
        UFXProtocol.Update selected = updatesList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UpdateFX.unpin(AppDirectory.dir());
            currentPin.set(0);
            informationalAlert("Version change",
                    "You will be switched to always track the latest version. The app will now restart.");
            Main.restart();
        } else {
            int ver = selected.getVersion();
            UpdateFX.pinToVersion(AppDirectory.dir(), ver);
            currentPin.set(ver);
            informationalAlert("Version change",
                    "You will be switched to always use version %d. The app will now restart.", ver);
            Main.restart();
        }
    }

    @FXML
    public void closeClicked(ActionEvent event) {
        overlayUI.done();
    }

    public void setUpdater(Updater updater) {
        if (updater.isDone())
            processUpdater(updater);
        else
            updater.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED, event -> {
                processUpdater(updater);
            });
    }

    private void processUpdater(Updater updater) {
        summary = Futures.getUnchecked(updater);
        updates.clear();
        updates.add(null);  // Sentinel for "latest"
        List<UFXProtocol.Update> list = new ArrayList<>(summary.updates.getUpdatesList());
        Collections.reverse(list);
        for (UFXProtocol.Update update : list) {
            // For each update in the index, check if we have it on disk (the index can contain updates older than
            // what we can roll back to).
            if (exists(AppDirectory.dir().resolve(format("%d.jar", update.getVersion())))) {
                if (update.getDescriptionCount() > 0)
                    updates.add(update);
            }
        }
    }
}
