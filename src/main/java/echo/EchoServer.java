package echo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Listens on a TCP port and echoes back every line a connected client sends,
 * so the client can confirm its data arrived exactly as sent.
 */
public final class EchoServer {

    private static final int DEFAULT_PORT = 5000;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Echo server listening on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread.ofVirtual().start(() -> handleClient(clientSocket));
            }
        }
    }

    private static void handleClient(Socket clientSocket) {
        String remote = clientSocket.getRemoteSocketAddress().toString();
        System.out.println("Client connected: " + remote);
        try (clientSocket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            String line;
            while ((line = in.readLine()) != null) {
                out.println(line);
            }
        } catch (IOException e) {
            System.out.println("Connection with " + remote + " failed: " + e.getMessage());
        } finally {
            System.out.println("Client disconnected: " + remote);
        }
    }

    private EchoServer() {
    }
}
