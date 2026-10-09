package com.myfeelings.diary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Talks to a local Ollama instance over HTTP. Nothing leaves the machine.
 *
 * <p>Entry text is never logged: only lengths and durations.
 */
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    /** A 7B model on CPU can take minutes on a long diary period. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Keeps the model resident between requests, so only the first summary of the day pays for loading it. */
    private static final String KEEP_ALIVE = "30m";

    private static final double TEMPERATURE = 0.3;

    private final Config config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public OllamaClient(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** The assistant's reply, and whether the model stopped on the context limit before finishing. */
    public record Reply(String text, boolean truncated) {
    }

    /**
     * Sends one non-streaming chat request and returns the assistant's reply.
     *
     * <p>{@code num_ctx} and {@code temperature} go inside {@code options}: at the top level Ollama
     * silently ignores them and falls back to the model's default context window, which truncates
     * long input without any error.
     */
    public Reply chat(String systemPrompt, String userPrompt) throws OllamaException {
        ObjectNode body = json.createObjectNode();
        body.put("model", config.ollamaModel());
        body.put("stream", false);
        body.put("keep_alive", KEEP_ALIVE);

        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        ObjectNode options = body.putObject("options");
        options.put("num_ctx", config.ollamaNumCtx());
        options.put("temperature", TEMPERATURE);

        log.info("Ollama request: model={}, num_ctx={}, prompt chars={}",
                config.ollamaModel(), config.ollamaNumCtx(), systemPrompt.length() + userPrompt.length());
        long startedAt = System.nanoTime();

        JsonNode response = post("/api/chat", body);
        JsonNode content = response.at("/message/content");
        if (content.isMissingNode() || !content.isTextual()) {
            throw new OllamaException("Unexpected response from Ollama: no /message/content field");
        }

        String reply = content.asText();
        long seconds = Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();
        log.info("Ollama replied in {}s, {} chars", seconds, reply.length());
        boolean truncated = response.path("done_reason").asText("").equals("length");
        if (truncated) {
            log.warn("Ollama stopped on the context limit; the reply is cut off. "
                    + "Raise OLLAMA_NUM_CTX or shorten the period.");
        }
        return new Reply(reply, truncated);
    }

    /** Model names known to the local Ollama instance, e.g. {@code qwen2.5:7b}. */
    public List<String> listModels() throws OllamaException {
        JsonNode response = get("/api/tags");
        List<String> names = new ArrayList<>();
        for (JsonNode model : response.path("models")) {
            names.add(model.path("name").asText());
        }
        return names;
    }

    /**
     * Checks that Ollama is reachable and the configured model is pulled. Returns a human-readable
     * verdict instead of throwing, because the bot must keep running without Ollama.
     */
    public String describeAvailability() {
        try {
            List<String> models = listModels();
            if (models.stream().anyMatch(this::matchesConfiguredModel)) {
                return "Ollama at " + config.ollamaUrl() + " has model " + config.ollamaModel();
            }
            return "Ollama at " + config.ollamaUrl() + " is up, but model " + config.ollamaModel()
                    + " is not pulled. Available: " + (models.isEmpty() ? "(none)" : String.join(", ", models))
                    + ". Run: ollama pull " + config.ollamaModel();
        } catch (OllamaException e) {
            return "Ollama at " + config.ollamaUrl() + " is unreachable: " + e.getMessage();
        }
    }

    /** Ollama reports a tag-less model as {@code name:latest}, so compare both spellings. */
    private boolean matchesConfiguredModel(String name) {
        String configured = config.ollamaModel();
        return name.equals(configured)
                || name.equals(configured + ":latest")
                || (configured.endsWith(":latest")
                        && name.equals(configured.substring(0, configured.length() - ":latest".length())));
    }

    private JsonNode get(String path) throws OllamaException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return send(request, path);
    }

    private JsonNode post(String path, ObjectNode body) throws OllamaException {
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new OllamaException("Could not serialize the request", e);
        }
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        return send(request, path);
    }

    private JsonNode send(HttpRequest request, String path) throws OllamaException {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new OllamaException("the request timed out after " + REQUEST_TIMEOUT.toMinutes() + " minutes", e);
        } catch (IOException e) {
            throw new OllamaException("could not reach " + config.ollamaUrl() + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("the request was interrupted", e);
        }

        if (response.statusCode() != 200) {
            String message = new String(response.body(), StandardCharsets.UTF_8);
            throw new OllamaException("HTTP " + response.statusCode() + " from " + path + ": " + message);
        }
        try {
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new OllamaException("could not parse the response from " + path, e);
        }
    }

    private URI uri(String path) {
        String base = config.ollamaUrl().endsWith("/")
                ? config.ollamaUrl().substring(0, config.ollamaUrl().length() - 1)
                : config.ollamaUrl();
        return URI.create(base + path);
    }

    /** Checked on purpose: every call site has to decide what to tell the user. */
    public static class OllamaException extends Exception {
        public OllamaException(String message) {
            super(message);
        }

        public OllamaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
