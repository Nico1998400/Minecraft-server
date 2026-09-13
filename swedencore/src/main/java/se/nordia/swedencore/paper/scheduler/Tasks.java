package se.nordia.swedencore.paper.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.paper.text.Messages;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs blocking work (database calls) off the main thread and returns results to the main thread.
 *
 * <p>Rules: never call domain services on the main thread; never touch Bukkit state off the main thread except via
 * {@link #sync(Runnable)}. Each player may have only a few requests in flight, which stops command spam from
 * exhausting the connection pool.
 */
public final class Tasks {

    private static final int MAX_IN_FLIGHT_PER_PLAYER = 3;

    private final Plugin plugin;
    private final Messages messages;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public Tasks(Plugin plugin, Messages messages, int threads) {
        this.plugin = plugin;
        this.messages = messages;
        this.logger = plugin.getLogger();
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2_000), r -> {
            Thread t = new Thread(r, "SwedenCore-Worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs work asynchronously. The future completes on a worker thread. */
    public <T> CompletableFuture<T> async(Callable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(work.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Fire-and-forget async work; failures are logged. */
    public void async(String description, Runnable work) {
        async(() -> {
            work.run();
            return null;
        }).exceptionally(t -> {
            logger.log(Level.SEVERE, "Async task failed: " + description, unwrap(t));
            return null;
        });
    }

    public void sync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Standard command flow: run {@code work} async, then {@code onSuccess} on the main thread.
     * Domain errors are shown to the sender; unexpected errors are logged and a generic message is shown.
     */
    public <T> void run(CommandSender sender, Callable<T> work, Consumer<T> onSuccess) {
        UUID throttleKey = sender instanceof Player p ? p.getUniqueId() : null;
        if (throttleKey != null) {
            AtomicInteger count = inFlight.computeIfAbsent(throttleKey, k -> new AtomicInteger());
            if (count.incrementAndGet() > MAX_IN_FLIGHT_PER_PLAYER) {
                count.decrementAndGet();
                messages.send(sender, "error.too_many_requests");
                return;
            }
        }
        async(work).whenComplete((result, error) -> {
            if (throttleKey != null) {
                inFlight.computeIfPresent(throttleKey, (k, c) -> c.decrementAndGet() <= 0 ? null : c);
            }
            sync(() -> {
                if (error == null) {
                    onSuccess.accept(result);
                    return;
                }
                Throwable cause = unwrap(error);
                if (cause instanceof DomainException domain) {
                    messages.sendError(sender, domain);
                } else if (cause instanceof RejectedExecutionException) {
                    messages.send(sender, "error.server_busy");
                } else {
                    logger.log(Level.SEVERE, "Command failed for " + sender.getName(), cause);
                    messages.send(sender, "error.internal");
                }
            });
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warning("Worker threads did not finish within 15s; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
