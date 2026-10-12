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
import java.util.Locale;

/** Talks to the Google Gemini {@code generateContent} API. The key is only ever sent in the {@code x-goog-api-key} header. */
public final class GeminiClient implements AiClient {

    private final GuideConfig config;
    private final HttpClient http;

    public GeminiClient(GuideConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build();
    }

    @Override
    public String complete(String systemPrompt, List<Turn> conversation) throws AiException {
        if (config.apiKey().isEmpty()) {
            throw new AiException("No API key configured");
        }
        JsonObject body = new JsonObject();
        body.add("system_instruction", content(null, systemPrompt));
        JsonArray contents = new JsonArray();
        for (Turn turn : conversation) {
            contents.add(content(turn.role() == Turn.Role.USER ? "user" : "model", turn.text()));
        }
        body.add("contents", contents);
        JsonObject generation = new JsonObject();
        generation.addProperty("maxOutputTokens", config.maxOutputTokens());
        if (config.model().toLowerCase(Locale.ROOT).startsWith("gemini-3")) {
            // A guide answering short questions needs no long reasoning; it only costs money and time.
            JsonObject thinking = new JsonObject();
            thinking.addProperty("thinkingLevel", "minimal");
            generation.add("thinkingConfig", thinking);
        }
        body.add("generationConfig", generation);

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(config.requestTimeout())
                .header("content-type", "application/json")
                .header("x-goog-api-key", config.apiKey())
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

    private static JsonObject content(String role, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        if (role != null) {
            content.addProperty("role", role);
        }
        content.add("parts", parts);
        return content;
    }

    static String parseText(String json) throws AiException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new AiException("AI answered without candidates (blocked?): " + abbreviate(json));
            }
            StringBuilder text = new StringBuilder();
            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            if (content != null && content.has("parts")) {
                for (JsonElement element : content.getAsJsonArray("parts")) {
                    JsonObject part = element.getAsJsonObject();
                    boolean thought = part.has("thought") && part.get("thought").getAsBoolean();
                    if (!thought && part.has("text")) {
                        text.append(part.get("text").getAsString());
                    }
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
