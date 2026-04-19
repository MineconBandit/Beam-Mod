package net.samar.beamqueue;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Calls g4f.dev API to generate a short reply for tournament FAQs.
 * Default: https://g4f.dev/api/groq/chat/completions with Llama 4 Maverick.
 * Event info: in 20 mins, configurable server IP, ~50 players, Discord donutskelesz.
 */
public final class BeamQueueAiReply {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "beamqueue-ai");
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService HEALTH_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "beamqueue-ai-health");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean HEALTH_STARTED = new AtomicBoolean(false);
    private static volatile String healthLabel = "Checking...";
    private static volatile int healthColor = 0xFFD76A;
    private static volatile int consecutiveHealthFailures = 0;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * Sends the user message to the AI and, on success, sends the reply via /msg on the client thread.
     */
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_BASE_DELAY_MS = 2000;
    private static final int RETRY_MAX_DELAY_MS = 12000;
    private static final int REQUEST_TIMEOUT_SEC = 35;
    private static final long MIN_API_CALL_GAP_MS = 3000L;
    private static final int HEALTH_CHECK_INITIAL_DELAY_SEC = 5;
    private static final int HEALTH_CHECK_INTERVAL_SEC = 120;
    private static final int HEALTH_FAILURES_FOR_DOWN = 3;
    private static final int RATE_LIMIT_DEFAULT_BACKOFF_MS = 60000;
    private static final int RATE_LIMIT_HEALTH_COLOR = 0xFFAA33;
    /** Skip sending the same reply to the same target within this window (stops duplicate sends). */
    private static final long SEND_DEDUPE_MS = 4000L;
    private static final int RECENT_REPLY_HISTORY = 8;
    private static String lastSentTarget;
    private static String lastSentMessage;
    private static long lastSentTime;
    private static final String[] recentReplies = new String[RECENT_REPLY_HISTORY];
    private static int recentRepliesCount = 0;
    private static int recentRepliesCursor = 0;
    private static int variationCursor = 0;
    private static long lastApiCallTime = 0L;
    private static volatile long rateLimitedUntilMs = 0L;

    public static void startHealthChecks() {
        if (!HEALTH_STARTED.compareAndSet(false, true)) return;
        HEALTH_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                runHealthCheck();
            } catch (Throwable e) {
                markHealthFailure();
                BeamQueueLog.warn("AI health check failed: {}", e.getMessage());
            }
        }, HEALTH_CHECK_INITIAL_DELAY_SEC, HEALTH_CHECK_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    public static void triggerHealthCheckNow() {
        HEALTH_EXECUTOR.execute(() -> {
            try {
                runHealthCheck();
            } catch (Throwable e) {
                markHealthFailure();
                BeamQueueLog.warn("AI immediate health check failed: {}", e.getMessage());
            }
        });
    }

    public static String getHealthLabel() {
        return healthLabel;
    }

    public static int getHealthColor() {
        return healthColor;
    }

    public static void requestReply(String userMessage, String targetPlayer) {
        BeamQueueLog.info("AI request: target={} message=\"{}\"", targetPlayer, userMessage);

        EXECUTOR.submit(() -> {
            try {
                if (isRateLimitedNow()) {
                    BeamQueueLog.warn("AI request skipped due to active rate-limit backoff");
                    String fallback = getFallbackReply(userMessage);
                    if (fallback == null || fallback.isBlank()) return;
                    String ft = targetPlayer;
                    String toSend = ensureVariedReply(sanitizeForChat(fallback), userMessage);
                    MinecraftClient.getInstance().execute(() -> {
                        if (BeamQueueMod.targetPlayer != null && BeamQueueMod.targetPlayer.equals(ft)) {
                            sendMsg(ft, toSend);
                        }
                    });
                    return;
                }
                String raw = callApi(userMessage);
                if (raw == null || raw.isBlank()) {
                    raw = getFallbackReply(userMessage);
                    String reply = extractReplyText(raw);
                    if (reply == null || reply.isBlank()) {
                        BeamQueueLog.warn("AI returned empty and no fallback for message");
                        return;
                    }
                    BeamQueueLog.info("Using fallback reply (API failed or empty): \"{}\"", reply);
                }
                AiIntent intent = extractIntent(raw, userMessage);
                String parsedReply = extractReplyText(raw);
                if (parsedReply == null || parsedReply.isBlank()) {
                    parsedReply = getFallbackReply(userMessage);
                }
                if (looksLikeAiRefusal(parsedReply)) {
                    BeamQueueLog.warn("AI refusal-like output detected; switching to fallback reply");
                    parsedReply = getFallbackReply(userMessage);
                }
                String toSend = ensureVariedReply(sanitizeForChat(parsedReply), userMessage);
                BeamQueueLog.info("AI reply ({} chars): \"{}\"", toSend.length(), toSend);
                String finalTarget = targetPlayer;
                MinecraftClient.getInstance().execute(() -> {
                    if (BeamQueueMod.targetPlayer != null && BeamQueueMod.targetPlayer.equals(finalTarget)) {
                        if (intent == AiIntent.DECLINED) {
                            BeamQueueLog.info("AI intent=DECLINED for target={} -> leave and requeue", finalTarget);
                            BeamQueueMod.handleAiDecline(finalTarget, toSend);
                            return;
                        }
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
                    String toSend = ensureVariedReply(sanitizeForChat(fallback), userMessage);
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

    private static void runHealthCheck() {
        if (isRateLimitedNow()) {
            consecutiveHealthFailures = 0;
            setHealth("Rate Limited", RATE_LIMIT_HEALTH_COLOR);
            return;
        }
        try {
            // Keep health probes light so they don't amplify rate-limit pressure.
            String content = callApi("ping", 2);
            if (content != null && !content.isBlank()) {
                consecutiveHealthFailures = 0;
                setHealth("Working", 0x55FF55);
            } else {
                markHealthFailure();
                BeamQueueLog.warn("AI health check returned empty content");
            }
        } catch (Exception e) {
            markHealthFailure();
            BeamQueueLog.warn("AI health request error: {}", e.getMessage());
        }
    }

    private static void markHealthFailure() {
        consecutiveHealthFailures++;
        if (consecutiveHealthFailures >= HEALTH_FAILURES_FOR_DOWN) {
            setHealth("Not Working", 0xFF5555);
        }
    }

    private static void setHealth(String label, int color) {
        healthLabel = label;
        healthColor = color;
    }

    /** Hardcoded replies when API fails (e.g. 500) or returns empty. */
    private static String getFallbackReply(String userMessage) {
        if (userMessage == null) return null;
        String shareTarget = BeamQueueMod.getShareTargetMasked();
        String m = userMessage.toLowerCase().trim();
        if (m.contains("when") || m.contains("time")) return "in 20 mins";
        if (m.contains("where") || m.contains("server") || m.contains("serv") || m.contains("join")) return shareTarget;
        if (m.contains("how many") || m.contains("players") || m.contains("people")) return "about 50 players";
        if (m.contains("discord") || m.contains("dc")) {
            if (BeamQueueMod.isShareModeDiscord()) return shareTarget;
            return "donutskelesz on discord";
        }
        // Decline-like / negative (e.g. "na im very bad") â€“ friendly reply when API fails
        if (m.contains("na ") || m.startsWith("na") || m.contains("im bad") || m.contains("very bad")
            || m.contains("no ") || m.contains("nah") || m.contains("later") || m.contains("im good"))
            return "no worries, maybe next time";
        // Final fallback when API fails (matches working JS: "gimme a few bro")
        return "join " + shareTarget + " in 20 mins";
    }

    private static String callApi(String userMessage) throws Exception {
        return callApi(userMessage, MAX_RETRIES);
    }

    private static String callApi(String userMessage, int maxRetries) throws Exception {
        String url = BeamQueueConfig.getApiUrl();
        String systemPrompt = buildSystemPrompt();
        enforceApiGap();
        String body = "{\"messages\":[" +
            "{\"role\":\"system\",\"content\":\"" + escapeJson(systemPrompt) + "\"}," +
            "{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}" +
            "],\"stream\":false}";

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
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpRequest req = builder.build();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpResponse<String> res;
            try {
                res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpTimeoutException timeout) {
                int retryDelayMs = computeRetryDelayMs(attempt);
                if (attempt < maxRetries) {
                    BeamQueueLog.warn("AI API timeout (attempt {}/{}), retrying in {}ms", attempt, maxRetries, retryDelayMs);
                    Thread.sleep(retryDelayMs);
                    continue;
                }
                BeamQueueLog.warn("AI API timeout (attempt {}/{}), retries exhausted", attempt, maxRetries);
                throw timeout;
            }
            BeamQueueLog.debug("AI API attempt {} status={} bodyLength={}", attempt, res.statusCode(), res.body().length());
            if (res.statusCode() == 200) {
                String content = extractContentFromAnyShape(res.body());
                if (content != null && !content.isBlank()) return content;
                BeamQueueLog.warn("AI API returned empty content");
                return null;
            }
            if (res.statusCode() == 429) {
                int retryAfterMs = parseRetryAfterMillis(res);
                int backoffMs = retryAfterMs > 0 ? retryAfterMs : RATE_LIMIT_DEFAULT_BACKOFF_MS;
                markRateLimited(backoffMs);
                String bodyPreview = res.body().length() > 200 ? res.body().substring(0, 200) + "..." : res.body();
                BeamQueueLog.warn("AI API 429 rate-limited: backing off for {}ms. body={}", backoffMs, bodyPreview);
                return null;
            }
            if (res.statusCode() == 500) {
                int retryDelayMs = computeRetryDelayMs(attempt);
                int effectiveDelayMs = retryDelayMs;
                if (attempt < maxRetries) {
                    BeamQueueLog.warn("AI API {} (attempt {}/{}), retrying in {}ms", res.statusCode(), attempt, maxRetries, effectiveDelayMs);
                    Thread.sleep(effectiveDelayMs);
                } else {
                    BeamQueueLog.warn("AI API {} (attempt {}/{}), retries exhausted", res.statusCode(), attempt, maxRetries);
                }
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

    private static String extractContentFromAnyShape(String body) {
        if (body == null || body.isBlank()) return null;
        // Some providers still return SSE-like chunks, others return plain JSON.
        String sse = extractStreamedContent(body);
        if (sse != null && !sse.isBlank()) return sse;
        String jsonChoice = extractMessageContent(body);
        if (jsonChoice != null && !jsonChoice.isBlank()) return jsonChoice;
        String fallback = extractContent(body);
        if (fallback != null && !fallback.isBlank()) return fallback;
        return null;
    }

    private static boolean isRateLimitedNow() {
        return System.currentTimeMillis() < rateLimitedUntilMs;
    }

    private static void markRateLimited(int backoffMs) {
        long now = System.currentTimeMillis();
        long next = now + Math.max(1000L, (long) backoffMs);
        if (next > rateLimitedUntilMs) {
            rateLimitedUntilMs = next;
        }
    }

    private static void enforceApiGap() throws InterruptedException {
        long now = System.currentTimeMillis();
        long waitMs = 0L;
        synchronized (BeamQueueAiReply.class) {
            long sinceLast = now - lastApiCallTime;
            if (sinceLast > 0 && sinceLast < MIN_API_CALL_GAP_MS) {
                waitMs = MIN_API_CALL_GAP_MS - sinceLast;
            }
            lastApiCallTime = now + waitMs;
        }
        if (waitMs > 0) {
            BeamQueueLog.debug("AI rate-limit guard: waiting {}ms before API call", waitMs);
            Thread.sleep(waitMs);
        }
    }

    private static int computeRetryDelayMs(int attempt) {
        long exp = (long) RETRY_BASE_DELAY_MS * (1L << Math.max(0, attempt - 1));
        long capped = Math.min(exp, RETRY_MAX_DELAY_MS);
        long jitter = ThreadLocalRandom.current().nextLong(250L, 1001L);
        long withJitter = Math.min((long) RETRY_MAX_DELAY_MS, capped + jitter);
        return (int) withJitter;
    }

    private static int parseRetryAfterMillis(HttpResponse<String> res) {
        try {
            String retryAfter = res.headers().firstValue("retry-after").orElse(null);
            if (retryAfter == null || retryAfter.isBlank()) return 0;
            int seconds = Integer.parseInt(retryAfter.trim());
            if (seconds <= 0) return 0;
            long ms = seconds * 1000L;
            return (int) Math.min(Integer.MAX_VALUE, ms);
        } catch (Exception ignored) {
            return 0;
        }
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

    /** Extract choices[0].message.content from standard non-stream chat completion JSON. */
    private static String extractMessageContent(String json) {
        if (json == null || json.isBlank()) return null;
        int choicesIdx = json.indexOf("\"choices\"");
        if (choicesIdx == -1) return null;
        int messageIdx = json.indexOf("\"message\"", choicesIdx);
        if (messageIdx == -1) return null;
        int contentIdx = json.indexOf("\"content\":\"", messageIdx);
        if (contentIdx == -1) return null;
        contentIdx += "\"content\":\"".length();
        StringBuilder out = new StringBuilder();
        for (int i = contentIdx; i < json.length(); i++) {
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

    private enum AiIntent {
        INTERESTED,
        DECLINED,
        UNSURE
    }

    private static AiIntent extractIntent(String raw, String userMessage) {
        if (raw == null) return inferIntentFromUserMessage(userMessage);
        String lower = raw.toLowerCase();
        int idx = lower.indexOf("intent=");
        if (idx == -1) idx = lower.indexOf("intent:");
        if (idx != -1) {
            String after = lower.substring(idx + "intent".length());
            after = after.replaceFirst("^[=:]\\s*", "");
            int end = after.indexOf(';');
            String token = (end >= 0 ? after.substring(0, end) : after).trim();
            if (token.startsWith("interested")) return AiIntent.INTERESTED;
            if (token.startsWith("declined")) return AiIntent.DECLINED;
            if (token.startsWith("unsure")) return AiIntent.UNSURE;
        }
        return inferIntentFromUserMessage(userMessage);
    }

    private static String extractReplyText(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        String lower = text.toLowerCase();
        int idx = lower.indexOf("reply=");
        if (idx == -1) idx = lower.indexOf("reply:");
        if (idx != -1) {
            String out = text.substring(idx + "reply".length());
            out = out.replaceFirst("^[=:]\\s*", "").trim();
            if (!out.isEmpty()) return out;
        }
        int sep = text.indexOf(';');
        if (sep >= 0 && sep + 1 < text.length()) {
            String rest = text.substring(sep + 1).trim();
            if (!rest.isEmpty()) return rest;
        }
        return text;
    }

    private static AiIntent inferIntentFromUserMessage(String userMessage) {
        if (userMessage == null) return AiIntent.UNSURE;
        String m = userMessage.toLowerCase().trim();
        if (m.contains("yes") || m.contains("yeah") || m.contains("sure")
            || m.contains("ok") || m.contains("join")) {
            return AiIntent.INTERESTED;
        }
        if (m.equals("no") || m.equals("nope") || m.equals("nah") || m.equals("na")
            || m.contains("no thanks") || m.contains("not interested")
            || m.contains("im good") || m.contains("i'm good")
            || m.contains("later") || m.contains("nvm") || m.contains("never mind")
            || m.contains("don't want") || m.contains("dont want")) {
            return AiIntent.DECLINED;
        }
        return AiIntent.UNSURE;
    }

    /** Sanitize reply for chat: newlines -> space, and replace literal domain with [dot] so server filter doesn't block. */
    private static String sanitizeForChat(String s) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        String sharePlain = BeamQueueMod.getShareTargetPlain();
        String shareMasked = BeamQueueMod.getShareTargetMasked();
        s = s.replaceAll("(?i)" + java.util.regex.Pattern.quote(sharePlain), shareMasked);
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
        BeamQueueMod.sendThrottledPrivateMessage(target, text);
        BeamQueueLog.info("Queued throttled /msg to {} ({} chars)", target, text.length());
    }

    private static String ensureVariedReply(String candidate, String userMessage) {
        String clean = sanitizeForChat(candidate);
        if (clean.isBlank()) return clean;
        if (looksLikeIntroReask(clean) && !userAskedJoinRelated(userMessage)) {
            clean = "alr";
        }
        synchronized (BeamQueueAiReply.class) {
            if (!looksRepeated(clean)) {
                rememberReply(clean);
                return clean;
            }
            String[] variants = buildIntentVariants(userMessage);
            for (int i = 0; i < variants.length; i++) {
                String option = sanitizeForChat(variants[(variationCursor + i) % variants.length]);
                if (!option.isBlank() && !looksRepeated(option)) {
                    variationCursor = (variationCursor + i + 1) % Math.max(1, variants.length);
                    rememberReply(option);
                    BeamQueueLog.debug("Reply variation guard: replaced repetitive reply \"{}\" -> \"{}\"", clean, option);
                    return option;
                }
            }
            // Last resort: still send, but we keep it in history so future turns can diversify.
            rememberReply(clean);
            return clean;
        }
    }

    private static String[] buildIntentVariants(String userMessage) {
        String m = userMessage == null ? "" : userMessage.toLowerCase().trim();
        String shareTarget = BeamQueueMod.getShareTargetMasked();
        if (m.contains("when") || m.contains("time")) {
            return new String[]{"in 20 mins", "alr starts in 20 mins", "20 mins from now fam", "bet, goes live in 20 mins"};
        }
        if (m.contains("where") || m.contains("server") || m.contains("serv") || m.contains("join")) {
            return new String[]{shareTarget, "yo its " + shareTarget, "alr pull up on " + shareTarget, "bet bet, " + shareTarget};
        }
        if (m.contains("how many") || m.contains("players") || m.contains("people")) {
            return new String[]{"about 50 players", "around 50 ppl rn", "close to 50 players", "like 50 players there"};
        }
        if (m.contains("discord") || m.contains("dc")) {
            if (BeamQueueMod.isShareModeDiscord()) {
                return new String[]{shareTarget, "join " + shareTarget, "its " + shareTarget, "pull up " + shareTarget};
            }
            return new String[]{"donutskelesz on discord", "add donutskelesz on dc", "dc is donutskelesz", "yo add donutskelesz on discord"};
        }
        if (m.contains("na ") || m.startsWith("na") || m.contains("im bad") || m.contains("very bad")
            || m.contains("no ") || m.contains("nah") || m.contains("later") || m.contains("im good")) {
            return new String[]{"alr gg maybe next time", "yo all good maybe next one", "bet bet no stress", "alr nw, maybe later"};
        }
        return new String[]{"alr bet", "yo sure", "bet bet", "say less", "how u doin fam"};
    }

    private static boolean looksLikeIntroReask(String text) {
        if (text == null) return false;
        String t = text.toLowerCase();
        return (t.contains("2v2") || t.contains("tournament") || t.contains("tourny") || t.contains("wanna join")
            || t.contains("tryna join") || t.contains("u down")) && !t.contains("no worries");
    }

    private static boolean userAskedJoinRelated(String userMessage) {
        if (userMessage == null) return false;
        String m = userMessage.toLowerCase();
        return m.contains("where") || m.contains("server") || m.contains("join") || m.contains("ip")
            || m.contains("discord") || m.contains("dc");
    }

    private static boolean looksRepeated(String candidate) {
        String normalized = normalizeForSimilarity(candidate);
        if (normalized.isEmpty()) return false;
        for (int i = 0; i < recentRepliesCount; i++) {
            String prev = recentReplies[i];
            if (prev == null || prev.isBlank()) continue;
            if (isSimilar(normalized, normalizeForSimilarity(prev))) return true;
        }
        return false;
    }

    private static void rememberReply(String reply) {
        recentReplies[recentRepliesCursor] = reply;
        recentRepliesCursor = (recentRepliesCursor + 1) % RECENT_REPLY_HISTORY;
        if (recentRepliesCount < RECENT_REPLY_HISTORY) recentRepliesCount++;
    }

    private static boolean isSimilar(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() >= 8 && b.length() >= 8 && (a.contains(b) || b.contains(a))) return true;
        String[] ta = a.split(" ");
        String[] tb = b.split(" ");
        if (ta.length < 2 || tb.length < 2) return false;
        int common = 0;
        for (String x : ta) {
            for (String y : tb) {
                if (x.equals(y)) {
                    common++;
                    break;
                }
            }
        }
        int max = Math.max(ta.length, tb.length);
        double overlap = (double) common / (double) max;
        return overlap >= 0.75;
    }

    private static String normalizeForSimilarity(String s) {
        if (s == null) return "";
        String out = s.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return out;
    }

    private static boolean looksLikeAiRefusal(String reply) {
        if (reply == null) return false;
        String r = reply.toLowerCase().trim();
        if (r.isEmpty()) return false;
        return r.contains("as an ai")
            || r.contains("i can't assist")
            || r.contains("i cannot assist")
            || r.contains("i'm unable to")
            || r.contains("i apologize")
            || r.contains("personal information")
            || r.contains("i don't have the ability");
    }

    private static String buildSystemPrompt() {
        String shareTarget = BeamQueueMod.getShareTargetMasked();
        String modeLabel = BeamQueueMod.isShareModeDiscord() ? "discord invite" : "server ip";
        return "You are trying to get the player to join via this " + modeLabel + ": " + shareTarget + ". " +
            "All chat conversation from that player is forwarded to you. " +
            "Classify intent and provide a short reply in this exact format: INTENT=<INTERESTED|DECLINED|UNSURE>; REPLY=<text>. " +
            "Output exactly one line using that format. " +
            " " +
            "If the player clearly does not want to join, set INTENT=DECLINED. " +
            "If they clearly want to join, set INTENT=INTERESTED. " +
            "Otherwise set INTENT=UNSURE. " +
            " " +
            "Reply to the latest message directly first, never ignore what they said. " +
            " " +
            "Tone: casual, human, slightly imperfect, like a real Minecraft player. " +
            "Keep replies to one short sentence (max ~12-14 words). " +
            "Use slang sometimes (alr, yea, idk, lol) but not every message. " +
            "Vary wording and avoid repeating phrases or structure. " +
            "Do not sound like an ad, script, or tryhard promoter. " +
            "Sometimes be dry, short, or slightly uninterested if it fits. " +
            " " +
            "Only mention the tournament if it feels natural, don't force it. " +
            "Do not repeat tournament details unless asked. " +
            " " +
            "If asked identity/source, keep it casual and vague. " +
            "If asked when -> say in 20 mins. " +
            "If asked where/server/join -> say " + shareTarget + " exactly like that. " +
            "If asked player count -> say around 50 ppl. " +
            "If asked discord -> if mode is discord invite, share " + shareTarget + "; else say donutskelesz. " +
            "If unsure -> reply vaguely like idk tbh. " +
            "The whole conversation between you (AI) and the player is sent to you, so reply naturally. " +
            "Do not repeat the same phrases. " +
            "Two messages are hardcoded and sent before AI chat starts: 'hi' and 'theres a 2v2 pvp tournament wanna join?'. " +
            "Use those as context to keep replies natural. " +
            "Avoid being overly helpful or overly consistent; act like a normal player.";
    }
}
