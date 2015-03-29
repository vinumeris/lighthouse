package lighthouse.subwindows;

import com.google.common.base.*;
import de.jensd.fx.fontawesome.*;
import javafx.concurrent.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import lighthouse.*;
import lighthouse.protocol.*;
import lighthouse.utils.*;
import lighthouse.wallet.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;

import static com.google.common.base.Preconditions.*;
import static lighthouse.utils.I18nUtil.*;

public class PledgeUploadWindow {
    private static final Logger log = LoggerFactory.getLogger(PledgeUploadWindow.class);

    public static final int TIMEOUT_MSEC = 60 * 1000;  // 60 seconds: needs to be a long time to cover dep broadcast for servers with no local node.

    public Main.OverlayUI overlayUI;

    @FXML public Label uploadLabel;
    @FXML public Label uploadIcon;
    @FXML public ProgressIndicator uploadProgress;
    private Task<Void> uploadTask;

    private Runnable onSuccess;

    public void initialize() {
        AwesomeDude.setIcon(uploadIcon, AwesomeIcon.CLOUD_UPLOAD, "60");
    }

    public static void open(Project project, PledgingWallet.PendingPledge pledge, Runnable onSuccess) {
        Main.OverlayUI<PledgeUploadWindow> window = Main.instance.overlayUI("subwindows/pledge_upload.fxml", tr("Upload"));
        window.controller.startUpload(project, pledge);
        window.controller.onSuccess = onSuccess;
    }

    private void startUpload(Project project, PledgingWallet.PendingPledge pledge) {
        uploadTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    URI url = project.getPaymentURL();
                    checkState(url != null);
                    log.info("Uploading pledge to {}", url);
                    // Tricksy URLs like jar:// result in a crash at this point.
                    HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setConnectTimeout(TIMEOUT_MSEC);
                    connection.setInstanceFollowRedirects(true);
                    connection.setReadTimeout(TIMEOUT_MSEC);
                    try {
                        log.info("Connecting");
                        connection.connect();
                    } catch (IOException e) {
                        log.error("Connect failed: {}", e.getLocalizedMessage());
                        // TRANS: %s = error message
                        updateMessage(String.format(tr("Connection failed: %s"), e.getLocalizedMessage()));
                        throw e;
                    }
                    log.info("Connected");
                    updateMessage(tr("Connected, uploading ..."));
                    try (OutputStream stream = connection.getOutputStream()) {
                        final LHProtos.Pledge data = pledge.getData();
                        data.writeTo(stream);
                        log.info("Data uploaded");
                        int response = connection.getResponseCode();
                        if (response != HttpURLConnection.HTTP_OK) {
                            // TRANS: %d = error code number, %s = error message
                            throw new Exception(String.format(tr("Server said %d %s"), connection.getResponseCode(), connection.getResponseMessage()));
                        } else {
                            log.info("Server accepted, committing");
                            pledge.commit(false);
                        }
                    }
                    return null;
                } catch (Exception e) {
                    GuiUtils.informationalAlert(tr("Upload failed"),
                            // TRANS: %s = error message
                            tr("%s. This probably indicates either a problem with " +
                                    "your internet connection or a configuration issue with the server. You could try again later."),
                            Throwables.getRootCause(e).getLocalizedMessage());
                    log.error("Upload error", e);
                    throw e;
                }
            }
        };
        uploadLabel.textProperty().bind(uploadTask.messageProperty());
        uploadTask.setOnSucceeded(ev -> {
            overlayUI.done();
            onSuccess.run();
        });
        uploadTask.setOnFailed(ev -> {
            overlayUI.done();
        });
        Thread thread = new Thread(uploadTask);
        thread.setDaemon(false);
        thread.setName("Pledge upload");
        thread.start();
    }

    @FXML
    public void cancelClicked(ActionEvent event) {
        uploadTask.cancel(true);
        overlayUI.done();
    }
}
