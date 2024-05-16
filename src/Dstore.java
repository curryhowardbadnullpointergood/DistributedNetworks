import java.io.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.DirectoryStream;

public class Dstore {

    // the global directory of the file
    String fileDirectory;

    // file name
    String storeFileName;
    // file size in bytes
    int storeFileSize;

    int timeduration;

    int controllerPort;

    int dport;
    // file to transfer back
    String fileToTransfer;




    //
    public Dstore(Integer port, Integer cport, Integer timeout, String file_folder) {

        this.fileDirectory = file_folder;
        this.timeduration = timeout;
        this.controllerPort = cport;
        this.dport = port;
        this.startFolder(fileDirectory);

        try (ServerSocket serverSocket = new ServerSocket(port);
             Socket controller = new Socket(InetAddress.getLoopbackAddress(), controllerPort);
             PrintWriter pw = new PrintWriter(controller.getOutputStream(), true);) {
            new Thread(() -> handleClient(controller, pw)).start();

            System.out.println("Data Store setup on port " + port);
            pw.println(String.format("%s %d", Protocol.JOIN_TOKEN, port));


            while (true) {
                Socket client = serverSocket.accept();

                new Thread(() -> handleClient(client, pw)).start();
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage() + " \n Port: " + dport);
        }


    }


    private void handleClient(Socket client, PrintWriter pw) {
        while (true) {
            try {
                var dataStoreOutput = client.getOutputStream();
                var printWriter = new PrintWriter(dataStoreOutput, true);
                var in = new BufferedReader(new InputStreamReader(client.getInputStream()));

                String line;
                while ((line = in.readLine()) != null) {
                    String[] contents = line.split(" ");
                    String command = contents[0];

                    System.out.println("Port: " + dport + line);
                    switch (command) {
                        case Protocol.STORE_TOKEN -> {

                            // set file size
                            storeFileSize = Integer.parseInt(contents[2]);
                            storeFileName = contents[1];

                            storeRequest(client, printWriter);

                            client.setSoTimeout(timeduration);
                            receiveContent(client, pw, client.getInputStream(), true);
                        }
                        case Protocol.LIST_TOKEN -> fileList( client, pw);
                        case Protocol.LOAD_DATA_TOKEN -> {
                            client.setSoTimeout(timeduration);
                            loadFile(contents[1], dataStoreOutput);
                        }
                        case Protocol.REMOVE_TOKEN -> deleteFile(client, contents[1], pw);
                        case Protocol.REBALANCE_TOKEN -> {


                            var remainingRebalance = line.split(" ", 2)[1];
                            rebalance(client, pw, remainingRebalance);
                        }
                        case Protocol.REBALANCE_STORE_TOKEN -> {

                            storeRequest(client, printWriter);
                            receiveContent(client, printWriter, client.getInputStream() , false);
                        }
                        case Protocol.ACK_TOKEN -> sendContents(dataStoreOutput);
                        default -> System.out.println("Unidentified message: " + line + "\n Port: " + dport);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }





    // sets fileToTransfer
    public void setFileToTransfer(String file){
        fileToTransfer = file;
    }


    // if a folder doesn't exist, this creates it, and if it does exist it clears it
    private void startFolder(String filePath) {


        // making a path for the directory
        Path dirPath = Paths.get(filePath);

        // checking if directory doesn't exist, and if it doesn't creates it
        File directory = new File(filePath);

        // if directory exists, clears and deletes contents of the directory
        if (directory.exists()) {
            try {
                deleteContentFile(filePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {

            Files.createDirectories(dirPath);
        } catch (Exception e) {
            e.printStackTrace();

        }


    }


    // deltes the files if any files exist inside the already created directories.
    private void deleteContentFile(String filePath) throws IOException {

        Path dirPath = Paths.get(filePath);

        long count = Files.list(dirPath).count();

        if (count > 0) {
            // Directory contains files or subdirectories.
            Files.walk(dirPath)
                    // Skip the directory itself
                    .skip(1)
                    // Delete each file and directory
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + p + " - " + e.getMessage());
                        }
                    });
        }

    }

    // Socket client, String fileName, PrintWriter pw
    private void deleteFile(Socket client, String fileName, PrintWriter pw) {


        Path filePath = Path.of(fileDirectory, fileName);
        // deletes file if it exists, else sends the appropriate error messages
        if (Files.exists(filePath)) {

            try {

                Files.delete(filePath);

                if (pw != null) {
                    pw.println(Protocol.REMOVE_ACK_TOKEN + " " + fileName);
                    System.out.println("Port: " + dport + Protocol.REMOVE_ACK_TOKEN + " " + fileName);
                }


            } catch (Exception e) {
                System.out.println("Failed to delete: " + fileName + " - " + e.getMessage());
            }
        } else {
            pw.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
            System.out.println("Port: " + dport + Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
        }


    }

    // sends file content through output stream
    private synchronized void sendContents(OutputStream outS) {

        Path fileDir = Paths.get(fileDirectory, fileToTransfer);

        try(InputStream inS = Files.newInputStream(fileDir); outS){

            byte[] content = inS.readAllBytes();

            outS.write(content);

        } catch(Exception e){
            e.printStackTrace();
        }


    }

    private synchronized void sendFile(Integer port, String fileName){
        try (Socket clientStore = new Socket(InetAddress.getLoopbackAddress(), port);
             OutputStream outS = clientStore.getOutputStream();
             PrintWriter pw = new PrintWriter(outS, true)) {

            Path filePath = Path.of(fileDirectory).resolve(fileName);
            long fileSize = Files.size(filePath);

            pw.println(Protocol.REBALANCE_STORE_TOKEN + " " + fileName + " " + fileSize);
            System.out.println("Send File Port: " + port +  Protocol.REBALANCE_STORE_TOKEN + " " + fileName + " " + fileSize);



            // sets fileToTransfer to filename of what you want to transfer
            setFileToTransfer(fileName);


            sendContents(outS);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    // receives a request to store a file and then sends back ack to client
    private synchronized void  storeRequest(Socket clientSocket, PrintWriter pw ){

        try{

            // send acknowledgement back to the client
            pw.println(Protocol.ACK_TOKEN);
            System.out.println("Port: " + dport + Protocol.ACK_TOKEN);

        } catch(Exception e){
            e.printStackTrace();
        }
    }

    // receive file content
    private synchronized void receiveContent(Socket clientSocket, PrintWriter pw, InputStream inS, boolean ackRequest){

        try{
            // makes sure only storing the exact size given
            byte[] data = new byte[storeFileSize];
            // stores the data inside the array
            inS.readNBytes(data, 0, storeFileSize);

            // Write the to the storefilename
            Path fileDir = Paths.get(fileDirectory, storeFileName);

            Files.createDirectories(fileDir.getParent());
            Files.write(fileDir, data);

            // Send a store ackknowledgement back to the client
            if(ackRequest){
                pw.println(Protocol.STORE_ACK_TOKEN + " " + storeFileName);
                System.out.println("Port: " + dport +  Protocol.STORE_ACK_TOKEN + " " + storeFileName);
            }

            System.out.println("FileName stored: " + storeFileName + " DStore Port: " + dport);

        }catch(Exception e){
            System.out.println("Error Storing file: " + storeFileName + " : filesize " + storeFileSize +" : Client SOcket: " + clientSocket) ;
            e.printStackTrace();
        }
    }

    private synchronized void loadFile(String fileName, OutputStream outS){

        try{
            // Check for invalid arguments
            if(fileName == null || outS == null){
                throw new IllegalArgumentException("Invalid arguments");
            }

            System.out.println("Loding File: "+ fileName);
            Path fileDir = Paths.get(fileDirectory, fileName);
            byte[] data = Files.readAllBytes(fileDir);
            outS.write(data);

        } catch(Exception e){
            e.printStackTrace();
        }
    }

    private synchronized void fileList(Socket clientSocket, PrintWriter pw) {


        StringBuilder files = new StringBuilder();

        // Get file directory
        Path fileDir = Paths.get(fileDirectory);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileDir)) {
            for (Path path : stream) {
                files.append(" ").append(path.getFileName().toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Sends list of files to the printwriter
        pw.println(Protocol.LIST_TOKEN + files);
        System.out.println("Port: " + dport +  Protocol.LIST_TOKEN + files);
    }


    private synchronized void rebalance(Socket clientSocket, PrintWriter pw, String rebalance) {

        int filesToTransfer = 0;
        int filesToRemove = 1;

        String[] content = rebalance.split(" ");

        // File is sent to the correct store
        while (filesToTransfer < Integer.parseInt(content[0])) {
            String fileName = content[1];
            int replicationCount = Integer.parseInt(content[2]);


            for (int i = 1; i <= replicationCount; i++) {
                int portNum = Integer.parseInt(content[2 + i]);
                sendFile(portNum, fileName);
            }

            filesToTransfer++;
            filesToRemove = filesToRemove + replicationCount + 2;
        }


        int remove = filesToRemove;
        int filesToDelete = Integer.parseInt(content[remove]);


        for (int i = 1; i <= filesToDelete; i++) {
            deleteFile(clientSocket, content[remove + i], null);
        }

        pw.println(Protocol.REBALANCE_COMPLETE_TOKEN);
        System.out.println("Port: " + dport +  Protocol.REBALANCE_COMPLETE_TOKEN);
    }





    public static void main(String[] args) {

        if (args.length >= 4){

            Dstore ds = new Dstore(Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]), args[3]);

        } System.out.println("Insufficient number of arguments");

    }


}
