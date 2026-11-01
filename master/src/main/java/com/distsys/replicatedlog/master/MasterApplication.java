package com.distsys.replicatedlog.master;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonParseException;
import com.distsys.replicatedlog.common.HttpSupport;
import com.distsys.replicatedlog.common.Message;
import com.distsys.replicatedlog.common.ReplicationException;

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

    private final MasterNode masterNode;
    
    public MasterApplication(MasterNode masterNode) {
        this.masterNode = masterNode;
    }

    public static void main(String[] args) throws IOException {
        /* Logical name of this node */
        String nodeId = HttpSupport.readNodeId("master");
        Map<String, String> secondaryUrls = HttpSupport.readSecondaries("http://localhost:8081");

        HttpClient httpClient = HttpClient.newHttpClient();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        /* Node id -> client; clients keyed by node id; HttpTransport routes by these ids */
        Map<String, SecondaryClient> secondaries = new LinkedHashMap<>();
        secondaryUrls.forEach((name, url) -> secondaries.put(name, new SecondaryClient(name, url, httpClient)));

        HttpTransport httpTransport = new HttpTransport(secondaries, executor);
        ReplicationCoordinator coordinator = new ReplicationCoordinator(httpTransport, nodeId, List.copyOf(secondaries.keySet()));
        MasterNode masterNode = new MasterNode(coordinator);
        MasterApplication masterApplication = new MasterApplication(masterNode);
        int port = HttpSupport.readPort("8080"); 
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/messages", masterApplication::handleMessages);
        
        /* HTTP requests can run on different threads at once */
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        log.info("Master {} listening on port {}, secondaries {}", nodeId, server.getAddress().getPort(), secondaryUrls);
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "POST" -> handlePost(exchange);
            case "GET" -> handleGet(exchange);
            default -> HttpSupport.sendPlainText(exchange, 405, "Method Not Allowed");
        }
    }


    private void handlePost(HttpExchange exchange) throws IOException {
        log.info("Received POST /messages");
        String body = HttpSupport.readRequestBody(exchange);
        String bodyText;
        try {
            
            bodyText = HttpSupport.parseBody(body).get("message").getAsString();
        }
         catch(IllegalArgumentException | JsonParseException e) {
            HttpSupport.sendPlainText(exchange, 400, "Invalid request body: " + e.getMessage());
            return;
        }
        
        Message message;
        try {
            message = masterNode.appendAndReplicate(bodyText);
        }
        catch (ReplicationException e) {
            log.error("Replcation failed for POST /messages", e);
            HttpSupport.sendPlainText(exchange, 502, "Replication failed: " + e.getMessage());
            return;
        }
        catch(RuntimeException e) {
            log.error("Unexpected error while handling POST /messages", e);
            HttpSupport.sendPlainText(exchange, 500, "Unexpected error");
            return;
        }

        log.info("POST /message for id = {} completed (all secondaries acked)", message.getId());
        
        /* Acknowledgement send*/
        HttpSupport.sendJson(exchange, 201, message.toJson().toString());
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        HttpSupport.sendJson(exchange, 200, masterNode.toJsonArray());
    }
}
