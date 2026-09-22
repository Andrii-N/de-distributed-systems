package com.distsys.replicatedlog.secondary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.distsys.replicatedlog.common.HttpSupport;


public class SecondaryApplication {
    
    public SecondaryApplication() {}

    public static void main (String[] args) throws IOException {
        SecondaryApplication app = new SecondaryApplication();
        int port = HttpSupport.readPort("8080");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/messages", app::handleGetMessage);
        
        /* HTTP requests can run on different threads at once */
        server.setExecutor(Executors.newCachedThreadPool());

        server.start();
    }

    private void handleGetMessage(HttpExchange exchange) throws IOException {
        if(!("GET".equalsIgnoreCase(exchange.getRequestMethod()))) {
            HttpSupport.sendPlainText(exchange, 405, "Method not allowed");
            return;
        }
        HttpSupport.sendJson(exchange, 200, "{\"status\":\"OK\"}");

    }
}
