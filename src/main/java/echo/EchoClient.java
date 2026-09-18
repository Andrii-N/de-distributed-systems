package echo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Connects to an EchoServer, sends whatever the user types, and prints back
 * what the server echoed to confirm it arrived unchanged.
 */
public final class EchoClient {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5000;

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader stdIn = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println("Connected to echo server at " + host + ":" + port);
            System.out.println("Type a message and press Enter. Type 'exit' to quit.");

            String userInput;
            while ((userInput = stdIn.readLine()) != null) {
                if ("exit".equalsIgnoreCase(userInput)) {
                    break;
                }
                out.println(userInput);
                String echoed = in.readLine();
                System.out.println("Server echoed: " + echoed);
            }
        }
    }

    private EchoClient() {
    }
}
