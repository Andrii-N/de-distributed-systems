package com.distsys.replicatedlog.secondary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.distsys.replicatedlog.common.HttpSupport;
import com.distsys.replicatedlog.common.Message;
import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Secondary application HTTP adapter of the replicated log.
 * Replication logic transfered to {@class: SecondaryNode}
 * Exposes:
 *  - POST /replicate : internal endpoint called by the master to replicate a message.
 *  - GET  /messages   : returns every message replicated so far, in order.
 */
public class SecondaryApplication {

    private static final Logger log = LoggerFactory.getLogger(SecondaryApplication.class);

    private final SecondaryNode node;
    
    public SecondaryApplication(SecondaryNode node) {
        this.node = node;
    }

    public static void main (String[] args) throws IOException {
        long delayMs = HttpSupport.readDelayMs("5000");
        SecondaryApplication app = new SecondaryApplication(new SecondaryNode(delayMs));
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
        
        log.info("Secondary listening on port {} (replicationDelayMs={})", server.getAddress().getPort(), node.getReplicationDelayMs());
        return server; 
    }

    private void handleGetMessages(HttpExchange exchange) throws IOException {
        if(!("GET".equalsIgnoreCase(exchange.getRequestMethod()))) {
            HttpSupport.sendPlainText(exchange, 405, "Method not allowed");
            return;
        }

        /* One snapshot for both the log and the body, so they can't disagree */
        List<Message> snapshot = node.listMsgs();
        JsonArray jsonArray = new JsonArray();
        for (Message message : snapshot) {
            jsonArray.add(message.toJson());
        }

        log.info("Returning {} replicated message(s)", snapshot.size());
        HttpSupport.sendJson(exchange, 200, jsonArray.toString());
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

        node.receiveMessage(message);

        /* Acknowledgement send*/
        HttpSupport.sendJson(exchange, 200, message.toJson().toString());
        log.info("Acked message id={} (size={})", message.getId(), node.listMsgs().size());
    }
}
