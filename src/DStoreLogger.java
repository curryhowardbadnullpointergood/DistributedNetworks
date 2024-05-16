

public class DStoreLogger extends Logger {

    private static final String LOG_FILE_SUFFIX = "dstore";

    private final String logFileSuffix;


    protected DStoreLogger(LoggingType loggingType, int port ) {
        super(loggingType);
        logFileSuffix = LOG_FILE_SUFFIX + "_" + port;
    }

    @Override
    protected String getLogFileSuffix() {
        return logFileSuffix;
    }

}
