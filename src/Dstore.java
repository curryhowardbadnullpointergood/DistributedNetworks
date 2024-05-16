import java.io.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.stream.Stream;

public class Dstore {

    // the global directory of the file
    String fileDirectory;

    String currentFileName;
    int currentFileSize;

    // file to transfer back
    String fileToTransfer;

    // logging
    private static DStoreLogger logger;


    //
    public Dstore(Integer port, Integer cport, Integer timeout, String file_folder) {


    }


    // sets the local directory
    public void setDirectory(String fileDir) {
        fileDirectory = fileDir;
    }


    // if a folder doesn't exist, this creates it, and if it does exist it clears it
    public void startFolder(String filePath) {


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
                    logger.messageSent(client, Protocol.REMOVE_ACK_TOKEN + " " + fileName);
                }


            } catch (Exception e) {
                System.out.println("Failed to delete: " + fileName + " - " + e.getMessage());
            }
        } else {
            pw.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
            logger.messageSent(client, Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
        }


    }

    // sends file content

    private synchronized void sendContents(OutputStream outS) {

        Path fileDir = Paths.get(fileDirectory, fileToTransfer);

        try(InputStream inS = Files.newInputStream(fileDir); outS){

        } catch(Exception e){
            e.printStackTrace();
        }


    }

    public static void main(String[] args) {
        // Initialize logger
        logger = new DStoreLogger(Logger.LoggingType.TERMINAL, 9999);

    }


}
