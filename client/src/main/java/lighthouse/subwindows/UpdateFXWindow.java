package lighthouse.subwindows;

import com.google.common.util.concurrent.*;
import com.vinumeris.updatefx.*;
import de.jensd.fx.fontawesome.*;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.concurrent.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.text.*;
import lighthouse.*;

import javax.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

import static com.google.common.base.Preconditions.*;
import static java.lang.String.format;
import static java.nio.file.Files.*;
import static lighthouse.utils.GuiUtils.*;

/**
 * Lets the user select a version to pin themselves to.
 */
public class UpdateFXWindow {
    public static final String CACHED_UPDATE_SUMMARY = "cached-update-summary";
    public Main.OverlayUI<UpdateFXWindow> overlayUI;

    @FXML Text descriptionLabel;
    @FXML ListView<UFXProtocol.Update> updatesList;
    @FXML Button pinBtn;

    private ObservableList<UFXProtocol.Update> updates = FXCollections.observableArrayList();
    private SimpleIntegerProperty currentPin = new SimpleIntegerProperty();
    private UpdateSummary summary;

    public void initialize() {
        currentPin.set(UpdateFX.getVersionPin(Main.unadjustedAppDir));

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
                    if ((item == null && Main.VERSION == summary.highestVersion) ||
                            (item != null && item.getVersion() == Main.VERSION && item.getVersion() != summary.highestVersion))
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
            UpdateFX.unpin(Main.unadjustedAppDir);
            currentPin.set(0);
            informationalAlert("Version change",
                    "You will be switched to always track the latest version. The app will now restart.");
            Main.restart();
        } else {
            int ver = selected.getVersion();
            UpdateFX.pinToVersion(Main.unadjustedAppDir, ver);
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
        if (updater.isDone() || Main.offline)
            processUpdater(updater);
        else
            updater.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED, event -> {
                processUpdater(updater);
            });
    }

    private void processUpdater(Updater updater) {
        updates.clear();
        updates.add(null);  // Sentinel for "latest"
        UpdateSummary s = null;
        try {
            if (!Main.offline)
                s = Futures.getUnchecked(updater);
        } catch (Exception e) {
            log.warn("Failed to get online updates index, trying to fall back to disk cache: {}", e.getMessage());
        }
        if (s != null)
            summary = s;
        else
            summary = loadCachedIndex();
        if (summary == null) {
            log.error("Not online and failed to load cached updates, showing blank window.");
            pinBtn.setDisable(true);
            return;   // Not online and no cached updates, or some other issue, so give up.
        }
        List<UFXProtocol.Update> list = new ArrayList<>(summary.updates.getUpdatesList());
        Collections.reverse(list);
        for (UFXProtocol.Update update : list) {
            // For each update in the index, check if we have it on disk (the index can contain updates older than
            // what we can roll back to).
            if (exists(Main.unadjustedAppDir.resolve(format("%d.jar", update.getVersion())))) {
                if (update.getDescriptionCount() > 0)
                    updates.add(update);
            }
        }
    }

    // Returns an UpdateSummary object that we previously had downloaded, so we can still display update info if
    // we are offline, or null if not found or some other error.
    @Nullable
    private UpdateSummary loadCachedIndex() {
        try (InputStream is = Files.newInputStream(Main.unadjustedAppDir.resolve(CACHED_UPDATE_SUMMARY))) {
            return new UpdateSummary(Main.VERSION, UFXProtocol.Updates.parseDelimitedFrom(is));
        } catch (IOException e) {
            return null;
        }
    }

    public static void saveCachedIndex(UFXProtocol.Updates updates) {
        try (OutputStream os = Files.newOutputStream(Main.unadjustedAppDir.resolve(CACHED_UPDATE_SUMMARY))) {
            updates.writeDelimitedTo(os);
        } catch (IOException e) {
            log.error("Failed to save cached update index", e);
        }
    }
}
