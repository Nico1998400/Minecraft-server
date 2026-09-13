package se.nordia.swedencore.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal synchronous event bus. Listeners run on the publishing thread (usually a worker thread) and must hand off
 * to the main thread themselves if they touch Bukkit state. A failing listener never affects the publisher.
 *
 * <p>Services publish only after their transaction returned successfully — never inside a transaction lambda, which
 * may be retried or rolled back.
 */
public final class DomainEvents {

    private final List<Consumer<DomainEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Logger logger;

    public DomainEvents(Logger logger) {
        this.logger = logger;
    }

    public void subscribe(Consumer<DomainEvent> listener) {
        listeners.add(listener);
    }

    public void publish(DomainEvent event) {
        for (Consumer<DomainEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Event listener failed for " + event, e);
            }
        }
    }
}
