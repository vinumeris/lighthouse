package lighthouse.files;

import com.google.common.collect.ImmutableSet;
import com.sun.nio.file.SensitivityWatchEventModifier;
import lighthouse.protocol.LHUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * The Java directory watching API is very low level, almost a direct translation of the underlying OS API's, so we
 * wrap it here to make it more digestable.
 */
public class DirectoryWatcher {
    private static final Logger log = LoggerFactory.getLogger(DirectoryWatcher.class);
    private final Thread thread;

    public DirectoryWatcher(ImmutableSet<Path> directories, BiConsumer<Path, WatchEvent.Kind<Path>> onChanged, @Nullable Executor executor) {
        if (directories.isEmpty()) {
            thread = null;
            return;
        }
        log.info("Starting directory watch service for {}", directories);
        thread = new Thread(() -> {
            try {
                WatchService watcher = FileSystems.getDefault().newWatchService();
                for (Path directory : directories) {
                    directory.register(watcher, new WatchEvent.Kind[]{ENTRY_DELETE, ENTRY_CREATE, ENTRY_MODIFY},
                            SensitivityWatchEventModifier.HIGH);
                    log.info("Files in {}: {}", directory, LHUtils.listDir(directory));
                }
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watcher.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == OVERFLOW) {
                            continue;
                        }
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path filename = ((Path)key.watchable()).resolve(ev.context());
                        if (executor != null)
                            executor.execute(() -> onChanged.accept(filename, ev.kind()));
                        else
                            onChanged.accept(filename, ev.kind());
                    }
                    if (!key.reset())
                        break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                // Shutting down ...
            }
        }, "Directory watcher");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (thread != null) thread.interrupt();
    }
}
