package com.distsys.replicatedlog.secondary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.distsys.replicatedlog.common.HttpSupport;
import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.MessageStore;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Secondary node of the replicated log.
 *
 * Exposes:
 *  - POST /replicate : internal endpoint called by the master to replicate a message.
 *                      Sleeps {@code REPLICATION_DELAY_MS} before acking, to make the
 *                      master's blocking replication observable.
 *  - GET  /messages   : returns every message replicated so far, in order.
 */
public class SecondaryApplication {

    private final MessageStore messages = new MessageStore();
    private final long replicationDelayMs;
    
    public SecondaryApplication(long replicationDelayMs) {
        this.replicationDelayMs = replicationDelayMs;
    }

    public static void main (String[] args) throws IOException {
        long delayMs = HttpSupport.readDelayMs("5000");
        SecondaryApplication app = new SecondaryApplication(delayMs);
        int port = HttpSupport.readPort("8080");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/replicate", app::handleReplicate);
        server.createContext("/messages", app::handleGetMessages);
        
        /* HTTP requests can run on different threads at once */
        server.setExecutor(Executors.newCachedThreadPool());

        server.start();
    }

    private void handleGetMessages(HttpExchange exchange) throws IOException {
        if(!("GET".equalsIgnoreCase(exchange.getRequestMethod()))) {
            HttpSupport.sendPlainText(exchange, 405, "Method not allowed");
            return;
        }
        HttpSupport.sendJson(exchange, 200, messages.toJsonArray());
    }

    private void handleReplicate(HttpExchange exchange) throws IOException {
        if(!("POST".equalsIgnoreCase(exchange.getRequestMethod()))) {
            HttpSupport.sendPlainText(exchange, 405, "Method not allowed");
            return;
        }
        String body = HttpSupport.readRequestBody(exchange);
        Message message;
        try {
            message = Message.fromJson(JsonParser.parseString(body).getAsJsonObject());
        }
        catch (JsonParseException | IllegalArgumentException e) {
            HttpSupport.sendPlainText(exchange, 400, "Invalid request body: " + e.getMessage());
            return;
        }
        catch(RuntimeException e) {
            HttpSupport.sendPlainText(exchange, 503, "Server unavailable: " + e.getMessage());
            return;
        }

        /* Replicate message*/
        messages.append(message);

        /* Delay after replication, prior to ack - so GET mid-delay includes message */
        sleep(replicationDelayMs);

        /* Acknowledgement send*/
        HttpSupport.sendJson(exchange, 200, message.toJson().toString());
    }

    private static void sleep(long milliseconds) {
        if (milliseconds <= 0) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        }
        catch(InterruptedException e) {
            /* Restore interrupted indication before doing anything else */
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while simulating replication delay", e); 
        }
    }
}
