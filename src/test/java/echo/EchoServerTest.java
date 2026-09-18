package echo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EchoServerTest {

    private ServerSocket serverSocket;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        serverSocket = new ServerSocket(0); // let the OS pick a free port
        port = serverSocket.getLocalPort();
        Thread.ofVirtual().start(this::acceptOneConnection);
    }

    private void acceptOneConnection() {
        try (Socket clientSocket = serverSocket.accept();
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                out.println(line);
            }
        } catch (IOException ignored) {
            // socket closed as part of test teardown
        }
    }

    @AfterEach
    void stopServer() throws IOException {
        serverSocket.close();
    }

    @Test
    void serverEchoesBackExactlyWhatTheClientSent() throws IOException {
        try (Socket socket = new Socket("localhost", port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            out.println("hello distributed systems");
            assertEquals("hello distributed systems", in.readLine());

            out.println("second line");
            assertEquals("second line", in.readLine());
        }
    }
}
