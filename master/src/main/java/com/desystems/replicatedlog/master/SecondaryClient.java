package com.desystems.replicatedlog.master;

import com.desystems.replicatedlog.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client-side view of one secondary node: knows how to replicate a single
 * message to it and wait for its ack (perfect-link assumption, so no
 * retries/timeout handling beyond a generous request timeout).
 */
public final class SecondaryClient {

    private static final Logger log = LoggerFactory.getLogger(SecondaryClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String name;
    private final URI replicateUri;
    private final HttpClient httpClient;

    public SecondaryClient(String name, String baseUrl, HttpClient httpClient) {
        this.name = name;
        this.replicateUri = URI.create(stripTrailingSlash(baseUrl) + "/replicate");
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

        log.info("Dispatching message id={} to secondary {} ({})", message.getId(), name, replicateUri);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ReplicationException(name + " responded with status " + response.statusCode());
            }
            log.info("Received ack for message id={} from secondary {}", message.getId(), name);
        } catch (IOException | InterruptedException e) {
            throw new ReplicationException("Failed to replicate message id=" + message.getId() + " to secondary " + name, e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
