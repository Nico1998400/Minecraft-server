package se.nordia.plugins.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Talks to the Anthropic Messages API over plain HTTPS. The key is only ever sent in the {@code x-api-key} header. */
public final class AnthropicClient implements AiClient {

    private static final String API_VERSION = "2023-06-01";

    private final GuideConfig config;
    private final HttpClient http;

    public AnthropicClient(GuideConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build();
    }

    @Override
    public String complete(String systemPrompt, List<Turn> conversation) throws AiException {
        if (config.apiKey().isEmpty()) {
            throw new AiException("No API key configured");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("max_tokens", config.maxOutputTokens());
        body.addProperty("system", systemPrompt);
        JsonArray messages = new JsonArray();
        for (Turn turn : conversation) {
            JsonObject message = new JsonObject();
            message.addProperty("role", turn.role() == Turn.Role.USER ? "user" : "assistant");
            message.addProperty("content", turn.text());
            messages.add(message);
        }
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(config.requestTimeout())
                .header("content-type", "application/json")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AiException("AI request failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("AI request interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new AiException("AI API answered HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return parseText(response.body());
    }

    static String parseText(String json) throws AiException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            StringBuilder text = new StringBuilder();
            for (JsonElement block : root.getAsJsonArray("content")) {
                JsonObject object = block.getAsJsonObject();
                if ("text".equals(object.get("type").getAsString())) {
                    text.append(object.get("text").getAsString());
                }
            }
            String result = text.toString().strip();
            if (result.isEmpty()) {
                throw new AiException("AI answered without text");
            }
            return result;
        } catch (RuntimeException e) {
            throw new AiException("Unreadable AI response: " + abbreviate(json), e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
