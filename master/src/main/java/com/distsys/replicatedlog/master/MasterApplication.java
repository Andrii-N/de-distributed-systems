package com.distsys.replicatedlog.master;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.distsys.replicatedlog.common.HttpSupport;
import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.MessageStore;

public class MasterApplication {

    private final MessageStore messages = new MessageStore();
    /* Thread-safe auto-increment id */
    private final AtomicLong nextId = new AtomicLong(1);

    private final List<SecondaryClient> secondaries;

    public MasterApplication(List<SecondaryClient> secondaries) {
        this.secondaries = secondaries;
    }
    public static void main(String[] args) throws IOException {
        List<String> secondaryUrls = HttpSupport.parseSecondaryUrls("http://localhost:8081");

        HttpClient httpClient = HttpClient.newHttpClient();

        List<SecondaryClient> secondaries = secondaryUrls.stream()
            .map(url -> new SecondaryClient(url, url, httpClient))
            .toList();
        MasterApplication masterApplication = new MasterApplication(secondaries);
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
         catch (IllegalArgumentException | JsonParseException e) {
            HttpSupport.sendPlainText(exchange, 400, "Invalid request body: " + e.getMessage());
            return;
        }

        Message message = new Message(String.valueOf(nextId.getAndIncrement()),bodyText, System.currentTimeMillis());
        
        /* Stores message for further replication */
        messages.append(message);

        /* Iterating over clients to replicate */
        for(SecondaryClient secondary : secondaries) {
            try {
                secondary.replicate(message);
            }
            catch (SecondaryClient.ReplicationException e) {
                HttpSupport.sendPlainText(exchange, 502, "Replication failed: " + e.getMessage());
                return;
            }
        }
        

        /* Acknowledgement send*/
        HttpSupport.sendJson(exchange, 201, message.toJson().toString());
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        HttpSupport.sendJson(exchange, 200, messages.toJsonArray());
    }
}
