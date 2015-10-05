package lighthouse.files

import com.sun.nio.file.SensitivityWatchEventModifier
import lighthouse.threading.AffinityExecutor
import lighthouse.utils.ThreadBox
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import kotlin.concurrent.thread

/**
 * The Java directory watching API is very low level, almost a direct translation of the underlying OS API's, so we
 * wrap it here to make it more digestable. One of the things this does is buffer up rapid sequences of notifications
 * that can be caused by file copy tools like scp.
 */
public object DirectoryWatcher {
    private val log = LoggerFactory.getLogger(DirectoryWatcher::class.java)

    @Suppress("UNCHECKED_CAST") @JvmStatic
    public fun watch(directory: Path, executor: AffinityExecutor.ServiceAffinityExecutor, onChanged: (Path, WatchEvent.Kind<Path>) -> Unit): Thread {
        return thread(start = true, daemon = true, name = "Directory watcher for $directory") {
            log.info("Starting directory watch service for $directory")

            // Apply a short delay to collapse rapid sequences of notifications together.
            class Pending(val kind: WatchEvent.Kind<Path>, val future: ScheduledFuture<*>)

            val dupeMap = ThreadBox(hashMapOf<Path, Pending>())

            try {
                val watcher = FileSystems.getDefault().newWatchService()
                directory.register(watcher, arrayOf(ENTRY_DELETE, ENTRY_CREATE, ENTRY_MODIFY), SensitivityWatchEventModifier.HIGH)
                while (!Thread.currentThread().isInterrupted) {
                    val key = watcher.take()
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind === OVERFLOW) {
                            continue
                        }
                        val ev = event as WatchEvent<Path>
                        val filename = (key.watchable() as Path).resolve(ev.context())

                        val handler = {
                            dupeMap.useWith { remove(filename) }
                            onChanged(filename, ev.kind())
                        }

                        dupeMap.use { map ->
                            val pending: Pending? = map[filename]

                            fun addToMap() {
                                map[filename] = Pending(ev.kind(), executor.executeIn(Duration.ofSeconds(1), handler))
                            }

                            if (pending == null) {
                                addToMap()
                            } else {
                                if (ev.kind() == ENTRY_MODIFY && pending.kind == ENTRY_CREATE) {
                                    // We do nothing here, as the onChanged event will prefer to see a CREATE and not
                                    // the subsequent modifies.
                                } else if (ev.kind() == ENTRY_DELETE && pending.kind == ENTRY_CREATE) {
                                    // A file created and deleted so fast can just be ignored.
                                    pending.future.cancel(false)
                                } else {
                                    // Otherwise let it override the previous pending event.
                                    pending.future.cancel(false)
                                    addToMap()
                                }
                            }
                        }
                    }
                    if (!key.reset())
                        break
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                // Shutting down ...
            }
        }
    }
}
