package lighthouse;

import lighthouse.files.*;
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

    public UserPrefs() {
        this.prefs = new Properties();
        path = AppDirectory.dir().resolve("settings.txt");
        try (InputStream stream = Files.newInputStream(path)) {
            prefs.load(stream);
        } catch (IOException e) {
            log.error("Could not load user prefs, using defaults.");
        }
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
}
