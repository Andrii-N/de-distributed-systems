package com.distsys.replicatedlog.secondary;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;

import com.distsys.replicatedlog.common.Message;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SecondaryApplicationTest {

    private HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void replicateMessagesAndListInArrivingOrder() throws IOException, InterruptedException {
        server = new SecondaryApplication(0).start(0);
        int port = server.getAddress().getPort();

        replicate(port, new Message("1","first", 1234L));
        replicate(port, new Message("2","second", 12345L));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonArray messages = JsonParser.parseString(response.body()).getAsJsonArray();
        assertEquals(2, messages.size());
        assertEquals("first", messages.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("second", messages.get(1).getAsJsonObject().get("text").getAsString());
    }

    private void replicate(int port, Message message) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/replicate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(message.toJson().toString()))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(200, response.statusCode());
        assertEquals(message.getText(), responseJson.get("text").getAsString());
    }
}
