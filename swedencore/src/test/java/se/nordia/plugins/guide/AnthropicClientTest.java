package se.nordia.plugins.guide;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real HTTP client against a local fake of the Messages API. */
class AnthropicClientTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private final AtomicReference<String> versionHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{\"content\":[{\"type\":\"text\",\"text\":\"Hej från Brage\"}]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            versionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
        return new AnthropicClient(new GuideConfig(true, "Brage", "sk-test-key", "claude-haiku-5-5", url, 300, 10, 300,
                Duration.ofSeconds(1), 100, Duration.ofSeconds(5)));
    }

    @Test
    void sendsModelSystemPromptAndConversationWithTheKeyInTheHeaderOnly() throws Exception {
        String answer = client().complete("Du är Brage", List.of(
                new AiClient.Turn(AiClient.Turn.Role.USER, "Hej å ö \"citat\""),
                new AiClient.Turn(AiClient.Turn.Role.ASSISTANT, "Hej!"),
                new AiClient.Turn(AiClient.Turn.Role.USER, "Och nu?")));
        assertThat(answer).isEqualTo("Hej från Brage");
        assertThat(apiKeyHeader.get()).isEqualTo("sk-test-key");
        assertThat(versionHeader.get()).isEqualTo("2023-06-01");
        JsonObject sent = JsonParser.parseString(body.get()).getAsJsonObject();
        assertThat(sent.get("model").getAsString()).isEqualTo("claude-haiku-5-5");
        assertThat(sent.get("max_tokens").getAsInt()).isEqualTo(300);
        assertThat(sent.get("system").getAsString()).isEqualTo("Du är Brage");
        assertThat(sent.getAsJsonArray("messages")).hasSize(3);
        assertThat(sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString())
                .isEqualTo("Hej å ö \"citat\"");
        assertThat(body.get()).doesNotContain("sk-test-key");
    }

    @Test
    void httpErrorsBecomeAiExceptions() {
        status = 401;
        response = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
        assertThatThrownBy(() -> client().complete("s", List.of(new AiClient.Turn(AiClient.Turn.Role.USER, "hej"))))
                .isInstanceOf(AiClient.AiException.class).hasMessageContaining("401");
    }

    @Test
    void garbageOrEmptyAnswersBecomeAiExceptions() {
        response = "not json";
        assertThatThrownBy(() -> client().complete("s", List.of(new AiClient.Turn(AiClient.Turn.Role.USER, "hej"))))
                .isInstanceOf(AiClient.AiException.class);
        response = "{\"content\":[]}";
        assertThatThrownBy(() -> client().complete("s", List.of(new AiClient.Turn(AiClient.Turn.Role.USER, "hej"))))
                .isInstanceOf(AiClient.AiException.class);
    }

    @Test
    void noKeyMeansNoRequest() {
        AnthropicClient noKey = new AnthropicClient(GuideConfig.defaults());
        assertThatThrownBy(() -> noKey.complete("s", List.of(new AiClient.Turn(AiClient.Turn.Role.USER, "hej"))))
                .isInstanceOf(AiClient.AiException.class);
        assertThat(body.get()).isNull();
    }
}
