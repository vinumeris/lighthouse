package lighthouse.utils

import com.vinumeris.crashfx.CrashWindow
import javafx.application.Platform
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.buildDispatcher
import nl.komponents.kovenant.jvm.asDispatcher
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor


val ui = Kovenant.createContext {
    workerContext.dispatcher = buildDispatcher {
        name = "misc"
        exceptionHandler = { ex -> CrashWindow.open(ex) }
    }
    callbackContext.dispatcher = Executor { Platform.runLater(it) }.asDispatcher()
}

val log = LoggerFactory.getLogger("Stopwatch")
inline fun <T> timeIt(name: String, b: () -> T): T {
    val now = System.currentTimeMillis()
    val r = b()
    log.info("$name took ${System.currentTimeMillis() - now} msec")
    return r
}

fun <T : Any, E : Throwable> Promise<T, E>.workingGet(): T {
    val latch = CountDownLatch(1)
    var result: T? = null
    var ex: Throwable? = null
    this.success { result = it }
    this.fail { ex = it }
    this.always { latch.countDown() }
    latch.await()
    if (ex != null) throw ex!!
    return result!!
}