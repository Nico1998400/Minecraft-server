package se.nordia.swedencore.database;

/** Unchecked wrapper for infrastructure-level database failures (not domain rule violations). */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
