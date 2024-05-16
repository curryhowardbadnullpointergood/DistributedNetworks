import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class Index {


    public int Stored = 235;
    public int Removed = 542;
    public int Storing = 719;
    public int Removing = 944;

    ArrayList<String> fileNames;
    ConcurrentHashMap<String, Integer> fileStatus;
    //ConcurrentHashMap<String, Integer> fileSizes;



// , ConcurrentHashMap<String,Integer> fileSize
    public Index(ArrayList<String> fileNames, ConcurrentHashMap<String, Integer> fileStatus) {
        this.fileNames = fileNames;
        this.fileStatus = fileStatus;
        //this.fileSizes = fileSize;
    }

    public ArrayList<String> getFileNames() {
        return fileNames;
    }

    public ConcurrentHashMap<String, Integer> getFileStatus() {
        return fileStatus;
    }

//    public ConcurrentHashMap<String, Integer> getFileSizes() {
//        return fileSizes;
//    }


    public void reset() {
        this.fileNames.clear();
        this.fileStatus.clear();
        // this.fileSizes.clear();
    }
}

