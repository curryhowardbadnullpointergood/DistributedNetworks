import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Controller {




    Index index;
    Map<Integer, Socket> dataStoreSocketList;
    static ConcurrentHashMap<String, ArrayList<Integer>> fileList;
    ConcurrentHashMap<String, PrintWriter> fileStorePrintWriter;
    ConcurrentHashMap<String, PrintWriter> fileRemovePrintWriter;

    int rebalanceCounter;
    int rebalanceCompleteCounter;
    int replicationFactor;
    int completedStores;
    int loadPortCounter;


    int controllerPort;
    int timeoutVal;
    int rebalancePeriod;



    public Controller (Integer cport, Integer replication_factor, Integer timeout_val, Integer rebalance_Period){



        index = new Index(new ArrayList<>(), new ConcurrentHashMap<>());
        fileList = new ConcurrentHashMap<>();
        dataStoreSocketList = new ConcurrentHashMap<>();
        fileStorePrintWriter = new ConcurrentHashMap<>();
        fileRemovePrintWriter = new ConcurrentHashMap<>();

        rebalanceCounter = 0;
        rebalanceCompleteCounter = 0;
        replicationFactor = replication_factor;
        controllerPort = cport;
        timeoutVal = timeout_val;
        rebalancePeriod = rebalance_Period;
        completedStores = 0;
        loadPortCounter = 0;


        // bunch of setter, can be removed , check each function and make sure it is half legit
        index.reset();

        ExecutorService pool = Executors.newFixedThreadPool(10);

        startRebalanceTimer(rebalancePeriod * 1000);


        try {
            var serverSocket = new ServerSocket(controllerPort);
            System.out.println("Controller setup on port " + controllerPort);


            while (true) {
                try {

                    Socket client = serverSocket.accept();
                    pool.submit(() -> handleClient(client, timeoutVal));
                } catch (Exception e) {
                    System.out.println("Error handling client connection: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("Error starting server socket: " + e.getMessage());
        }

    }

    public static void main(String[] args) {


        if (args.length >= 4){

            Controller controller = new Controller(Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));

        } System.out.println("Insufficient number of arguments");


    }

    private void handleClient(Socket client, int timeout) {
        int currentDataStorePort = 0;
        boolean isDataStore = false;
        String line;

        try (client;
             var out = new PrintWriter(client.getOutputStream(), true);
             var in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {

            // read lines from the client
            while ((line = in.readLine()) != null) {
                String[] contents = line.split(" ");
                String command = contents[0];
                System.out.println("Message Received" + line);

                switch (command) {
                    case Protocol.JOIN_TOKEN -> {
                        isDataStore = true;
                        currentDataStorePort = Integer.parseInt(contents[1]);
                        addDataStore(client, Integer.parseInt(contents[1]));
                        rebalance();
                    }
                    case Protocol.LIST_TOKEN -> {
                        if (isDataStore) {
                            handleDataStoreList(currentDataStorePort, contents);
                        } else {
                            if (checkEnoughDataStores(client, out)) {
                                handleClientList(client, out);
                            }
                        }
                    }
                    case Protocol.STORE_TOKEN -> {
                        if (checkEnoughDataStores(client, out)) {
                            CountDownLatch storeLatch = new CountDownLatch(1);
                            handleStore(client, out, contents, storeLatch);
                            boolean storeAckReceived = storeLatch.await(timeout, TimeUnit.MILLISECONDS);
                            if (!storeAckReceived) {
                                System.out.println("STORE_ACK not received within timeout");
                            }
                        }
                    }
                    case Protocol.STORE_ACK_TOKEN -> handleStoreAck(client, currentDataStorePort, contents);
                    case Protocol.LOAD_TOKEN -> {
                        if (checkEnoughDataStores(client, out)) {
                            handleLoad(client, contents, 1, out);
                        }
                    }
                    case Protocol.RELOAD_TOKEN -> handleReload(client, out, contents);
                    case Protocol.REMOVE_TOKEN -> {
                        if (checkEnoughDataStores(client, out)) {
                            CountDownLatch removeLatch = new CountDownLatch(1);
                            handleRemove(client, out, contents, removeLatch);
                            boolean storeAckReceived = removeLatch.await(timeout, TimeUnit.MILLISECONDS);
                            if (!storeAckReceived) {
                                System.out.println("REMOVE_ACK not received within timeout");
                            }
                        }
                    }
                    case Protocol.REMOVE_ACK_TOKEN -> {
                        if (checkEnoughDataStores(client, out)) {
                            handleRemoveAck(client, currentDataStorePort, contents);
                        }
                    }
                    case Protocol.REBALANCE_COMPLETE_TOKEN -> handleRebalanceComplete(client, out);
                    default -> System.out.println("Unidentified message: " + line);
                }
            }
        } catch (SocketException e) {
            System.out.println("SocketException occurred. Failed Data Store will be fixed: " + e.getMessage());
            fixFailedDataStore(currentDataStorePort);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void handleClientList(Socket client, PrintWriter out) {
        listFiles(client, out);
    }

    private void handleRebalanceComplete(Socket client, PrintWriter out) {
        out.println(Protocol.LIST_TOKEN);
        System.out.println( Protocol.LIST_TOKEN);

        setRebalanceCompleteCounter(getRebalanceCompleteCounter() - 1);
        if (getRebalanceCompleteCounter() == 0) {
            updateIndexAfterRebalance();
        }
    }

    private void handleRemoveAck(Socket client, int currentPort, String[] contents) {
        if (index.fileStatus.get(contents[1]) == index.Removing) {
            removeComplete(client, currentPort, fileRemovePrintWriter.get(contents[1]), contents[1]);
        }
    }

    private void handleRemove(Socket client, PrintWriter out, String[] contents, CountDownLatch removeLatch) {
        try {
            if (index.fileNames.contains(contents[1])) {
                fileRemovePrintWriter.put(contents[1], out);
            }
            remove(client, out, contents[1]);
            removeLatch.countDown();
        } catch (Exception e) {
            System.out.println("Remove handling failed");
        }
    }

    private void handleReload(Socket client, PrintWriter out, String[] contents) {

        if (getLoadPortCounter() == fileList.get(contents[1]).size() - 1) {
            out.println(Protocol.ERROR_LOAD_TOKEN);
        } else if (checkEnoughDataStores(client, out)) {
            handleLoad(client, contents, getLoadPortCounter() + 1, out);
        }
    }

    private void handleLoad(Socket client, String[] contents, int loadPortCounter, PrintWriter out) {
        if (index.fileNames.contains(contents[1])) {
            setLoadPortCounter(loadPortCounter);
            load(out, contents[1], getLoadPortCounter());
        } else {
            out.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
            System.out.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
        }
    }

    private void handleStoreAck(Socket client, int currentPort, String[] contents) {
        if (index.fileStatus.get(contents[1]) == index.Storing) {
            storeComplete(client, currentPort, fileStorePrintWriter.get(contents[1]), getCompletedStores() + 1, contents[1]);
        }
    }

    private synchronized void storeComplete(Socket client, int dataStorePort, PrintWriter printWriter, int completedStores, String fileName) {

        List<Integer> fileList = Controller.fileList.computeIfAbsent(fileName, k -> new ArrayList<>());


        if (!fileList.contains(dataStorePort)) {
            fileList.add(dataStorePort);
        }


        if (!(completedStores == getReplicationFactor())) {
            setCompletedStores(getCompletedStores() + 1);
            return;
        }


        index.fileNames.add(fileName);


        setCompletedStores(0);


        index.fileStatus.put(fileName, index.Stored);
        printWriter.println(Protocol.STORE_COMPLETE_TOKEN);
        System.out.println(Protocol.STORE_COMPLETE_TOKEN);
    }

    private void handleStore(Socket client, PrintWriter out, String[] contents, CountDownLatch storeLatch) {
        try {
            if (!index.fileStatus.containsKey(contents[1])) {
                fileStorePrintWriter.put(contents[1], out);
            }
            store(client, out, replicationFactor, contents[1], Integer.parseInt(contents[2]));
            storeLatch.countDown();
        } catch (Exception e) {
            System.out.println("Store handling failed");
        }
    }

    private void handleDataStoreList(int port, String[] contents) {
        rebalanceCounter++;
        System.out.println("Data Store LIST");
        ArrayList<String> fileNames = new ArrayList<>(Arrays.asList(contents).subList(1, contents.length));
        updateHashMap(port, fileNames);
    }

    private synchronized void updateHashMap(Integer port, ArrayList<String> fileNames) {
        if (!fileList.isEmpty()) {
            for (String fileName : fileNames) {
                if (fileList.containsKey(fileName)) {
                    fileList.get(fileName).add(port);
                }
            }
        }

        if (rebalanceCounter >= dataStoreSocketList.size()) {

            rebalanceCounter = 0;
            calculateRebalances();
        }
    }

    private synchronized boolean checkEnoughDataStores(Socket client, PrintWriter printWriter) {
        if (dataStoreSocketList.size() >= replicationFactor) {
            return true;
        }
        printWriter.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
        System.out.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
        return false;
    }

    private synchronized void addDataStore(Socket dataStore, Integer port) throws IOException {
        //System.out.println(dataStore);
        dataStoreSocketList.put(port, new Socket(InetAddress.getLoopbackAddress(), port));
        System.out.println(port.toString());
    }

    private synchronized void listFiles(Socket client, PrintWriter printWriter) {
        StringBuilder fileNames = new StringBuilder();
        if (index.getFileNames().size() > 0) {
            for (String file : index.getFileNames()) {
                if (index.fileStatus.get(file) == index.Stored)
                    fileNames.append(" ").append(file);
            }
        }
        printWriter.println(Protocol.LIST_TOKEN + fileNames);
        System.out.println(Protocol.LIST_TOKEN + fileNames);
    }

    private synchronized void store(Socket clientSocket, PrintWriter printWriter, int replicationFactor, String fileName, int fileSize) {

        if (!index.fileStatus.containsKey(fileName)) {

            ArrayList<Integer> tmp = new ArrayList<>();
            tmp.add(fileSize);
            fileList.put(fileName, tmp);
            index.fileStatus.put(fileName, index.Storing);


            StringBuilder portsBuilder = new StringBuilder();
            int currentReplicationFactor = 0;
            for (Integer key : dataStoreSocketList.keySet()) {
                if (currentReplicationFactor < replicationFactor) {
                    portsBuilder.append(" ").append(key);
                    currentReplicationFactor++;
                }
            }
            String ports = portsBuilder.toString();


            printWriter.println(Protocol.STORE_TO_TOKEN + ports);
            System.out.println(Protocol.STORE_TO_TOKEN + ports);
        } else {
            if (index.fileStatus.get(fileName) == index.Removed) {
                printWriter.println(Protocol.STORE_TO_TOKEN);
                System.out.println(Protocol.STORE_TO_TOKEN);
            }

            printWriter.println(Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN);
            System.out.println(Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN);
        }
    }

    private synchronized void load(PrintWriter printWriter, String fileName, int loadPortCounter) {
        for (String key : fileList.keySet()) {

            if (key.equals(fileName) && index.fileNames.contains(key)) {
                int fileSize = fileList.get(fileName).get(0);
                int port = fileList.get(fileName).get(loadPortCounter);

                printWriter.println(Protocol.LOAD_FROM_TOKEN + " " + port + " " + fileSize);
                System.out.println("Loading from " + port + " with size " + fileSize);
            }
        }
    }

    private synchronized void remove(Socket client, PrintWriter printWriter, String fileName) {

        if (!index.fileNames.contains(fileName)) {
            printWriter.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
            System.out.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
            return;
        }


        index.fileStatus.remove(fileName);
        index.fileStatus.put(fileName, index.Removing);
        System.out.println("Remove in progress");


        if (fileList.containsKey(fileName)) {
            ArrayList<Integer> dataStores = fileList.get(fileName);
            for (int i = 1; i < dataStores.size(); i++) {
                sendRemove(dataStores.get(i), fileName);
            }
        }
    }

    private synchronized void sendRemove(Integer port, String fileName) {
        try {
            Socket dataStore = dataStoreSocketList.get(port);
            PrintWriter dataStorePrintWriter = new PrintWriter(dataStore.getOutputStream(), true);

            dataStorePrintWriter.println(Protocol.REMOVE_TOKEN + " " + fileName);
            System.out.println(Protocol.REMOVE_TOKEN + " " + fileName);
        } catch (IOException e) {
            System.out.println("Failed to send remove request to port " + port + ": " + e.getMessage());
        }
    }

    private synchronized void removeComplete(Socket client, Integer port, PrintWriter printWriter, String fileName) {
        System.out.println(String.valueOf(fileList.get(fileName).size() - 1));


        if (fileList.get(fileName).size() - 1 > 1) {

            fileList.get(fileName).remove(port);
            return;
        }


        index.fileNames.remove(fileName);

        fileList.remove(fileName);


        index.fileStatus.remove(fileName);
        index.fileStatus.put(fileName, index.Removed);


        printWriter.println(Protocol.REMOVE_COMPLETE_TOKEN);
        System.out.println(Protocol.REMOVE_COMPLETE_TOKEN);
    }

    private synchronized void startRebalanceTimer(int rebalanceTime) {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                rebalance();
            }
        }, rebalanceTime, rebalanceTime);
    }

    private synchronized void rebalance() {

        updateHashMap();


        for (Integer dataStorePort : dataStoreSocketList.keySet()) {
            try {
                Socket dataStore = dataStoreSocketList.get(dataStorePort);

                PrintWriter dataStorePrintWriter = new PrintWriter(dataStore.getOutputStream(), true);
                dataStorePrintWriter.println(Protocol.LIST_TOKEN);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private synchronized void updateHashMap() {
        for (Map.Entry<String, ArrayList<Integer>> entry : fileList.entrySet()) {
            int size = entry.getValue().get(0);
            entry.getValue().set(0, size);
        }
    }

    private synchronized void calculateRebalances() {


        HashMap<Integer, ArrayList<String>> dataStoreHashMap = getDataStoreHashMap();



        double minReplication = Math.floor(replicationFactor * fileList.size() / (double) dataStoreSocketList.size());
        double maxReplication = Math.ceil(replicationFactor * fileList.size() / (double) dataStoreSocketList.size());

        HashMap<Integer, ArrayList<String>> filesToStoreHashMap = new HashMap<>();
        HashMap<Integer, ArrayList<String>> filesToRemoveHashMap = removeFiles(index.fileStatus);
        HashMap<String, ArrayList<Integer>> filesToSendHashMap = new HashMap<>();



        while (!isReplicationComplete(dataStoreHashMap, replicationFactor, minReplication, maxReplication)) {


            reachMaxReplicationStep(dataStoreHashMap, maxReplication, filesToStoreHashMap, filesToSendHashMap);



            if (isReplicationComplete(dataStoreHashMap, replicationFactor, minReplication, maxReplication)) {
                break;
            }


            reachMinReplicationStep(dataStoreHashMap, filesToRemoveHashMap);
        }



        prepareRebalanceRequests(filesToStoreHashMap, filesToRemoveHashMap, filesToSendHashMap);
    }

    private void reachMaxReplicationStep(HashMap<Integer, ArrayList<String>> dataStoreHashMap, double maxReplication, HashMap<Integer, ArrayList<String>> filesToStoreHashMap, HashMap<String, ArrayList<Integer>> filesToSendHashMap) {
        Integer minDataStore;
        String minFile;

        while (!checkDataStoreMaxReplication(maxReplication, dataStoreHashMap)) {
            Integer skip = 0;


            minDataStore = getDataStore(dataStoreHashMap, false);


            minFile = getMinFile(skip, dataStoreHashMap);

            while (dataStoreHashMap.get(minDataStore).contains(minFile)) {
                skip++;
                minFile = getMinFile(skip, dataStoreHashMap);
            }



            dataStoreHashMap.get(minDataStore).add(minFile);
            putFilesDataStoreHashMap(minFile, minDataStore, filesToStoreHashMap);
            putFilesDataStoreAndListHashMap(minFile, minDataStore, filesToSendHashMap);
        }
    }

    private void reachMinReplicationStep(HashMap<Integer, ArrayList<String>> dataStoreHashMap, HashMap<Integer, ArrayList<String>> filesToRemoveHashMap) {
        String maxFile;
        Integer maxDataStore;


        while (!checkFilesMinReplication(replicationFactor, dataStoreHashMap)) {


            maxDataStore = getDataStore(dataStoreHashMap, true);


            maxFile = getMaxFile(dataStoreHashMap, maxDataStore);



            putFilesDataStoreHashMap(maxFile, maxDataStore, filesToRemoveHashMap);
            dataStoreHashMap.get(maxDataStore).remove(maxFile);
        }
    }

    private void putFilesDataStoreAndListHashMap(String minFile, Integer minDataStore, HashMap<String, ArrayList<Integer>> filesToSendHashMap) {
        if (filesToSendHashMap.containsKey(minFile)) {
            if (!filesToSendHashMap.get(minFile).contains(minDataStore)) {
                filesToSendHashMap.get(minFile).add(minDataStore);
            }
        } else {
            if (fileList.get(minFile) != null) {
                filesToSendHashMap.put(minFile, new ArrayList<>(Arrays.asList(fileList.get(minFile).get(1), minDataStore)));
            }
        }
    }

    private void putFilesDataStoreHashMap(String file, Integer dataStore, HashMap<Integer, ArrayList<String>> filesHashMap) {
        if (filesHashMap.containsKey(dataStore)) {
            if (!filesHashMap.get(dataStore).contains(file)) {
                filesHashMap.get(dataStore).add(file);
            }
        } else {
            filesHashMap.put(dataStore, new ArrayList<>(Collections.singletonList(file)));
        }
    }

    private synchronized HashMap<Integer, ArrayList<String>> removeFiles(ConcurrentHashMap<String, Integer> statusHashMap) {
        HashMap<Integer, ArrayList<String>> filesToRemove = new HashMap<>();
        for (String key : statusHashMap.keySet()) {
            if (statusHashMap.get(key) == index.Removing) {
                ArrayList<Integer> ports = fileList.get(key);
                ports.remove(0);
                for (Integer port : ports) {
                    putFilesDataStoreHashMap(key, port, filesToRemove);
                }
            }
        }
        return filesToRemove;
    }

    private synchronized HashMap<Integer, ArrayList<String>> getDataStoreHashMap() {
        for (String key : index.fileNames) {
            if (index.fileStatus.get(key) == index.Removed) {
                fileList.remove(key);
            }
        }
        HashMap<Integer, ArrayList<String>> dataStoreHashMap = new HashMap<>();

        for (String key : fileList.keySet()) {
            ArrayList<Integer> values = fileList.get(key);
            for (int i = 1; i < values.size(); i++) {
                if (dataStoreHashMap.containsKey(values.get(i))) {
                    if (!dataStoreHashMap.get(values.get(i)).contains(key)) {
                        dataStoreHashMap.get(values.get(i)).add(key);
                    }
                } else {
                    ArrayList<String> tempValue = new ArrayList<>();
                    tempValue.add(key);
                    dataStoreHashMap.put(values.get(i), tempValue);
                }
            }
        }

        for (Integer port : dataStoreSocketList.keySet()) {
            if (!dataStoreHashMap.containsKey(port)) {
                dataStoreHashMap.put(port, new ArrayList<>());
            }
        }
        return dataStoreHashMap;
    }

    private synchronized boolean checkDataStoreMaxReplication(Double maxReplication, HashMap<Integer, ArrayList<String>> hashMap) {
        for (Integer key : hashMap.keySet()) {
            if (hashMap.get(key).size() < maxReplication) {
                return false;
            }
        }
        return true;
    }

    private Integer getDataStore(HashMap<Integer, ArrayList<String>> dataStoreHashMap, boolean findMax) {


        Integer resultDataStore = 1;
        int resultDataStoreSize = findMax ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (Integer dataStore : dataStoreHashMap.keySet()) {
            int dataStoreSize = dataStoreHashMap.get(dataStore).size();



            if (findMax) {
                if (dataStoreSize > resultDataStoreSize) {
                    resultDataStore = dataStore;
                    resultDataStoreSize = dataStoreSize;
                }
            } else {

                if (dataStoreSize < resultDataStoreSize) {
                    resultDataStore = dataStore;
                    resultDataStoreSize = dataStoreSize;
                }
            }
        }
        return resultDataStore;
    }

    private String getMinFile(Integer skip, HashMap<Integer, ArrayList<String>> hashMap) {
        HashSet<String> filesSet = new HashSet<>();
        for (ArrayList<String> fileNames : hashMap.values()) {
            filesSet.addAll(fileNames);
        }
        String minFile = getMinFileReplications(revertToKeyFileName(hashMap), filesSet);
        while (skip > 0) {
            skip--;
            filesSet.remove(minFile);
            minFile = getMinFileReplications(revertToKeyFileName(hashMap), filesSet);
        }
        return minFile;
    }

    private String getMaxFile(HashMap<Integer, ArrayList<String>> hashMap, Integer maxDataStore) {
        String maxFileName = "";
        int tempMax = Integer.MIN_VALUE;
        ArrayList<String> fileArray = hashMap.get(maxDataStore);
        HashMap<String, ArrayList<Integer>> invertedHashMap = revertToKeyFileName(hashMap);

        for (String key : fileArray) {
            if (invertedHashMap.get(key).size() > tempMax) {
                tempMax = invertedHashMap.get(key).size();
                maxFileName = key;
            }
        }
        return maxFileName;
    }

    private String getMinFileReplications(HashMap<String, ArrayList<Integer>> hashMap, Set<String> stringSet) {
        String minFileName = "";
        int minFileReplications = Integer.MAX_VALUE;

        for (String key : stringSet) {
            if (hashMap.get(key).size() < minFileReplications) {
                minFileName = key;
                minFileReplications = hashMap.get(key).size();
            }
        }
        return minFileName;
    }

    private HashMap<String, ArrayList<Integer>> revertToKeyFileName(HashMap<Integer, ArrayList<String>> hashMap) {
        HashMap<String, ArrayList<Integer>> reverted = new HashMap<>();
        for (int key : hashMap.keySet()) {
            for (String fileName : hashMap.get(key)) {
                reverted.computeIfAbsent(fileName, k -> new ArrayList<>()).add(key);
            }
        }
        return reverted;
    }


    private boolean isReplicationComplete(HashMap<Integer, ArrayList<String>> dataStoreHashMap, int replicationFactor, double minReplication, double maxReplication) {
        final var fileHashMap = revertToKeyFileName(dataStoreHashMap);

        for (var entry : fileHashMap.entrySet()) {
            if (!(entry.getValue().size() == replicationFactor)) {
                return false;
            }
        }

        for (var entry : dataStoreHashMap.entrySet()) {
            if (!(entry.getValue().size() >= minReplication && entry.getValue().size() <= maxReplication)) {
                return false;
            }
        }

        return true; // return true otherwise
    }

    private boolean checkFilesMinReplication(Integer replicationFactor, HashMap<Integer, ArrayList<String>> hashMap) {
        HashMap<String, ArrayList<Integer>> fileHashMap = revertToKeyFileName(hashMap);
        for (String key : fileHashMap.keySet()) {
            if (fileHashMap.get(key).size() > replicationFactor) {
                return false;
            }
        }
        return true;
    }

    private synchronized void prepareRebalanceRequests(HashMap<Integer, ArrayList<String>> fileToStoreHashMap, HashMap<Integer, ArrayList<String>> fileToRemoveHashMap, HashMap<String, ArrayList<Integer>> fileToSendHashMap) {


        if (fileToStoreHashMap.isEmpty() && fileToRemoveHashMap.isEmpty() && fileToSendHashMap.isEmpty()) {
            System.out.println("No rebalancing required.");
            return;
        }



        var finalSend = new HashMap<HashMap<Integer, String>, ArrayList<Integer>>();
        fileToSendHashMap.forEach((file, portsToSend) -> {
            var linkHashMap = new HashMap<Integer, String>();
            linkHashMap.put(portsToSend.get(0), file);
            finalSend.put(linkHashMap, new ArrayList<>(portsToSend.subList(1, portsToSend.size())));
        });



        setRebalanceCompleteCounter(0);



        prepareFilesSendRemove(fileToRemoveHashMap, finalSend);
    }

    private void prepareFilesSendRemove(HashMap<Integer, ArrayList<String>> fileToRemoveHashMap, HashMap<HashMap<Integer, String>, ArrayList<Integer>> finalSend) {
        for (int port : dataStoreSocketList.keySet()) {
            StringBuilder filesToSend = new StringBuilder();
            StringBuilder filesToRemove = new StringBuilder();
            AtomicInteger filesToSendCounter = new AtomicInteger();

            finalSend.keySet().stream()
                    .filter(map -> map.containsKey(port))
                    .forEach(map -> {
                        filesToSendCounter.getAndIncrement();
                        var file = map.get(port);
                        var ports = finalSend.get(map);
                        filesToSend.append(" ").append(file).append(" ").append(ports.size()).append(" ").append(getPortsToMessage(ports));
                    });



            filesToSend.insert(0, filesToSendCounter.get());
            var removeList = fileToRemoveHashMap.get(port);



            filesToRemove.append(removeList == null ? "0" : (removeList.size() + " " + String.join(" ", removeList)));



            sendRebalanceRequest(port, filesToSend.toString(), filesToRemove.toString());
        }
    }

    private String getPortsToMessage(ArrayList<Integer> portsList) {
        return portsList.stream()
                .map(Object::toString)
                .collect(Collectors.joining(" "));
    }



    private void sendRebalanceRequest(Integer port, String filesToSend, String filesToRemove) {
        try {
            var dataStore = dataStoreSocketList.get(port);
            var dataStorePrintWriter = new PrintWriter(dataStore.getOutputStream(), true);

            dataStorePrintWriter.println(Protocol.REBALANCE_TOKEN + " " + filesToSend + " " + filesToRemove);
            System.out.println(Protocol.REBALANCE_TOKEN + " " + filesToSend + " " + filesToRemove);



            setRebalanceCompleteCounter(getRebalanceCompleteCounter() + 1);
        } catch (IOException e) {
            System.out.print("Failed to send rebalance request: " + e.getMessage());
        }
    }

    private synchronized void updateIndexAfterRebalance() {
        for (String key : index.fileStatus.keySet()) {
            if (index.fileStatus.get(key) == index.Removing) {
                index.fileStatus.remove(key);
                index.fileNames.remove(key);
            }
        }
    }

    private synchronized void fixFailedDataStore(Integer port) {
        if (port == 0)
            return;



        dataStoreSocketList.remove(port);


        fileList.entrySet().stream()
                .filter(entry -> entry.getValue().contains(port))
                .forEach(entry -> {
                    entry.getValue().remove(port);

                    if (entry.getValue().size() == 1) {
                        String key = entry.getKey();

                        fileList.remove(key);
                        index.fileNames.remove(key);
                        index.fileStatus.remove(key);
                    }
                });
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }



    public int getCompletedStores() {
        return completedStores;
    }

    public void setCompletedStores(int cs) {
        completedStores = cs;
    }

    public int getLoadPortCounter() {
        return loadPortCounter;
    }

    public void setLoadPortCounter(int lpc) {
        loadPortCounter = lpc;
    }

    public int getRebalanceCompleteCounter() {
        return rebalanceCompleteCounter;
    }

    public void setRebalanceCompleteCounter(int re) {
        rebalanceCompleteCounter = re;
    }
}
