package se.nordia.plugins.paper.gui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A tiny built-in web server that hands out the resource pack bundled in the plugin jar, so the server owner does not have
 * to upload it anywhere. It serves exactly one file at one path (GET only, no directories), holds the file in memory, and
 * computes the SHA-1 itself so the number can never go out of date.
 */
public final class PackHost implements AutoCloseable {

    public static final String PATH = "/nordia-pack.zip";

    private final byte[] pack;
    private final byte[] sha1;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String publicUrl;

    private PackHost(byte[] pack, byte[] sha1, HttpServer server, ExecutorService executor, String publicUrl) {
        this.pack = pack;
        this.sha1 = sha1;
        this.server = server;
        this.executor = executor;
        this.publicUrl = publicUrl;
    }

    /**
     * Starts serving {@code pack} on {@code port}.
     *
     * @param publicAddress the host name or IP players use to reach this machine (put into the download link)
     * @throws IOException if the port cannot be opened
     */
    public static PackHost start(byte[] pack, int port, String publicAddress, Logger logger) throws IOException {
        byte[] hash = sha1Of(pack);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 8);
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "SwedenCore-PackHost");
            t.setDaemon(true);
            return t;
        });
        PackHost host = new PackHost(pack, hash, server, executor,
                "http://" + publicAddress + ":" + server.getAddress().getPort() + PATH);
        server.createContext("/", host::handle);
        server.setExecutor(executor);
        server.start();
        logger.log(Level.INFO, "Texture pack is served at {0}", host.publicUrl);
        return host;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            boolean get = "GET".equals(exchange.getRequestMethod());
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
            } else if (!get) {
                exchange.sendResponseHeaders(405, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, pack.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(pack);
                }
            }
        }
    }

    public String url() {
        return publicUrl;
    }

    public byte[] sha1() {
        return sha1.clone();
    }

    /** The port the server is really listening on (useful when started with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    static byte[] sha1Of(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
