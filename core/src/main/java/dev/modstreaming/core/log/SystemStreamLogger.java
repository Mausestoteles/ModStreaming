package dev.modstreaming.core.log;

final class SystemStreamLogger implements StreamLogger {

    static final SystemStreamLogger INSTANCE = new SystemStreamLogger();

    private final System.Logger logger = System.getLogger("ModStreaming");

    @Override
    public void debug(String message) {
        logger.log(System.Logger.Level.DEBUG, message);
    }

    @Override
    public void info(String message) {
        logger.log(System.Logger.Level.INFO, message);
    }

    @Override
    public void warn(String message) {
        logger.log(System.Logger.Level.WARNING, message);
    }

    @Override
    public void error(String message, Throwable error) {
        if (error != null) {
            logger.log(System.Logger.Level.ERROR, message, error);
        } else {
            logger.log(System.Logger.Level.ERROR, message);
        }
    }
}
