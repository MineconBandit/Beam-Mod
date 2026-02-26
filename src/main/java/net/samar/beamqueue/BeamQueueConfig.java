package net.samar.beamqueue;

import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads API key and base URL from config/beamqueue.properties or env.
 * Defaults to g4f.dev Groq endpoint (no key required): https://g4f.dev/api/groq/chat/completions
 * Env: BEAMQUEUE_API_KEY or BEAMQUEUE_OPENAI_API_KEY.
 */
public final class BeamQueueConfig {

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = "beamqueue.properties";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_OPENAI_API_KEY = "openai_api_key";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_OPENAI_API_URL = "openai_api_url";
    private static final String KEY_MODEL = "model";
    private static final String KEY_DEBUG = "debug";
    /** g4f.space Groq endpoint (g4f.dev returns 405 for server-side requests). No API key required. */
    private static final String DEFAULT_API_URL = "https://g4f.space/api/groq/v1/chat/completions";
    /** Hardcoded g4f.dev API key (override with config or env if needed). */
    private static final String HARDCODED_API_KEY = "g4f_u_mm3dtj_0791feb27c48700d17de45c6c35d43530844857daa2e4782_2b585d83";

    private static String apiKey;
    private static String apiUrl;
    private static String model;
    private static boolean loaded;

    public static String getOpenAiApiKey() {
        ensureLoaded();
        return apiKey;
    }

    public static String getApiUrl() {
        ensureLoaded();
        return (apiUrl != null && !apiUrl.isBlank()) ? apiUrl : DEFAULT_API_URL;
    }

    /** AI model name. Default: Groq Llama 4. Config key: model */
    public static String getModel() {
        ensureLoaded();
        return (model != null && !model.isBlank()) ? model : "meta-llama/llama-4-maverick-17b-128e-instruct";
    }

    public static boolean hasApiKey() {
        ensureLoaded();
        return apiKey != null && !apiKey.isBlank();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        apiKey = System.getenv("BEAMQUEUE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("BEAMQUEUE_OPENAI_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            apiUrl = null;
            return;
        }

        Path runDir = MinecraftClient.getInstance().runDirectory.toPath();
        Path configPath = runDir.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
        if (Files.isRegularFile(configPath)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(configPath)) {
                p.load(in);
            } catch (IOException ignored) {
                // fall through to hardcoded
            }
            apiKey = p.getProperty(KEY_API_KEY);
            if (apiKey == null || apiKey.isBlank()) apiKey = p.getProperty(KEY_OPENAI_API_KEY);
            apiUrl = p.getProperty(KEY_API_URL);
            if (apiUrl == null || apiUrl.isBlank()) apiUrl = p.getProperty(KEY_OPENAI_API_URL);
            model = p.getProperty(KEY_MODEL);
            String debugVal = p.getProperty(KEY_DEBUG);
            if (debugVal != null) BeamQueueLog.setDebugEnabled("true".equalsIgnoreCase(debugVal.trim()));
        }
        if (apiKey == null || apiKey.isBlank()) apiKey = HARDCODED_API_KEY;
    }
}
