package net.samar.beamqueue;

import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persists usernames we've messaged, ensuring each username is stored once.
 */
public final class BeamQueueMessagedUsers {

    private static final String FILE_NAME = "beamqueue_messaged_users.txt";

    private static final Set<String> originalNames = new LinkedHashSet<>();
    private static final Set<String> lowerNames = new LinkedHashSet<>();
    private static final Set<String> blockedLowerNames = new LinkedHashSet<>();
    private static boolean loaded = false;

    private BeamQueueMessagedUsers() {
    }

    public static void recordUsername(String username) {
        if (username == null) return;
        String clean = username.trim();
        if (clean.isEmpty()) return;
        synchronized (BeamQueueMessagedUsers.class) {
            ensureLoaded();
            String lower = clean.toLowerCase(Locale.ROOT);
            if (blockedLowerNames.contains(lower)) return;
            if (lowerNames.contains(lower)) return;
            lowerNames.add(lower);
            originalNames.add(clean);
            appendToFile(clean);
            BeamQueueLog.info("Saved messaged username: {}", clean);
        }
    }

    public static void markNegativeReply(String username) {
        if (username == null) return;
        String clean = username.trim();
        if (clean.isEmpty()) return;
        synchronized (BeamQueueMessagedUsers.class) {
            ensureLoaded();
            String lower = clean.toLowerCase(Locale.ROOT);
            blockedLowerNames.add(lower);
            boolean removedLower = lowerNames.remove(lower);
            boolean removedOriginal = originalNames.removeIf(name -> name.equalsIgnoreCase(clean));
            if (removedLower || removedOriginal) {
                rewriteFile();
                BeamQueueLog.info("Removed username after negative reply: {}", clean);
            }
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        for (Path file : getFilePaths()) {
            if (!Files.isRegularFile(file)) continue;
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String clean = line == null ? "" : line.trim();
                    if (clean.isEmpty()) continue;
                    String lower = clean.toLowerCase(Locale.ROOT);
                    if (!lowerNames.contains(lower)) {
                        lowerNames.add(lower);
                        originalNames.add(clean);
                    }
                }
            } catch (IOException e) {
                BeamQueueLog.warn("Failed to load saved usernames from '{}': {}", file, e.getMessage());
            }
        }
    }

    private static void appendToFile(String username) {
        boolean savedAnywhere = false;
        for (Path file : getFilePaths()) {
            try {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(
                    file,
                    (username + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );
                savedAnywhere = true;
            } catch (IOException e) {
                BeamQueueLog.warn("Failed to save messaged username '{}' to '{}': {}", username, file, e.getMessage());
            }
        }
        if (!savedAnywhere) {
            BeamQueueLog.warn("Failed to save messaged username '{}' to all configured paths", username);
        }
    }

    private static void rewriteFile() {
        StringBuilder data = new StringBuilder();
        for (String name : originalNames) {
            data.append(name).append(System.lineSeparator());
        }
        for (Path file : getFilePaths()) {
            try {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(
                    file,
                    data.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
            } catch (IOException e) {
                BeamQueueLog.warn("Failed to rewrite saved usernames file '{}': {}", file, e.getMessage());
            }
        }
    }

    private static List<Path> getFilePaths() {
        Path runDir = MinecraftClient.getInstance().runDirectory.toPath();
        List<Path> out = new ArrayList<>(2);
        out.add(runDir.resolve(FILE_NAME));
        out.add(runDir.resolve("config").resolve(FILE_NAME));
        return out;
    }
}
