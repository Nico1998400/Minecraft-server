package se.nordia.swedencore.database;

public record DatabaseConfig(
        String host,
        int port,
        String database,
        String user,
        String password,
        String sslMode,
        int poolSize,
        long connectionTimeoutMillis
) {
    public DatabaseConfig {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1");
        }
    }

    @Override
    public String toString() {
        // Never print the password.
        return "DatabaseConfig[" + user + "@" + host + ":" + port + "/" + database + ", pool=" + poolSize + "]";
    }
}
