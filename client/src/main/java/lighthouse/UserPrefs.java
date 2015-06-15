package lighthouse;

import javafx.stage.*;
import lighthouse.files.*;
import lighthouse.protocol.*;
import org.slf4j.*;

import javax.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Stores user preferences (not many currently). Access from UI thread.
 */
public class UserPrefs {
    private static final Logger log = LoggerFactory.getLogger(UserPrefs.class);

    private Properties prefs;
    private final Path path;

    public final boolean prefsFileFound;

    public UserPrefs() {
        this.prefs = new Properties();
        path = AppDirectory.dir().resolve("settings.txt");
        boolean found = false;
        try (InputStream stream = Files.newInputStream(path)) {
            prefs.load(stream);
            found = true;
        } catch (IOException e) {
            log.info("Could not load user prefs, using defaults.");
        }
        prefsFileFound = found;
    }

    private void store() {
        try (OutputStream stream = Files.newOutputStream(path)) {
            prefs.store(stream, " Lighthouse settings file");
        } catch (IOException e) {
            log.error("Could not save preferences!", e);
        }
    }

    @Nullable
    public String getContactAddress() {
        return prefs.getProperty("contact");
    }

    public void setContactAddress(String address) {
        prefs.setProperty("contact", address);
        store();
    }

    @Nullable
    public Duration getExpectedKeyDerivationTime() {
        String time = prefs.getProperty("scryptTime");
        if (time == null)
            return null;
        long timeMsec = Long.parseLong(time);
        return Duration.ofMillis(timeMsec);
    }

    public void setExpectedKeyDerivationTime(Duration time) {
        long msec = time.toMillis();
        prefs.setProperty("scryptTime", Long.toString(msec));
        store();
    }

    @Nullable
    public Path getCoverPhotoFolder() {
        String path = prefs.getProperty("coverPhotoFolder");
        if (path == null) return null;
        return Paths.get(path);
    }

    public void setCoverPhotoFolder(Path path) {
        prefs.setProperty("coverPhotoFolder", path.toAbsolutePath().toString());
        store();
    }

    public int getLastRunVersion() {
        return Integer.parseInt(prefs.getProperty("lastRunVersion", "" + Main.VERSION));
    }

    public void setLastRunVersion(int version) {
        prefs.setProperty("lastRunVersion", "" + version);
        store();
    }

    private double readDouble(String name, double defaultVal) {
        return Double.parseDouble(prefs.getProperty(name, Double.toString(defaultVal)));
    }

    public void readStageSettings(Stage stage) {
        double x = readDouble("windowX", -1);
        double y = readDouble("windowY", -1);
        double w = readDouble("windowWidth", -1);
        double h = readDouble("windowHeight", -1);
        boolean max = Boolean.parseBoolean(prefs.getProperty("windowMaximized", "true"));
        if (w != -1 && h != -1 && x != -1 && y != -1) {
            stage.setWidth(w);
            stage.setHeight(h);
            stage.setX(x);
            stage.setY(y);
            if (!LHUtils.isMac())
                stage.setMaximized(max);
        } else if (!LHUtils.isMac()) {
            // First run, make maximized, but not on MacOS where the whole notion of window maximization is
            // pathologically messed up and JavaFX has some bugs around it too.
            stage.setMaximized(true);
        }
    }

    public void storeStageSettings(Stage stage) {
        prefs.setProperty("windowWidth", Double.toString(stage.getWidth()));
        prefs.setProperty("windowHeight", Double.toString(stage.getHeight()));
        prefs.setProperty("windowX", Double.toString(stage.getX()));
        prefs.setProperty("windowY", Double.toString(stage.getY()));
        prefs.setProperty("windowMaximized", Boolean.toString(stage.isMaximized()));
        log.info("Storing stage metrics({})", stage.isMaximized());
        store();
    }
}
