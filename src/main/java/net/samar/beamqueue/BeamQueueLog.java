package net.samar.beamqueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central logging for Beam Queue. Set DEBUG=true in config or env to enable verbose debug logs.
 */
public final class BeamQueueLog {

    public static final Logger LOG = LogManager.getLogger("BeamQueue");

    /** Verbose debug (prefix matching, tick phase, etc.). Enable via config or env BEAMQUEUE_DEBUG=true */
    private static boolean debugEnabled = false;

    static {
        String env = System.getenv("BEAMQUEUE_DEBUG");
        if ("true".equalsIgnoreCase(env) || "1".equals(env)) debugEnabled = true;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static void info(String msg) {
        LOG.info("[BeamQueue] {}", msg);
    }

    public static void info(String format, Object... args) {
        LOG.info("[BeamQueue] " + format, args);
    }

    public static void warn(String msg) {
        LOG.warn("[BeamQueue] {}", msg);
    }

    public static void warn(String format, Object... args) {
        LOG.warn("[BeamQueue] " + format, args);
    }

    public static void error(String msg, Throwable t) {
        LOG.error("[BeamQueue] {}", msg, t);
    }

    public static void error(String msg) {
        LOG.error("[BeamQueue] {}", msg);
    }

    /** Only logged when debug is enabled. */
    public static void debug(String msg) {
        if (debugEnabled) LOG.info("[BeamQueue:debug] {}", msg);
    }

    /** Only logged when debug is enabled. */
    public static void debug(String format, Object... args) {
        if (debugEnabled) LOG.info("[BeamQueue:debug] " + format, args);
    }
}
