package com.desystems.replicatedlog.secondary;

import com.desystems.replicatedlog.common.Message;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondaryApplicationTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void replicatesAndListsMessagesInOrder() throws Exception {
        server = new SecondaryApplication(0).start(0);
        int port = server.getAddress().getPort();

        replicate(port, new Message(1L, "first", 111L));
        replicate(port, new Message(2L, "second", 222L));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JSONArray messages = new JSONArray(response.body());
        assertEquals(2, messages.length());
        assertEquals("first", messages.getJSONObject(0).getString("text"));
        assertEquals("second", messages.getJSONObject(1).getString("text"));
    }

    @Test
    void replicateSleepsForConfiguredDelayBeforeAcking() throws Exception {
        long delayMs = 300;
        server = new SecondaryApplication(delayMs).start(0);
        int port = server.getAddress().getPort();

        long start = System.currentTimeMillis();
        replicate(port, new Message(1L, "slow", 111L));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= delayMs, "expected replication to block for at least " + delayMs + "ms, took " + elapsed + "ms");
    }

    private void replicate(int port, Message message) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/replicate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toJson().toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(message.getText(), new JSONObject(response.body()).getString("text"));
    }
}
