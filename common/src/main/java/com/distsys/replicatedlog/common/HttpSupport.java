package com.distsys.replicatedlog.common;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;

/**
 * Helpers to facilitate HttpExchange in both master and secondary
 */

public final class HttpSupport {

    public static int readPort(String rawPort) {
        String raw = System.getenv().getOrDefault("PORT", rawPort).trim();
        return parsePort(raw);
    }

    public static int parsePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT must be a number, got: '" + raw + "'", e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("PORT must be between 1 and 65535, got: " + port);
        }
        return port;
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
