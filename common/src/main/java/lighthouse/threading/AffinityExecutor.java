package lighthouse.threading;

import com.google.common.util.concurrent.*;
import javafx.application.*;
import lighthouse.protocol.*;
import org.bitcoinj.core.*;
import org.slf4j.*;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static com.google.common.base.Preconditions.*;
import static lighthouse.protocol.LHUtils.*;

/** An extended executor interface that supports thread affinity assertions and short circuiting. */
public interface AffinityExecutor extends Executor {
    /** Returns true if the current thread is equal to the thread this executor is backed by. */
    boolean isOnThread();
    /** Throws an IllegalStateException if the current thread is equal to the thread this executor is backed by. */
    void checkOnThread();
    /** If isOnThread() then runnable is invoked immediately, otherwise the closure is queued onto the backing thread. */
    void executeASAP(LHUtils.UncheckedRunnable runnable);

    /**
     * Runs the given function on the executor, blocking until the result is available. Be careful not to deadlock this
     * way! Make sure the executor can't possibly be waiting for the calling thread.
     */
    default <T> T fetchFrom(Supplier<T> fetcher) {
        if (isOnThread())
            return fetcher.get();
        else
            return checkedGet(CompletableFuture.supplyAsync(fetcher, this));
    }

    abstract class BaseAffinityExecutor implements AffinityExecutor {
        protected final Thread.UncaughtExceptionHandler exceptionHandler;

        protected BaseAffinityExecutor() {
            exceptionHandler = Thread.currentThread().getUncaughtExceptionHandler();
        }

        @Override
        public abstract boolean isOnThread();

        @Override
        public void checkOnThread() {
            checkState(isOnThread(), "On wrong thread: %s", Thread.currentThread());
        }

        @Override
        public void executeASAP(LHUtils.UncheckedRunnable runnable) {
            final Runnable command = () -> {
                try {
                    runnable.run();
                } catch (Throwable throwable) {
                    exceptionHandler.uncaughtException(Thread.currentThread(), throwable);
                }
            };
            if (isOnThread())
                command.run();
            else {
                execute(command);
            }
        }

        // Must comply with the Executor definition w.r.t. exceptions here.
        @Override
        public abstract void execute(Runnable command);
    }

    AffinityExecutor UI_THREAD = new BaseAffinityExecutor() {
        @Override
        public boolean isOnThread() {
            return Platform.isFxApplicationThread();
        }

        @Override
        public void execute(Runnable command) {
            Platform.runLater(command);
        }
    };

    AffinityExecutor SAME_THREAD = new BaseAffinityExecutor() {
        @Override
        public boolean isOnThread() {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    class ServiceAffinityExecutor extends BaseAffinityExecutor {
        private static final Logger log = LoggerFactory.getLogger(ServiceAffinityExecutor.class);

        protected AtomicReference<Thread> whichThread = new AtomicReference<>(null);
        private final Thread.UncaughtExceptionHandler handler = Thread.currentThread().getUncaughtExceptionHandler();
        public final ScheduledThreadPoolExecutor service;

        public ServiceAffinityExecutor(String threadName) {
            Context bcjContext = Context.get();
            service = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(() -> {
                    Context.propagate(bcjContext);
                    runnable.run();
                });
                thread.setDaemon(true);
                thread.setName(threadName);
                whichThread.set(thread);
                return thread;
            }, (runnable, executor) -> {
                // log.error("Ignored execution attempt due to shutdown", new Throwable());
            });
        }

        @Override
        public boolean isOnThread() {
            return Thread.currentThread() == whichThread.get();
        }

        @Override
        public void execute(Runnable command) {
            service.execute(() -> {
                try {
                    command.run();
                } catch (Throwable e) {
                    if (handler != null)
                        handler.uncaughtException(Thread.currentThread(), e);
                    else
                        e.printStackTrace();
                }
            });
        }

        public <T> ScheduledFuture<T> executeIn(Duration time, Callable<T> command) {
            return service.schedule(command::call, time.toMillis(), TimeUnit.MILLISECONDS);
        }

        public ScheduledFuture<?> executeIn(Duration time, Runnable runnable) {
            return service.schedule(() -> {
                try {
                    runnable.run();
                } catch (Throwable e) {
                    if (handler != null)
                        handler.uncaughtException(Thread.currentThread(), e);
                    else
                        e.printStackTrace();
                }
            }, time.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * An executor useful for unit tests: allows the current thread to block until a command arrives from another
     * thread, which is then executed. Inbound closures/commands stack up until they are cleared by looping.
     */
    class Gate extends BaseAffinityExecutor {
        private final Thread thisThread = Thread.currentThread();
        private final LinkedBlockingQueue<Runnable> commandQ = new LinkedBlockingQueue<>();
        private final boolean alwaysQueue;

        public Gate() {
            this(false);
        }

        /** If alwaysQueue is true, executeASAP will never short-circuit and will always queue up. */
        public Gate(boolean alwaysQueue) {
            this.alwaysQueue = alwaysQueue;
        }

        @Override
        public boolean isOnThread() {
            return !alwaysQueue && Thread.currentThread() == thisThread;
        }

        @Override
        public void execute(Runnable command) {
            Uninterruptibles.putUninterruptibly(commandQ, command);
        }

        public void waitAndRun() {
            final Runnable runnable = Uninterruptibles.takeUninterruptibly(commandQ);
            runnable.run();
        }

        public int getTaskQueueSize() {
            return commandQ.size();
        }
    }
}
