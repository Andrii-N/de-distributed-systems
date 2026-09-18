package com.desystems.replicatedlog.secondary;

import com.desystems.replicatedlog.common.HttpSupport;
import com.desystems.replicatedlog.common.Message;
import com.desystems.replicatedlog.common.MessageStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Secondary node of the replicated log.
 *
 * Exposes:
 *  - POST /replicate : internal endpoint called by the master to replicate a message.
 *                      Sleeps {@code REPLICATION_DELAY_MS} before acking, to make the
 *                      master's blocking replication observable.
 *  - GET  /messages   : returns every message replicated so far, in order.
 */
public final class SecondaryApplication {

    private static final Logger log = LoggerFactory.getLogger(SecondaryApplication.class);

    private final MessageStore store = new MessageStore();
    private final long replicationDelayMs;

    public SecondaryApplication(long replicationDelayMs) {
        this.replicationDelayMs = replicationDelayMs;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        long delayMs = Long.parseLong(System.getenv().getOrDefault("REPLICATION_DELAY_MS", "0"));

        new SecondaryApplication(delayMs).start(port);
    }

    /**
     * Starts the HTTP server on the given port (0 = pick a free ephemeral port,
     * useful in tests) and returns it so callers can inspect the bound port or
     * stop it later.
     */
    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/replicate", this::handleReplicate);
        server.createContext("/messages", this::handleGetMessages);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        log.info("Secondary listening on port {} (replicationDelayMs={})", server.getAddress().getPort(), replicationDelayMs);
        return server;
    }

    private void handleReplicate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpSupport.sendPlainText(exchange, 405, "Method Not Allowed");
            return;
        }

        String body = HttpSupport.readBody(exchange);
        Message message = Message.fromJson(new JSONObject(body));

        log.info("Received message id={} for replication, simulating delay of {}ms", message.getId(), replicationDelayMs);
        sleep(replicationDelayMs);

        store.append(message);
        log.info("Acked message id={} (store size={})", message.getId(), store.size());

        HttpSupport.sendJson(exchange, 200, message.toJson().toString());
    }

    private void handleGetMessages(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpSupport.sendPlainText(exchange, 405, "Method Not Allowed");
            return;
        }

        log.info("Returning {} replicated message(s)", store.size());
        HttpSupport.sendJson(exchange, 200, store.toJsonArray());
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while simulating replication delay", e);
        }
    }
}
