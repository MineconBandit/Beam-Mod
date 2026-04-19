package net.samar.beamqueue;

import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads API key and base URL from config/beamqueue.properties or env.
 * Default API URL: https://g4f.space/api/ollama/chat/completions
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
    /** g4f.space ollama provider endpoint. */
    private static final String DEFAULT_API_URL = "https://g4f.space/api/ollama/chat/completions";
    /** Empty by default so provider auto-selects model unless user sets one. */
    private static final String DEFAULT_MODEL = "";
    /** Hardcoded g4f.dev API key (override with config or env if needed). */
    private static final String HARDCODED_API_KEY = "g4f_u_mm3dtj_731391ec3875d2f03e5e6ed43c2feb0d48b099df8eaa9de2_6a6aaef4";

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

    /** AI model name. Empty by default so provider can auto-select. Config key: model */
    public static String getModel() {
        ensureLoaded();
        return (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
    }

    public static boolean hasApiKey() {
        ensureLoaded();
        return apiKey != null && !apiKey.isBlank();
    }

    public static synchronized boolean setApiKey(String newApiKey) {
        String normalized = newApiKey == null ? "" : newApiKey.trim();
        if (normalized.isBlank()) return false;
        ensureLoaded();
        apiKey = normalized;
        return saveApiKeyToConfig(normalized);
    }

    public static synchronized boolean setModel(String newModel) {
        String normalized = newModel == null ? "" : newModel.trim();
        if (normalized.isBlank()) return false;
        ensureLoaded();
        model = normalized;
        return saveModelToConfig(normalized);
    }

    public static synchronized boolean setApiUrl(String newApiUrl) {
        String normalized = newApiUrl == null ? "" : newApiUrl.trim();
        if (normalized.isBlank()) return false;
        ensureLoaded();
        apiUrl = normalized;
        return saveApiUrlToConfig(normalized);
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

    private static boolean saveApiKeyToConfig(String key) {
        Path configPath = getConfigPath();
        Properties p = new Properties();
        if (Files.isRegularFile(configPath)) {
            try (var in = Files.newInputStream(configPath)) {
                p.load(in);
            } catch (IOException e) {
                BeamQueueLog.warn("Failed reading existing config before writing API key: {}", e.getMessage());
            }
        }
        p.setProperty(KEY_API_KEY, key);
        p.remove(KEY_OPENAI_API_KEY);
        try {
            Files.createDirectories(configPath.getParent());
            try (var out = Files.newOutputStream(configPath)) {
                p.store(out, "Beam Queue config");
            }
            return true;
        } catch (IOException e) {
            BeamQueueLog.warn("Failed writing API key to config: {}", e.getMessage());
            return false;
        }
    }

    private static boolean saveModelToConfig(String modelName) {
        Path configPath = getConfigPath();
        Properties p = new Properties();
        if (Files.isRegularFile(configPath)) {
            try (var in = Files.newInputStream(configPath)) {
                p.load(in);
            } catch (IOException e) {
                BeamQueueLog.warn("Failed reading existing config before writing model: {}", e.getMessage());
            }
        }
        p.setProperty(KEY_MODEL, modelName);
        try {
            Files.createDirectories(configPath.getParent());
            try (var out = Files.newOutputStream(configPath)) {
                p.store(out, "Beam Queue config");
            }
            return true;
        } catch (IOException e) {
            BeamQueueLog.warn("Failed writing model to config: {}", e.getMessage());
            return false;
        }
    }

    private static boolean saveApiUrlToConfig(String url) {
        Path configPath = getConfigPath();
        Properties p = new Properties();
        if (Files.isRegularFile(configPath)) {
            try (var in = Files.newInputStream(configPath)) {
                p.load(in);
            } catch (IOException e) {
                BeamQueueLog.warn("Failed reading existing config before writing api_url: {}", e.getMessage());
            }
        }
        p.setProperty(KEY_API_URL, url);
        p.remove(KEY_OPENAI_API_URL);
        try {
            Files.createDirectories(configPath.getParent());
            try (var out = Files.newOutputStream(configPath)) {
                p.store(out, "Beam Queue config");
            }
            return true;
        } catch (IOException e) {
            BeamQueueLog.warn("Failed writing api_url to config: {}", e.getMessage());
            return false;
        }
    }

    private static Path getConfigPath() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Path runDir = (mc != null && mc.runDirectory != null)
            ? mc.runDirectory.toPath()
            : Path.of(".");
        return runDir.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
    }
}
