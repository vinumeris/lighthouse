package lighthouse.utils

import javafx.application.Platform
import lighthouse.protocol.LHProtos
import lighthouse.protocol.LHUtils
import lighthouse.protocol.Project
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import java.util.concurrent.CompletableFuture

public operator fun Coin.plus(other: Coin) = this.add(other)
public operator fun Coin.minus(other: Coin) = this.subtract(other)
public fun Long.asCoin(): Coin = Coin.valueOf(this)

class ThreadBox<out T>(private val data: T) {
    @Synchronized fun use<R>(block: (T) -> R): R = block(data)
    @Synchronized fun useWith<R>(block: T.() -> R): R = data.block()
}

class UIThreadBox<out T>(private val data: T) {
    fun use(block: (T) -> Unit): Unit = if (Platform.isFxApplicationThread()) block(data) else Platform.runLater { block(data) }
    fun useWith(block: T.() -> Unit): Unit = if (Platform.isFxApplicationThread()) data.block() else Platform.runLater { data.block() }

    /** Does a blocking get from the UI thread - danger of deadlock if not used properly! */
    fun getWith<R>(block: T.() -> R): R {
        if (Platform.isFxApplicationThread())
            return data.block()
        val f = CompletableFuture<R>()
        Platform.runLater {
            try {
                f.complete(data.block())
            } catch (e: Throwable) {
                f.completeExceptionally(e)
            }
        }
        return f.get()
    }
}

val LHProtos.Pledge.hash: Sha256Hash get() = LHUtils.hashFromPledge(this)
val LHProtos.Pledge.projectID: Sha256Hash get() = Sha256Hash.wrap(pledgeDetails.projectId)

val Project.hash: Sha256Hash get() = this.idHash

