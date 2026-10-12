package se.nordia.plugins.paper.gui;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class PackHostTest {

    private final byte[] pack = "not really a zip, but bytes are bytes".getBytes();

    private HttpResponse<byte[]> call(String method, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void servesExactlyThePackAndReportsItsRealSha1() throws Exception {
        try (PackHost host = PackHost.start(pack, 0, "play.example.se", Logger.getAnonymousLogger())) {
            HttpResponse<byte[]> response = call("GET", host.port(), PackHost.PATH);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(pack);
            assertThat(HexFormat.of().formatHex(host.sha1()))
                    .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(pack)));
            assertThat(host.url()).isEqualTo("http://play.example.se:" + host.port() + "/nordia-pack.zip");
        }
    }

    @Test
    void anythingElseIsRefused() throws Exception {
        try (PackHost host = PackHost.start(pack, 0, "localhost", Logger.getAnonymousLogger())) {
            assertThat(call("GET", host.port(), "/").statusCode()).isEqualTo(404);
            assertThat(call("GET", host.port(), "/../config.yml").statusCode()).isEqualTo(404);
            assertThat(call("GET", host.port(), "/nordia-pack.zip/extra").statusCode()).isEqualTo(404);
            assertThat(call("POST", host.port(), PackHost.PATH).statusCode()).isEqualTo(405);
            assertThat(call("DELETE", host.port(), PackHost.PATH).statusCode()).isEqualTo(405);
        }
    }
}
