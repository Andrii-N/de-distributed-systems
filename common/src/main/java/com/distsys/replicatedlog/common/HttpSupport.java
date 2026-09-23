package com.distsys.replicatedlog.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;

/**
 * Helpers to facilitate HttpExchange in both master and secondary
 */

public final class HttpSupport {

    public static List<String> parseSecondaryUrls (String raw) {
        String rawUrls = System.getenv().getOrDefault("SECONDARY_URLS", raw);
        List<String> urls = Arrays.stream(rawUrls.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("SECONDARY_URLS must contain at least one URL, got: '" + rawUrls + "'");
        }
        return urls;
    }

    public static int readPort(String rawPort) {
        String raw = System.getenv().getOrDefault("PORT", rawPort).trim();
        return parsePort(raw);
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
