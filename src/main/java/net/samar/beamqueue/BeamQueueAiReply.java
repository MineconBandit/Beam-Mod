package net.samar.beamqueue;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calls g4f.dev API to generate a short reply for tournament FAQs.
 * Default: https://g4f.dev/api/groq/chat/completions with Llama 4 Maverick.
 * Event info: in 20 mins, feather-mc [dot] net, ~50 players, Discord donutskelesz.
 */
public final class BeamQueueAiReply {

    /** Default: Groq Llama 4 Maverick (meta-llama/llama-4-maverick-17b-128e-instruct). */
    private static final String DEFAULT_MODEL = "meta-llama/llama-4-maverick-17b-128e-instruct";

    private static final String SYSTEM_PROMPT =
        "You are replying to a player in-game. Your exact reply will be sent to them via /msg – write only the message we should send, nothing else. " +
        "Context: 2v2 sword PvP tournament. Event in 20 minutes. Server: feather-mc [dot] net. About 50 players. Discord: donutskelesz. " +
        "If they ask when -> say in 20 mins. Where -> say feather-mc [dot] net (write it exactly like that). How many -> about 50. Discord -> donutskelesz. " +
        "Keep it to one short sentence, brief and friendly.";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "beamqueue-ai");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * Sends the user message to the AI and, on success, sends the reply via /msg on the client thread.
     */
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 300;
    /** Skip sending the same reply to the same target within this window (stops duplicate sends). */
    private static final long SEND_DEDUPE_MS = 4000L;
    private static String lastSentTarget;
    private static String lastSentMessage;
    private static long lastSentTime;

    public static void requestReply(String userMessage, String targetPlayer) {
        // Groq endpoint doesn't require API key; others do
        if (!BeamQueueConfig.hasApiKey() && !isGroqEndpoint()) {
            BeamQueueLog.warn("AI reply skipped: no API key");
            return;
        }
        BeamQueueLog.info("AI request: target={} message=\"{}\"", targetPlayer, userMessage);

        EXECUTOR.submit(() -> {
            try {
                String reply = callApi(userMessage);
                if (reply == null || reply.isBlank()) {
                    reply = getFallbackReply(userMessage);
                    if (reply == null || reply.isBlank()) {
                        BeamQueueLog.warn("AI returned empty and no fallback for message");
                        return;
                    }
                    BeamQueueLog.info("Using fallback reply (API failed or empty): \"{}\"", reply);
                }
                String toSend = sanitizeForChat(reply);
                BeamQueueLog.info("AI reply ({} chars): \"{}\"", toSend.length(), toSend);
                String finalTarget = targetPlayer;
                MinecraftClient.getInstance().execute(() -> {
                    if (BeamQueueMod.targetPlayer != null && BeamQueueMod.targetPlayer.equals(finalTarget)) {
                        sendMsg(finalTarget, toSend);
                    } else {
                        BeamQueueLog.debug("AI reply not sent: target changed or null (current={})", BeamQueueMod.targetPlayer);
                    }
                });
            } catch (Exception e) {
                BeamQueueLog.error("AI request failed: " + e.getMessage(), e);
                String fallback = getFallbackReply(userMessage);
                if (fallback != null && !fallback.isBlank()) {
                    String ft = targetPlayer;
                    String toSend = sanitizeForChat(fallback);
                    MinecraftClient.getInstance().execute(() -> {
                        if (BeamQueueMod.targetPlayer != null && BeamQueueMod.targetPlayer.equals(ft)) {
                            sendMsg(ft, toSend);
                        }
                    });
                    BeamQueueLog.info("Sent fallback reply after API error");
                }
            }
        });
    }

    /** Hardcoded replies when API fails (e.g. 500) or returns empty. */
    private static String getFallbackReply(String userMessage) {
        if (userMessage == null) return null;
        String m = userMessage.toLowerCase().trim();
        if (m.contains("when") || m.contains("time")) return "in 20 mins";
        if (m.contains("where") || m.contains("server") || m.contains("serv") || m.contains("join")) return "feather-mc [dot] net";
        if (m.contains("how many") || m.contains("players") || m.contains("people")) return "about 50 players";
        if (m.contains("discord") || m.contains("dc")) return "donutskelesz on discord";
        // Decline-like / negative (e.g. "na im very bad") – friendly reply when API fails
        if (m.contains("na ") || m.startsWith("na") || m.contains("im bad") || m.contains("very bad")
            || m.contains("no ") || m.contains("nah") || m.contains("later") || m.contains("im good"))
            return "no worries, maybe next time";
        // Final fallback when API fails (matches working JS: "gimme a few bro")
        return "join feather-mc [dot] net in 20 mins";
    }

    private static boolean isGroqEndpoint() {
        String u = BeamQueueConfig.getApiUrl();
        return u != null && u.contains("groq");
    }

    private static String callApi(String userMessage) throws Exception {
        String url = BeamQueueConfig.getApiUrl();
        String modelName = BeamQueueConfig.getModel();
        // Match working JS: stream: true, stream_options; messages with user only or system+user (we keep system for context)
        String body = "{\"model\":\"" + escapeJson(modelName) + "\",\"messages\":[" +
            "{\"role\":\"system\",\"content\":\"" + escapeJson(SYSTEM_PROMPT) + "\"}," +
            "{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}" +
            "],\"stream\":true,\"stream_options\":{\"include_usage\":true}}";

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("Origin", "https://g4f.dev")
            .header("Referer", "https://g4f.dev/chat/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36")
            .header("sec-ch-ua", "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        // Groq endpoint does not use Authorization (per working JS)
        if (!isGroqEndpoint() && BeamQueueConfig.hasApiKey()) {
            builder.header("Authorization", "Bearer " + BeamQueueConfig.getOpenAiApiKey());
        }
        HttpRequest req = builder.build();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            BeamQueueLog.debug("AI API attempt {} status={} bodyLength={}", attempt, res.statusCode(), res.body().length());
            if (res.statusCode() == 200) {
                String content = extractStreamedContent(res.body());
                if (content != null && !content.isBlank()) return content;
                BeamQueueLog.warn("AI API returned empty streamed content");
                return null;
            }
            if (res.statusCode() == 429 || res.statusCode() == 500) {
                BeamQueueLog.warn("AI API {} (attempt {}/{}), retrying in {}ms", res.statusCode(), attempt, MAX_RETRIES, RETRY_DELAY_MS);
                if (attempt < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS);
                continue;
            }
            String bodyPreview = res.body().length() > 200 ? res.body().substring(0, 200) + "..." : res.body();
            BeamQueueLog.warn("AI API non-200: status={} body={}", res.statusCode(), bodyPreview);
            if (res.statusCode() == 404) {
                BeamQueueLog.warn("AI API 404: wrong api_url or model. Check config/beamqueue.properties.");
            }
            return null;
        }
        return null;
    }

    /** Parse SSE stream: lines "data: {...}", extract choices[0].delta.content. */
    private static String extractStreamedContent(String body) {
        if (body == null || body.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        String[] lines = body.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith("data: ")) continue;
            String raw = line.substring(6).trim();
            if ("[DONE]".equals(raw)) continue;
            try {
                String delta = extractDeltaContent(raw);
                if (delta != null && !delta.isEmpty()) out.append(delta);
            } catch (Exception ignored) { }
        }
        return out.length() > 0 ? out.toString().trim() : null;
    }

    /** Get choices[0].delta.content from a single SSE JSON chunk (no full JSON parser). */
    private static String extractDeltaContent(String json) {
        // Look for "delta":{"content":"..."} or "content":"..." after "delta"
        int deltaIdx = json.indexOf("\"delta\"");
        if (deltaIdx == -1) return null;
        int contentIdx = json.indexOf("\"content\":\"", deltaIdx);
        if (contentIdx == -1) return null;
        contentIdx += "\"content\":\"".length();
        StringBuilder sb = new StringBuilder();
        for (int i = contentIdx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i++; continue; }
                if (next == 'n') { sb.append('\n'); i++; continue; }
                if (next == '\\') { sb.append('\\'); i++; continue; }
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    /** Extract "content":"...\" from OpenAI-style JSON. */
    private static String extractContent(String json) {
        String marker = "\"content\":\"";
        int start = json.indexOf(marker);
        if (start == -1) return null;
        start += marker.length();
        StringBuilder out = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { out.append('"'); i++; continue; }
                if (next == 'n') { out.append('\n'); i++; continue; }
                if (next == '\\') { out.append('\\'); i++; continue; }
            }
            if (c == '"') break;
            out.append(c);
        }
        return out.toString().trim();
    }

    /** Sanitize reply for chat: newlines -> space, and replace literal domain with [dot] so server filter doesn't block. */
    private static String sanitizeForChat(String s) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        // AI may return "feather-mc.net" – replace with [dot] so filter allows it (case-insensitive)
        s = s.replaceAll("(?i)feather-mc\\.net", "feather-mc [dot] net");
        return s;
    }

    private static void sendMsg(String target, String text) {
        long now = System.currentTimeMillis();
        synchronized (BeamQueueAiReply.class) {
            if (target != null && target.equals(lastSentTarget) && text != null && text.equals(lastSentMessage)
                && (now - lastSentTime) < SEND_DEDUPE_MS) {
                BeamQueueLog.debug("Send dedupe: same reply to {} already sent recently, skipping", target);
                return;
            }
            lastSentTarget = target;
            lastSentMessage = text;
            lastSentTime = now;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            BeamQueueLog.warn("sendMsg skipped: player null");
            return;
        }
        String cmd = "msg " + target + " " + text;
        if (cmd.length() > 256) cmd = cmd.substring(0, 253) + "...";
        client.player.networkHandler.sendChatCommand(cmd);
        BeamQueueLog.info("Sent /msg to {} ({} chars)", target, text.length());
    }
}
