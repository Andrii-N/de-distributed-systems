package com.distsys.replicatedlog.secondary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(SecondaryApplication.class);

    private final MessageStore messages = new MessageStore();
    private final long replicationDelayMs;
    
    public SecondaryApplication(long replicationDelayMs) {
        this.replicationDelayMs = replicationDelayMs;
    }

    public static void main (String[] args) throws IOException {
        long delayMs = HttpSupport.readDelayMs("5000");
        SecondaryApplication app = new SecondaryApplication(delayMs);
        int port = HttpSupport.readPort("8080");

        app.start(port);
    }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/replicate", this::handleReplicate);
        server.createContext("/messages", this::handleGetMessages);
        
        /* HTTP requests can run on different threads at once */
        server.setExecutor(Executors.newCachedThreadPool());
        
        server.start();
        
        log.info("Secondary listening on port {} (replicationDelayMs={})", server.getAddress().getPort(), replicationDelayMs);
        return server; 
    }

    private void handleGetMessages(HttpExchange exchange) throws IOException {
        if(!("GET".equalsIgnoreCase(exchange.getRequestMethod()))) {
            HttpSupport.sendPlainText(exchange, 405, "Method not allowed");
            return;
        }

        log.info("Returning {} replicated message(s)", messages.size());
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
            log.error("Unexpected error while handling POST /messages", e);
            HttpSupport.sendPlainText(exchange, 503, "Server unavailable: " + e.getMessage());
            return;
        }

        /* Replicate message*/
        messages.append(message);
        
        /* Delay after replication, prior to ack - so GET mid-delay includes message */
        log.info("Received message id={} for replication, simulating delay of {}ms", message.getId(), replicationDelayMs);
        sleep(replicationDelayMs);

        /* Acknowledgement send*/
        HttpSupport.sendJson(exchange, 200, message.toJson().toString());
        log.info("Acked message id={} (size={})", message.getId(), messages.size());
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
