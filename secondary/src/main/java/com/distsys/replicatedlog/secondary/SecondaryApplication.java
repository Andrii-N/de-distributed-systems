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
import com.google.gson.JsonSyntaxException;


public class SecondaryApplication {

    private final MessageStore messages = new MessageStore();
    
    public SecondaryApplication() {}

    public static void main (String[] args) throws IOException {
        SecondaryApplication app = new SecondaryApplication();
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

        /* Replicate message*/
        messages.append(message);

        /* Acknowledgement send*/
        HttpSupport.sendJson(exchange, 200, message.toJson().toString());
    }
}
