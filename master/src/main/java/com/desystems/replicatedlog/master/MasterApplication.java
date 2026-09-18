package com.desystems.replicatedlog.master;

import com.desystems.replicatedlog.common.HttpSupport;
import com.desystems.replicatedlog.common.Message;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Master node of the replicated log.
 *
 * Exposes:
 *  - POST /messages : appends a message locally, replicates it to every
 *                     secondary and only responds once all secondaries ack.
 *  - GET  /messages : returns every message in the master's log.
 */
public final class MasterApplication {

    private static final Logger log = LoggerFactory.getLogger(MasterApplication.class);

    private final ReplicatedLog replicatedLog;

    public MasterApplication(ReplicatedLog replicatedLog) {
        this.replicatedLog = replicatedLog;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        List<String> secondaryUrls = parseSecondaryUrls(System.getenv().getOrDefault("SECONDARY_URLS", ""));

        if (secondaryUrls.isEmpty()) {
            log.warn("No SECONDARY_URLS configured; messages will only be appended to the master's local log");
        }

        HttpClient httpClient = HttpClient.newHttpClient();
        List<SecondaryClient> secondaries = secondaryUrls.stream()
                .map(url -> new SecondaryClient(url, url, httpClient))
                .toList();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ReplicationCoordinator coordinator = new ReplicationCoordinator(secondaries, executor);
        ReplicatedLog replicatedLog = new ReplicatedLog(coordinator);

        new MasterApplication(replicatedLog).start(port);
    }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/messages", this::handleMessages);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        log.info("Master listening on port {}", server.getAddress().getPort());
        return server;
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "POST" -> handlePost(exchange);
            case "GET" -> handleGet(exchange);
            default -> HttpSupport.sendPlainText(exchange, 405, "Method Not Allowed");
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body = HttpSupport.readBody(exchange);
        String text;
        try {
            text = new JSONObject(body).getString("message");
        } catch (Exception e) {
            HttpSupport.sendPlainText(exchange, 400, "Expected JSON body: {\"message\": \"...\"}");
            return;
        }

        long start = System.nanoTime();
        log.info("Received POST /messages: \"{}\"", text);

        Message message;
        try {
            message = replicatedLog.appendAndReplicate(text);
        } catch (SecondaryClient.ReplicationException e) {
            log.error("Replication failed", e);
            HttpSupport.sendPlainText(exchange, 502, "Replication failed: " + e.getMessage());
            return;
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("POST /messages for id={} completed in {}ms (all secondaries acked)", message.getId(), elapsedMs);

        HttpSupport.sendJson(exchange, 201, message.toJson().toString());
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        HttpSupport.sendJson(exchange, 200, replicatedLog.toJsonArray());
    }

    private static List<String> parseSecondaryUrls(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
