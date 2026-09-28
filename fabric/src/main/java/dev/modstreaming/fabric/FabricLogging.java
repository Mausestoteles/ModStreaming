package dev.modstreaming.fabric;

import dev.modstreaming.core.log.StreamLogger;

/** Creates a {@link StreamLogger} backed by SLF4J when available (always the case in Fabric), else the JDK logger. */
final class FabricLogging {

    private FabricLogging() {
    }

    static StreamLogger create(String name) {
        try {
            Class.forName("org.slf4j.LoggerFactory", false, FabricLogging.class.getClassLoader());
            return new Slf4jStreamLogger(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return StreamLogger.system();
        }
    }

    private static final class Slf4jStreamLogger implements StreamLogger {
        private final org.slf4j.Logger logger;

        Slf4jStreamLogger(String name) {
            this.logger = org.slf4j.LoggerFactory.getLogger(name);
        }

        @Override
        public void debug(String message) {
            logger.debug(message);
        }

        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void warn(String message) {
            logger.warn(message);
        }

        @Override
        public void error(String message, Throwable error) {
            if (error != null) {
                logger.error(message, error);
            } else {
                logger.error(message);
            }
        }
    }
}
