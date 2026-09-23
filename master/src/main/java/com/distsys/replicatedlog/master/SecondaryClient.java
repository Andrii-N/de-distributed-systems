package com.distsys.replicatedlog.master;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.distsys.replicatedlog.common.Message;


/**
 * Client-side view of one secondary node: knows how to replicate a single
 * message to it and wait for its ack
 */
public final class SecondaryClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String name;
    private final URI replicateUri;
    private final HttpClient httpClient;   
    
    public SecondaryClient(String name, String baseUrl, HttpClient httpClient) {
        this.name = name;
        this.replicateUri = URI.create(baseUrl + "/replicate");
        this.httpClient = httpClient;
    }

    /**
     * Sends the message to this secondary and blocks until it acks (HTTP 200).
     */
    public void replicate(Message message) {
        HttpRequest request = HttpRequest.newBuilder(replicateUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toJson().toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ReplicationException(name + " responded with status " + response.statusCode());
            }
        }
        catch (IOException e) {
            throw new ReplicationException("Failed to replicate message id=" + message.getId() + " to secondary " + name, e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReplicationException("Failed to replicate message id=" + message.getId() + " to secondary " + name, e);
        }

    }

    public static final class ReplicationException extends RuntimeException {
        public ReplicationException(String message) {
            super(message);
        }

        public ReplicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
