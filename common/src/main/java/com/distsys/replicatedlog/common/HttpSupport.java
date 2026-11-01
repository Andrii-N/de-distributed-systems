package com.distsys.replicatedlog.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

/**
 * Helpers to facilitate HttpExchange in both master and secondary
 */

public final class HttpSupport {

    /**
     * Logical name of this process (e.g. "master", "secondary1"), used as its node id in logs and by the transport
     */
    public static String readNodeId(String rawNodeId) {
        String nodeId = System.getenv().getOrDefault("NODE_ID", rawNodeId).trim();
        if (nodeId.isEmpty()) {
            throw new IllegalArgumentException("NODE_ID must not be empty");
        }
        return nodeId;
    }

    public static Map<String, String> readSecondaries(String rawSecondaries) {
        return parseSecondaries(System.getenv().getOrDefault("SECONDARY_URLS", rawSecondaries));
    }

    /**
     * Secondaries as logical name -> base URL, in configuration order.
     * Entries are comma-separated "name=url"; an entry without a name gets "secondary<N>" by its 1-based position
     */
    public static Map<String, String> parseSecondaries(String raw) {
        List<String> entries = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("SECONDARY_URLS must contain at least one secondary, got: '" + raw + "'");
        }

        /* LinkedHashMap keeps configuration order, so logs and dispatch order stay predictable */
        Map<String, String> secondaries = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            int separator = entry.indexOf('=');
            String name = separator < 0 ? "secondary" + (i + 1) : entry.substring(0, separator).trim();
            String url = separator < 0 ? entry : entry.substring(separator + 1).trim();
            if (name.isEmpty() || url.isEmpty()) {
                throw new IllegalArgumentException("SECONDARY_URLS entry must be 'name=url' or 'url', got: '" + entry + "'");
            }
            if (secondaries.putIfAbsent(name, url) != null) {
                throw new IllegalArgumentException("Duplicate secondary name in SECONDARY_URLS: '" + name + "'");
            }
        }
        return Collections.unmodifiableMap(secondaries);
    }

    public static int readPort(String rawPort) {
        String raw = System.getenv().getOrDefault("PORT", rawPort).trim();
        return parsePort(raw);
    }

    public static JsonObject parseBody(String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }

        JsonObject json = parsed.getAsJsonObject();

        if (!json.has("message") || json.get("message").isJsonNull()) {
            throw new IllegalArgumentException("Missing required field: message");
        }
        if (!json.get("message").isJsonPrimitive()) {
            throw new IllegalArgumentException("Message is not Json primitive");
        }
        return json;
    }

    public static int parsePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT must be a number, got: '" + raw + "'", e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("PORT must be between 1 and 65535, got: " + port);
        }
        return port;
    }

    public static long readDelayMs(String rawDelayMs) {
        String raw = System.getenv().getOrDefault("REPLICATION_DELAY_MS", rawDelayMs);
        
        return parseDelay(raw);
    }

    public static long parseDelay(String raw) {
        long delayMs;
        try {
            delayMs = Long.parseLong(raw);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Delay must be a number, got: '" + raw + "'", e);
        }
        if (delayMs < 0) {
            throw new IllegalArgumentException("Delay must be non-negative number");
        }
        return delayMs;
    }

    public static String readRequestBody (HttpExchange exchange) throws IOException {
        try
        (
            InputStream in = exchange.getRequestBody();
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * JSON array of messages, in list order; shared by the master and secondary GET /messages adapters
     */
    public static String toJsonArray(List<Message> messages) {
        JsonArray jsonArray = new JsonArray();
        for (Message message : messages) {
            jsonArray.add(message.toJson());
        }
        return jsonArray.toString();
    }

    public static void sendJson(HttpExchange exchange, int statusCode, String jsonBody) throws IOException {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendPlainText(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
