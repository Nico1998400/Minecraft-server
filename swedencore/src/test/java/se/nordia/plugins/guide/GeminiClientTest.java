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

/** Runs the real HTTP client against a local fake of the Gemini API. */
class GeminiClientTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> keyHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hej från Brage\"}]}}]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
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

    private GeminiClient client(String model) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/x:generateContent";
        return new GeminiClient(new GuideConfig(true, "Brage", "AQ.test-key", model, url, 300, 10, 300,
                Duration.ofSeconds(1), 100, Duration.ofSeconds(5)));
    }

    private static List<AiClient.Turn> hello() {
        return List.of(new AiClient.Turn(AiClient.Turn.Role.USER, "Hej å \"ö\""),
                new AiClient.Turn(AiClient.Turn.Role.ASSISTANT, "Hej!"),
                new AiClient.Turn(AiClient.Turn.Role.USER, "Och nu?"));
    }

    @Test
    void sendsSystemPromptAndRolesWithTheKeyInTheHeaderOnly() throws Exception {
        assertThat(client("gemini-3.1-flash-lite").complete("Du är Brage", hello())).isEqualTo("Hej från Brage");
        assertThat(keyHeader.get()).isEqualTo("AQ.test-key");
        JsonObject sent = JsonParser.parseString(body.get()).getAsJsonObject();
        assertThat(sent.getAsJsonObject("system_instruction").getAsJsonArray("parts").get(0).getAsJsonObject()
                .get("text").getAsString()).isEqualTo("Du är Brage");
        assertThat(sent.getAsJsonArray("contents")).hasSize(3);
        assertThat(sent.getAsJsonArray("contents").get(1).getAsJsonObject().get("role").getAsString()).isEqualTo("model");
        assertThat(sent.getAsJsonObject("generationConfig").get("maxOutputTokens").getAsInt()).isEqualTo(300);
        assertThat(sent.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("thinkingLevel")
                .getAsString()).isEqualTo("minimal");
        assertThat(body.get()).doesNotContain("AQ.test-key");
    }

    @Test
    void olderModelsGetNoThinkingSetting() throws Exception {
        client("gemini-2.5-flash-lite").complete("s", hello());
        assertThat(JsonParser.parseString(body.get()).getAsJsonObject().getAsJsonObject("generationConfig").has("thinkingConfig"))
                .isFalse();
    }

    @Test
    void thoughtPartsAreNeverShown() throws Exception {
        response = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hemlig tanke\",\"thought\":true},{\"text\":\"Svar\"}]}}]}";
        assertThat(client("gemini-3.1-flash-lite").complete("s", hello())).isEqualTo("Svar");
    }

    @Test
    void errorsBlockedAndEmptyAnswersBecomeAiExceptions() {
        status = 400;
        response = "{\"error\":{\"code\":400,\"message\":\"API key not valid\"}}";
        assertThatThrownBy(() -> client("gemini-3.1-flash-lite").complete("s", hello()))
                .isInstanceOf(AiClient.AiException.class).hasMessageContaining("400");
        status = 200;
        response = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
        assertThatThrownBy(() -> client("gemini-3.1-flash-lite").complete("s", hello())).isInstanceOf(AiClient.AiException.class);
        response = "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}";
        assertThatThrownBy(() -> client("gemini-3.1-flash-lite").complete("s", hello())).isInstanceOf(AiClient.AiException.class);
    }

    @Test
    void theModelNamePicksTheProviderAndEndpoint() {
        GuideConfig gemini = new GuideConfig(true, "B", "k", "gemini-3.1-flash-lite", null, 300, 10, 300, Duration.ofSeconds(1), 1, Duration.ofSeconds(1));
        GuideConfig claude = new GuideConfig(true, "B", "k", "claude-haiku-5-5", null, 300, 10, 300, Duration.ofSeconds(1), 1, Duration.ofSeconds(1));
        assertThat(gemini.provider()).isEqualTo(GuideConfig.Provider.GEMINI);
        assertThat(gemini.endpoint()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");
        assertThat(claude.provider()).isEqualTo(GuideConfig.Provider.ANTHROPIC);
        assertThat(claude.endpoint()).isEqualTo(GuideConfig.ANTHROPIC_URL);
        assertThat(AiClient.create(gemini)).isInstanceOf(GeminiClient.class);
        assertThat(AiClient.create(claude)).isInstanceOf(AnthropicClient.class);
    }
}
