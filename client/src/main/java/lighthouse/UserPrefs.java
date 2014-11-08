package lighthouse;

import lighthouse.files.AppDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Stores user preferences (not many currently).
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
}
