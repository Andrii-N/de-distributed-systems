package com.distsys.replicatedlog.master;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.distsys.replicatedlog.common.HttpSupport;
import com.distsys.replicatedlog.common.Message;

/**
 * Master node of the replicated log.
 *
 * Exposes:
 *  - POST /messages : appends a message locally, replicates it to every
 *                     secondary and only responds once all secondaries ack.
 *  - GET  /messages : returns every message in the master's log.
 */
public final class MasterApplication {

    private final ReplicatedLog replicatedLog;
    
    public MasterApplication(ReplicatedLog replicatedLog) {
        this.replicatedLog = replicatedLog;
    }

    public static void main(String[] args) throws IOException {
        List<String> secondaryUrls = HttpSupport.parseSecondaryUrls("http://localhost:8081");
        HttpClient httpClient = HttpClient.newHttpClient();
        List<SecondaryClient> secondaries = secondaryUrls.stream()
            .map(url -> new SecondaryClient(url, url, httpClient))
            .toList();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ReplicationCoordinator coordinator = new ReplicationCoordinator(secondaries, executor);
        ReplicatedLog replicatedLog = new ReplicatedLog(coordinator);
        MasterApplication masterApplication = new MasterApplication(replicatedLog);
        int port = HttpSupport.readPort("8080"); 
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/messages", masterApplication::handleMessages);
        
        /* HTTP requests can run on different threads at once */
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "POST" -> handlePost(exchange);
            case "GET" -> handleGet(exchange);
            default -> HttpSupport.sendPlainText(exchange, 405, "Method Not Allowed");
        }
    }


    private void handlePost(HttpExchange exchange) throws IOException {
        String body = HttpSupport.readRequestBody(exchange);
        String bodyText;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (!json.has("message") || json.get("message").isJsonNull()) {
                throw new IllegalArgumentException("Missing required field: message");
            }
            
            bodyText = json.get("message").getAsString();

        }
         catch(IllegalArgumentException | JsonParseException e) {
            HttpSupport.sendPlainText(exchange, 400, "Invalid request body: " + e.getMessage());
            return;
        }
        
        Message message;
        try {
            message = replicatedLog.appendAndReplicate(bodyText);
        }
        catch (SecondaryClient.ReplicationException e) {
            HttpSupport.sendPlainText(exchange, 502, "Replication failed: " + e.getMessage());
            return;
        }
        catch(RuntimeException e) {
            HttpSupport.sendPlainText(exchange, 500, "Unexpected error");
            return;
        }
        /* Acknowledgement send*/
        HttpSupport.sendJson(exchange, 201, message.toJson().toString());
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        HttpSupport.sendJson(exchange, 200, replicatedLog.toJsonArray());
    }
}
