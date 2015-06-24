package lighthouse

import javafx.stage.Stage
import lighthouse.files.AppDirectory
import lighthouse.protocol.LHUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Properties

/**
 * Stores user preferences (not many currently). Access from UI thread.
 */
public class UserPrefs {
    private val log = LoggerFactory.getLogger(javaClass<UserPrefs>())

    private val prefs = Properties()
    private val path = AppDirectory.dir().resolve("settings.txt").toFile()
    public val prefsFileFound: Boolean

    init {
        try {
            path.inputStream().use { prefs.load(it) }
            prefsFileFound = true
        } catch (e: IOException) {
            log.info("Could not load user prefs, using defaults.")
            prefsFileFound = false
        }
    }

    private fun store() {
        try {
            path.outputStream().use { prefs.store(it, " Lighthouse settings file") }
        } catch (e: IOException) {
            log.error("Could not save preferences!", e)
        }
    }

    public fun getContactAddress(): String? = prefs.getProperty("contact")

    public fun setContactAddress(address: String) {
        prefs.setProperty("contact", address)
        store()
    }

    public fun getExpectedKeyDerivationTime(): Duration? {
        val time = prefs.getProperty("scryptTime") ?: return null
        val timeMsec = java.lang.Long.parseLong(time)
        return Duration.ofMillis(timeMsec)
    }

    public fun setExpectedKeyDerivationTime(time: Duration) {
        val msec = time.toMillis()
        prefs.setProperty("scryptTime", java.lang.Long.toString(msec))
        store()
    }

    public fun getCoverPhotoFolder(): Path? {
        val path = prefs.getProperty("coverPhotoFolder") ?: return null
        return Paths.get(path)
    }

    public fun setCoverPhotoFolder(path: Path) {
        prefs.setProperty("coverPhotoFolder", path.toAbsolutePath().toString())
        store()
    }

    public fun getLastRunVersion(): Int = Integer.parseInt(prefs.getProperty("lastRunVersion", "" + Main.VERSION))

    public fun setLastRunVersion(version: Int) {
        prefs.setProperty("lastRunVersion", "" + version)
        store()
    }

    private fun readDouble(name: String, defaultVal: Double) = prefs.getProperty(name, defaultVal.toString()).toDouble()

    public fun readStageSettings(stage: Stage) {
        val x = readDouble("windowX", -1.0)
        val y = readDouble("windowY", -1.0)
        val w = readDouble("windowWidth", -1.0)
        val h = readDouble("windowHeight", -1.0)
        val max = prefs.getProperty("windowMaximized", "true").toBoolean()
        if (w != -1.0 && h != -1.0 && x != -1.0 && y != -1.0) {
            stage.setWidth(w)
            stage.setHeight(h)
            stage.setX(x)
            stage.setY(y)
            if (!LHUtils.isMac())
                stage.setMaximized(max)
        } else if (!LHUtils.isMac()) {
            // First run, make maximized, but not on MacOS where the whole notion of window maximization is
            // pathologically messed up and JavaFX has some bugs around it too.
            stage.setMaximized(true)
        }
    }

    public fun storeStageSettings(stage: Stage) {
        prefs.setProperty("windowWidth", java.lang.Double.toString(stage.getWidth()))
        prefs.setProperty("windowHeight", java.lang.Double.toString(stage.getHeight()))
        prefs.setProperty("windowX", java.lang.Double.toString(stage.getX()))
        prefs.setProperty("windowY", java.lang.Double.toString(stage.getY()))
        prefs.setProperty("windowMaximized", java.lang.Boolean.toString(stage.isMaximized()))
        log.info("Storing stage metrics({})", stage.isMaximized())
        store()
    }
}
