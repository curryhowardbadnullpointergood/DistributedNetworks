
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * The ManualTester class is designed for manual testing of the Controller in a distributed file storage system.
 * It allows for interactive communication with the Controller by sending user-typed messages from the command line
 * and displaying responses from the Controller. This utility is particularly useful for debugging and testing
 * the Controller's request handling capabilities in a live environment.
 * <p>
 * Upon initialization, this class establishes a TCP connection to the Controller at a specified host and port.
 * Users can then input messages directly into the command line which are sent to the Controller, with the
 * responses being printed back to the console. This setup aids in validating and observing the behavior
 * of the Controller under various conditions.
 * </p>f
 * @author Jakub Nowacki
 */
public class ManualTester {
    /**
     * The socket used to communicate with the other end of the connection.
     */
    private final Socket socket;
    /**
     * The PrintWriter object used to send messages back to the other end of the connection.
     */
    private final PrintWriter out;
    /**
     * The BufferedReader object used to read responses from the other end of the connection.
     */
    private final BufferedReader in;

    /**
     * Creates a new ManualTester instance with the specified host and port number.
     * @param host The host name of the Controller.
     * @param port The port number on which the Controller is listening for incoming connections.
     * @throws IOException If an I/O error occurs while creating the socket.
     */
    public ManualTester(String host, int port) throws IOException {
        // Establish a connection to the controller
        socket = new Socket(host, port);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    /**
     * Sends messages to the other endpoints and prints the responses back to the console.
     */
    public void sendMessages() {
        try (BufferedReader stdIn = new BufferedReader(new InputStreamReader(System.in))) {
            String userInput;
            while ((userInput = stdIn.readLine()) != null) {
                if ("T1".equals(userInput)) {
                    sendFileTo("file1.txt", "Contents of file 1, once upon a time in a...", socket.getPort());
                }
                else if ("T11".equals(userInput)) {
                    for (int j = 0; j < 10; j++){
                        int jFinal = j;
                        new Thread(() -> {
                            for (int i = 0; i < 10; i++){
                                int iFinal = i;
                                new Thread(() -> {
                                    try {
                                        sendFileTo(jFinal + "r" + iFinal + ".txt", generateRandomString(), socket.getPort());
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                }).start();
                            }
                        }).start();
                    }
                }
                else if ("T2".equals(userInput)) {
                    out.println("LOAD_DATA file1.txt");
                    receiveFile("file1.txt", "Contents of file 1, once upon a time in a...".length(), socket);
                }
                else if ("T3".equals(userInput)) {
                    removeFile("file1.txt");
                }
                else if ("T5".equals(userInput)){
                    testRebalance();
                }
                else if ("T6".equals(userInput)){
                    readFile("file1.txt");
                }
                else {
                    // Send message to the controller
                    out.println(userInput);
                    // Print server response
                    System.out.println("Received: " + in.readLine());
                }
            }
        } catch (IOException e) {
            System.out.println("An error occurred while reading from or writing to the socket: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                System.out.println("Error closing the socket: " + e.getMessage());
            }
        }
    }

    /**
     * The main method for the ManualTester class is expected
     * to be used as follows with 2 arguments:
     * java ManualTester host port
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java ManualTester <host> <port>");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);

        try {
            ManualTester tester = new ManualTester(host, port);
            System.out.println("Connected to Controller at " + host + ":" + port);
            System.out.println("Type your messages to send to the Controller:");
            tester.sendMessages();
        } catch (IOException e) {
            System.out.println("Cannot connect to the controller: " + e.getMessage());
        }
    }

    private void readFile(String filename) throws IOException {
        out.println("LOAD " + filename);
        String res = in.readLine();
        System.out.println("Received: " + res);
        if (res.equals("ERROR_FILE_DOES_NOT_EXIST")) {
            return;
        }
        String[] tokens = res.split(" ");
        int port = Integer.parseInt(tokens[1]);
        int fileSize = Integer.parseInt(tokens[2]);

        Socket socket = new Socket("localhost", port);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        out.println("LOAD_DATA " + filename);
        receiveFile(filename, fileSize, socket);

        //receiveFile(filename, Integer.parseInt(tokens[1]), socket);

    }

    private void receiveFile(String filename, int fileSize, Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            byte[] buffer = in.readNBytes(fileSize);

            if (buffer.length != fileSize) {
                throw new IOException("File size mismatch: expected " + fileSize + " bytes, received " + buffer.length);
            }

            // Convert byte array to string assuming UTF-8 encoding
            String fileContents = new String(buffer, StandardCharsets.UTF_8);
            // Print file contents
            System.out.println("Received file contents for " + filename + ":");
            System.out.println(fileContents);

        } catch (IOException e) {
            System.out.println("Failed to process file data: " + e.getMessage());
        }
    }

    private void removeFile(String filename) throws IOException {
        out.println("REMOVE " + filename);
        System.out.println("Received: " + in.readLine());
    }

    private void testRebalance() {
        out.println("REBALANCE 2 test.txt 2 1009 1010 test2.txt 2 1011 1010 1 test3.txt");
    }

    public void sendFileTo(String filename, String fileContent, int port) throws IOException {
        Socket socket = new Socket("localhost", port);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        out.println("STORE " + filename + " " + fileContent.length());
        String response = in.readLine();
        String[] tokens = response.split(" ");
        System.out.println("Received: " + response);

        if (tokens[0].equals("ACK")) {
            out.println(fileContent);
            socket.close();
            return;
        }
        if (tokens[0].equals("ERROR_FILE_ALREADY_EXISTS")) {
            socket.close();
            return;
        }

        for (int i = 1; i < tokens.length; i++) {
            int finalI = i;
            Thread thread = new Thread(() -> {
                try {
                    sendFileTo(filename, fileContent, Integer.parseInt(tokens[finalI]));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            thread.start();
        }

        System.out.println("Received: " + in.readLine());
        socket.close();
    }

    public static String generateRandomString() {
        Random random = new Random();
        // Determine a random length between 1 and 100,000
        int length = 1 + random.nextInt(100000);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            // Generate a random alphanumeric character
            int characterType = random.nextInt(4);
            if (characterType == 0) {
                // Append a random lowercase letter
                sb.append((char) ('a' + random.nextInt(26)));
            } else if (characterType == 1) {
                // Append a random uppercase letter
                sb.append((char) ('A' + random.nextInt(26)));
            } else if (characterType == 2) {
                // Append a random digit
                sb.append(random.nextInt(10));
            }
            else {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

}