package com.distsys.replicatedlog.secondary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


public class SecondaryApplication {
    
    public SecondaryApplication() {}

    public static void main (String args[]) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8081"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/messages/");
        server.setExecutor(Executors.newCachedThreadPool());

        server.start();
    };
}
