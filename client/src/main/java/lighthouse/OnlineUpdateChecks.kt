package lighthouse

import com.vinumeris.updatefx.UpdateFX
import com.vinumeris.updatefx.Updater
import javafx.beans.value.ObservableDoubleValue
import lighthouse.subwindows.UpdateFXWindow
import lighthouse.utils.GuiUtils
import lighthouse.utils.I18nUtil
import org.bitcoinj.params.RegTestParams
import org.slf4j.LoggerFactory
import java.net.URI

public enum class UpdateState {
    UNSTARTED,
    CHECKING,
    WE_ARE_FRESH,
    DOWNLOADING,
    AWAITING_APP_RESTART,
    FAILED
}

public class UpdateCheckStrings {
    companion object {
        val DOWNLOADING_SOFTWARE_UPDATE = I18nUtil.tr("Downloading software update")
        val RESTART = I18nUtil.tr("Restart")
        val PLEASE_RESTART_NOW = I18nUtil.tr("Please restart the app to upgrade to the new version.")
    }
}

/**
 * Wraps UpdateFX and handles the differences between the first run blocking update and rest-of-time async/background
 * updates.
 */
public class OnlineUpdateChecks(val onStateChanged: (UpdateState, OnlineUpdateChecks) -> Unit) {
    private val log = LoggerFactory.getLogger(OnlineUpdateChecks::class.java)

    val updater: Updater
    var state: UpdateState = UpdateState.UNSTARTED
        set(value) {
            field = value
            onStateChanged(value, this)
        }
    val progress: ObservableDoubleValue get() = updater.progressProperty()
    val exception: Throwable get() = updater.exception

    init {
        updater = Updater(URI.create(Main.instance.updatesURL), Main.APP_NAME, Main.unadjustedAppDir,
                UpdateFX.findCodePath(Main::class.java), Main.UPDATE_SIGNING_KEYS, Main.UPDATE_SIGNING_THRESHOLD)

        if (Main.instance.updatesURL != Main.UPDATES_BASE_URL)
            updater.setOverrideURLs(true)    // For testing.

        updater.progressProperty().addListener { _ ->
            if (state == UpdateState.CHECKING) {
                state = UpdateState.DOWNLOADING
                updater.setOnSucceeded { state = UpdateState.AWAITING_APP_RESTART }
            }
        }

        updater.setOnSucceeded() {
            // Save the updates list to disk so we can still display the updates screen even if we're offline.
            UpdateFXWindow.saveCachedIndex(updater.get().updates)
            if (state == UpdateState.CHECKING)
                state = UpdateState.WE_ARE_FRESH
        }

        // Don't bother the user if update check failed: assume some temporary server error that can be fixed silently.
        updater.setOnFailed() {
            log.error("Online update check failed", updater.exception)
            if (Main.params !== RegTestParams.get())
                GuiUtils.informationalAlert(I18nUtil.tr("Online update failed"), // TRANS: %s = error message
                        I18nUtil.tr("An error was encountered whilst attempting to download or apply an online update: %s"), exception)
            state = UpdateState.FAILED
        }
    }

    fun start() {
        state = UpdateState.CHECKING
        log.info("Starting online update check")
        val t = Thread(updater, "Online update thread")
        t.isDaemon = true
        t.start()
    }
}
