package net.samar.beamqueue;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BeamQueueMod implements ClientModInitializer {

    public static boolean active = false;
    public static String targetPlayer = null;
    public static int ticksSinceStart = 0;
    /** Number of scan attempts so far (0–3); we do 4 total scans after 7s wait + 6s move. */
    public static int scanAttempts = 0;
    /** Tick when we set targetPlayer (so we send tournament msg 1s later). */
    public static int targetSetTick = 0;
    public static boolean tournamentSent = false;
    /** After target declines or player death: /leave, then wait 10s and restart beam. */
    public static boolean restartAfterLeave = false;
    public static int leaveWaitTicks = 0;
    /** Death detected from chat message (e.g. "X was slain by Y"): wait 4s then /leave and restart. */
    private static boolean deathMessageSeen = false;
    private static int deathWaitTicks = 0;
    private static final int TICKS_4_SEC = 80;
    /** Queue cooldown (e.g. "You are on queue cooldown for 6m, 54s..."): wait that long then startBeamAgain. */
    private static boolean queueCooldownActive = false;
    private static int queueCooldownTicks = 0;
    /** Dedupe: skip same (target, reply) within this window (ms). */
    private static String lastProcessedMessageKey = null;
    private static long lastProcessedMessageTime = 0;
    private static final long DEDUPE_MS = 2500;
    /** Re-entry guard: sending /msg etc. from inside handler can trigger another GAME/CHAT event -> StackOverflow. */
    private static volatile boolean inMessageHandler = false;
    /** After positive reply: wait 45s, keep forwarding target messages to AI, then /leave and restart. */
    private static boolean postPositiveActive = false;
    private static int postPositiveWaitTicks = 0;
    /** After beam timeout: wait 10s then startBeamAgain. */
    private static boolean timeoutRestartPending = false;
    private static int timeoutRestartTicks = 0;

    private static final int TICKS_7_SEC = 140;
    private static final int TICKS_6_SEC = 120; // walk forward duration
    private static final int TICKS_5_SEC = 100;
    private static final int TICKS_10_SEC = 200;
    private static final int TICKS_45_SEC = 900;
    private static final int TICKS_1_SEC = 20;
    private static final int TICKS_BEAM_TIMEOUT = 1800;
    private static final int MAX_SCAN_ATTEMPTS = 4;
    private static final double MAX_SQ_DISTANCE = 2500; // 50^2
    /** First scan at 7s + 6s = 13s; then every 7s (4 scans total). */
    private static final int TICKS_BEFORE_FIRST_SCAN = TICKS_7_SEC + TICKS_6_SEC;

    @Override
    public void onInitializeClient() {
        BeamQueueLog.info("Beam Queue client init");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("beam").executes(ctx -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player == null || client.world == null) {
                        BeamQueueLog.warn("/beam ignored: not in world (player or world null)");
                        return 0;
                    }
                    if (active) {
                        BeamQueueLog.debug("/beam ignored: already active");
                        client.player.sendMessage(
                            Text.literal("Beam is already active!").formatted(Formatting.GREEN), false);
                        return 1;
                    }
                    active = true;
                    targetPlayer = null;
                    ticksSinceStart = 0;
                    scanAttempts = 0;
                    client.player.networkHandler.sendChatCommand("queue sword");
                    client.player.sendMessage(
                        Text.literal("Queued sword! Waiting 7s, then moving forward 6s, then scanning...").formatted(Formatting.GREEN), false);
                    BeamQueueLog.info("/beam started: queue sword sent, sequence started (7s wait -> 6s move -> scan x4)");
                    return 1;
                })
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active) return;

            // Queue cooldown: wait parsed duration (e.g. 6m 54s) then startBeamAgain
            if (queueCooldownActive) {
                if (client.player != null) {
                    queueCooldownTicks--;
                    if (queueCooldownTicks <= 0) {
                        queueCooldownActive = false;
                        queueCooldownTicks = 0;
                        BeamQueueLog.info("Queue cooldown finished -> startBeamAgain");
                        if (client.world != null) {
                            client.player.sendMessage(
                                Text.literal("Cooldown over. Requeuing...").formatted(Formatting.GREEN), false);
                        }
                        startBeamAgain(client);
                    }
                }
                return;
            }

            // After death message: wait 4 seconds then /leave and restart (same flow as restartAfterLeave)
            if (deathMessageSeen) {
                if (client.player != null) {
                    deathWaitTicks++;
                    BeamQueueLog.debug("Death wait countdown: {} / {} ticks", deathWaitTicks, TICKS_4_SEC);
                    if (deathWaitTicks >= TICKS_4_SEC) {
                        deathMessageSeen = false;
                        deathWaitTicks = 0;
                        setForwardPressed(client, false);
                        client.player.networkHandler.sendChatCommand("leave");
                        client.player.sendMessage(
                            Text.literal("Death detected. Leaving, then requeueing in 10s...").formatted(Formatting.GREEN), false);
                        targetPlayer = null;
                        tournamentSent = false;
                        restartAfterLeave = true;
                        leaveWaitTicks = 0;
                    }
                }
                return;
            }

            // After /leave: wait 10 seconds (in-world) then startBeamAgain – only count when we have world so it's 10s real wait
            if (restartAfterLeave) {
                if (client.player != null && client.world != null) {
                    leaveWaitTicks++;
                    BeamQueueLog.debug("Leave restart countdown: {} / {} ticks", leaveWaitTicks, TICKS_10_SEC);
                    if (leaveWaitTicks >= TICKS_10_SEC) {
                        restartAfterLeave = false;
                        leaveWaitTicks = 0;
                        BeamQueueLog.info("Restarting beam after leave (sending queue sword)");
                        startBeamAgain(client);
                    }
                }
                return;
            }

            // After positive reply: wait 45s (forward target messages to AI), then /leave and restart
            if (postPositiveActive) {
                if (client.player != null && client.world != null) {
                    postPositiveWaitTicks++;
                    if (postPositiveWaitTicks >= TICKS_45_SEC) {
                        postPositiveActive = false;
                        postPositiveWaitTicks = 0;
                        setForwardPressed(client, false);
                        client.player.networkHandler.sendChatCommand("leave");
                        client.player.sendMessage(
                            Text.literal("Leaving now, then requeueing in 10s...").formatted(Formatting.GREEN), false);
                        targetPlayer = null;
                        tournamentSent = false;
                        restartAfterLeave = true;
                        leaveWaitTicks = 0;
                    }
                }
                return;
            }

            // After beam timeout: wait 10s then startBeamAgain
            if (timeoutRestartPending) {
                if (client.player != null && client.world != null) {
                    timeoutRestartTicks++;
                    if (timeoutRestartTicks >= TICKS_10_SEC) {
                        timeoutRestartPending = false;
                        timeoutRestartTicks = 0;
                        BeamQueueLog.info("Timeout wait over -> startBeamAgain");
                        client.player.sendMessage(Text.literal("Restarting beam...").formatted(Formatting.GREEN), false);
                        startBeamAgain(client);
                    }
                }
                return;
            }

            // Pause sequence during teleport/world load (don't reset – only reset on disconnect)
            if (client.player == null || client.world == null) {
                BeamQueueLog.debug("Tick skipped: world or player null (pausing until world loads)");
                setForwardPressed(client, false);
                return;
            }

            ticksSinceStart++;

            if (ticksSinceStart >= TICKS_BEAM_TIMEOUT) {
                BeamQueueLog.info("Beam timed out at tick {} -> wait 10s then restart", ticksSinceStart);
                client.player.sendMessage(Text.literal("Beam timed out. Restarting in 10s...").formatted(Formatting.GREEN), false);
                setForwardPressed(client, false);
                targetPlayer = null;
                tournamentSent = false;
                timeoutRestartPending = true;
                timeoutRestartTicks = 0;
                return;
            }

            if (targetPlayer == null) {
                // Phase 1: wait 7s. Phase 2: move forward 6s. Phase 3: scan at 13s, 20s, 27s, 34s (4 attempts).
                if (ticksSinceStart == TICKS_7_SEC) {
                    BeamQueueLog.debug("Phase: moving forward for 6s (tick {})", ticksSinceStart);
                    client.player.sendMessage(Text.literal("Moving forward for 6s...").formatted(Formatting.GREEN), false);
                }
                if (ticksSinceStart >= TICKS_7_SEC && ticksSinceStart < TICKS_BEFORE_FIRST_SCAN) {
                    setForwardPressed(client, true);
                } else if (ticksSinceStart == TICKS_BEFORE_FIRST_SCAN) {
                    setForwardPressed(client, false);
                    BeamQueueLog.debug("Phase: move ended, first scan at next tick (tick {})", ticksSinceStart);
                }
                int nextScanTick = TICKS_BEFORE_FIRST_SCAN + TICKS_7_SEC * scanAttempts;
                if (scanAttempts < MAX_SCAN_ATTEMPTS && ticksSinceStart == nextScanTick) {
                    BeamQueueLog.debug("Scan trigger: tick {} (attempt {}), nextScanTick={}", ticksSinceStart, scanAttempts + 1, nextScanTick);
                    runScan(client);
                }
                return;
            }

            if (!tournamentSent && ticksSinceStart >= targetSetTick + TICKS_1_SEC) {
                tournamentSent = true;
                client.player.networkHandler.sendChatCommand("msg " + targetPlayer + " theres a 2v2 sword pvp tournament wanna join?");
                client.player.sendMessage(Text.literal("Sent invite to " + targetPlayer + "!").formatted(Formatting.GREEN), false);
                BeamQueueLog.info("Sent tournament invite to target={}", targetPlayer);
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (inMessageHandler) return;
            inMessageHandler = true;
            try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!active) return;
            // Detect death messages and queue cooldown
            if (client.player != null) {
                String plain = stripFormatting(message.getString());
                String ourName = client.player.getName().getString();
                if (isOurDeathMessage(plain, ourName)) {
                    BeamQueueLog.info("Death message detected in beam session: \"{}\" -> wait 4s then /leave and restart", plain);
                    deathMessageSeen = true;
                    deathWaitTicks = 0;
                    return;
                }
                int cooldownSec = parseQueueCooldownSeconds(plain);
                if (cooldownSec > 0) {
                    queueCooldownActive = true;
                    queueCooldownTicks = cooldownSec * 20;
                    targetPlayer = null;
                    tournamentSent = false;
                    setForwardPressed(client, false);
                    BeamQueueLog.info("Queue cooldown detected: {}s -> waiting then startBeamAgain", cooldownSec);
                    client.player.sendMessage(
                        Text.literal("Queue cooldown: waiting " + formatCooldown(cooldownSec) + ", then requeuing.").formatted(Formatting.GREEN), false);
                    return;
                }
            }
            if (targetPlayer == null || client.player == null) return;
            String full = normalizeIncomingMessage(message.getString());
            BeamQueueLog.debug("GAME message raw: overlay={} text={}", overlay, full);
            String fullLower = full.toLowerCase();
            // Ignore our own outgoing messages (e.g. "You -> thekidpika: ...")
            if (fullLower.startsWith("you ->")) return;
            String targetLower = targetPlayer.toLowerCase();
            // Try strict prefixes first, then lenient fallback (target name + "you:" anywhere)
            String reply = extractReplyFromGameMessage(full, fullLower, targetLower);
            if (reply == null) {
                if (fullLower.contains(targetLower)) {
                    BeamQueueLog.debug("GAME: message contained target '{}' but no reply extracted. Raw: {}", targetPlayer, full);
                }
                return;
            }
            if (reply.isEmpty()) return;
            // Dedupe: same message from same target within window (GAME can fire 2–3x; sync for thread safety)
            String key = targetPlayer + "|" + reply;
            long now = System.currentTimeMillis();
            synchronized (BeamQueueMod.class) {
                if (key.equals(lastProcessedMessageKey) && (now - lastProcessedMessageTime) < DEDUPE_MS) {
                    BeamQueueLog.debug("GAME: dedupe skip same message from {} within {}ms", targetPlayer, DEDUPE_MS);
                    return;
                }
                lastProcessedMessageKey = key;
                lastProcessedMessageTime = now;
            }
            BeamQueueLog.info("GAME message from target={} reply=\"{}\" (will respond)", targetPlayer, reply);
            // During 45s post-positive window, forward all target messages to AI (no second join link)
            if (postPositiveActive) {
                BeamQueueLog.info("Post-positive: forwarding target message to AI");
                BeamQueueAiReply.requestReply(reply, targetPlayer);
                return;
            }
            String replyLower = reply.toLowerCase();
            if (replyLower.contains("yes") || replyLower.contains("yeah") || replyLower.contains("sure")
                || replyLower.contains("ok") || replyLower.contains("join")) {
                BeamQueueLog.info("Positive reply -> sending join link, then 45s wait (forward msgs to AI), then leave");
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand(
                        "msg " + targetPlayer + " join feather-mc [dot] net, add donutskelesz on dc");
                    client.player.sendMessage(
                        Text.literal("Player interested! Link sent. Waiting 45s for more messages, then leaving...").formatted(Formatting.GREEN), false);
                }
                postPositiveActive = true;
                postPositiveWaitTicks = 0;
            } else if (isDeclineReply(replyLower)) {
                BeamQueueLog.info("Decline reply -> /leave and restart in 10s");
                client.player.networkHandler.sendChatCommand("leave");
                client.player.sendMessage(
                    Text.literal("Target declined. Leaving queue, restarting in 10s...").formatted(Formatting.GREEN), false);
                targetPlayer = null;
                tournamentSent = false;
                restartAfterLeave = true;
                leaveWaitTicks = 0;
            } else {
                // Only send to AI when reply is neither positive nor decline (saves API calls). Also used during 45s post-positive.
                BeamQueueLog.info("Other reply -> sending to AI for response");
                BeamQueueAiReply.requestReply(reply, targetPlayer);
            }
            } finally {
                inMessageHandler = false;
            }
        });

        // Also handle CHAT – whispers from target, and death messages on some servers
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (inMessageHandler) return;
            inMessageHandler = true;
            try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!active) return;
            if (client.player != null) {
                String plain = stripFormatting(message.getString());
                String ourName = client.player.getName().getString();
                if (isOurDeathMessage(plain, ourName)) {
                    BeamQueueLog.info("Death message (CHAT) detected: \"{}\" -> wait 4s then /leave and restart", plain);
                    deathMessageSeen = true;
                    deathWaitTicks = 0;
                    return;
                }
                int cooldownSec = parseQueueCooldownSeconds(plain);
                if (cooldownSec > 0) {
                    queueCooldownActive = true;
                    queueCooldownTicks = cooldownSec * 20;
                    targetPlayer = null;
                    tournamentSent = false;
                    setForwardPressed(client, false);
                    BeamQueueLog.info("Queue cooldown (CHAT) detected: {}s -> waiting then startBeamAgain", cooldownSec);
                    client.player.sendMessage(
                        Text.literal("Queue cooldown: waiting " + formatCooldown(cooldownSec) + ", then requeuing.").formatted(Formatting.GREEN), false);
                    return;
                }
            }
            if (targetPlayer == null || client.player == null) return;
            if (sender == null || sender.getName() == null) return;
            String senderName = stripFormatting(sender.getName()).trim();
            if (senderName.isEmpty()) return;
            // Exact match or target name contained in sender (e.g. rank prefix "[VIP] Kirambitt") or vice versa
            boolean fromTarget = senderName.equalsIgnoreCase(targetPlayer)
                || senderName.toLowerCase().contains(targetPlayer.toLowerCase())
                || targetPlayer.toLowerCase().contains(senderName.toLowerCase());
            if (!fromTarget) return;
            String reply = stripFormatting(message.getString()).trim();
            if (reply.isEmpty()) return;
            String key = targetPlayer + "|" + reply;
            long now = System.currentTimeMillis();
            synchronized (BeamQueueMod.class) {
                if (key.equals(lastProcessedMessageKey) && (now - lastProcessedMessageTime) < DEDUPE_MS) {
                    BeamQueueLog.debug("CHAT: dedupe skip same message from {} within {}ms", targetPlayer, DEDUPE_MS);
                    return;
                }
                lastProcessedMessageKey = key;
                lastProcessedMessageTime = now;
            }
            BeamQueueLog.info("CHAT message from sender={} reply=\"{}\"", sender.getName(), reply);
            if (postPositiveActive) {
                BeamQueueLog.info("Post-positive (CHAT): forwarding target message to AI");
                BeamQueueAiReply.requestReply(reply, targetPlayer);
                return;
            }
            String replyLower = reply.toLowerCase();
            if (replyLower.contains("yes") || replyLower.contains("yeah") || replyLower.contains("sure")
                || replyLower.contains("ok") || replyLower.contains("join")) {
                BeamQueueLog.info("CHAT positive reply -> sending join link, then 45s wait (forward msgs to AI), then leave");
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand(
                        "msg " + targetPlayer + " join feather-mc [dot] net, add donutskelesz on dc");
                    client.player.sendMessage(
                        Text.literal("Player interested! Link sent. Waiting 45s for more messages, then leaving...").formatted(Formatting.GREEN), false);
                }
                postPositiveActive = true;
                postPositiveWaitTicks = 0;
            } else if (isDeclineReply(replyLower)) {
                BeamQueueLog.info("CHAT decline reply -> /leave and restart in 10s");
                client.player.networkHandler.sendChatCommand("leave");
                client.player.sendMessage(
                    Text.literal("Target declined. Leaving queue, restarting in 10s...").formatted(Formatting.GREEN), false);
                targetPlayer = null;
                tournamentSent = false;
                restartAfterLeave = true;
                leaveWaitTicks = 0;
            } else {
                BeamQueueLog.info("CHAT other reply -> sending to AI");
                BeamQueueAiReply.requestReply(reply, targetPlayer);
            }
            } finally {
                inMessageHandler = false;
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (restartAfterLeave) {
                BeamQueueLog.info("Disconnect during leave-restart – keeping state so requeue continues after rejoin");
                return;
            }
            BeamQueueLog.info("Disconnect -> reset");
            reset();
        });
    }

    private static void runScan(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) return;
        int attempt = scanAttempts + 1;
        BeamQueueLog.info("Scan attempt {} / {} (tick={})", attempt, MAX_SCAN_ATTEMPTS, ticksSinceStart);
        self.sendMessage(Text.literal("Scanning for nearby players... (attempt " + attempt + "/" + MAX_SCAN_ATTEMPTS + ")").formatted(Formatting.GREEN), false);
        List<AbstractClientPlayerEntity> players = client.world.getPlayers();
        AbstractClientPlayerEntity nearest = null;
        double minSq = MAX_SQ_DISTANCE + 1;
        for (AbstractClientPlayerEntity p : players) {
            if (p == self) continue;
            double sq = self.squaredDistanceTo(p);
            BeamQueueLog.debug("  player {} sqDist={}", p.getName().getString(), sq);
            if (sq < minSq && sq < MAX_SQ_DISTANCE) {
                minSq = sq;
                nearest = p;
            }
        }
        if (nearest == null) {
            scanAttempts++;
            BeamQueueLog.info("Scan {}: no player in range (maxSq={})", attempt, MAX_SQ_DISTANCE);
            if (scanAttempts >= MAX_SCAN_ATTEMPTS) {
                self.sendMessage(Text.literal("No nearby player found after " + MAX_SCAN_ATTEMPTS + " scans!").formatted(Formatting.GREEN), false);
                BeamQueueLog.info("Max scans reached -> reset");
                reset();
            } else {
                self.sendMessage(Text.literal("No player in range. Retrying in 7s... (" + scanAttempts + "/" + MAX_SCAN_ATTEMPTS + ")").formatted(Formatting.GREEN), false);
            }
            return;
        }
        targetPlayer = nearest.getName().getString();
        targetSetTick = ticksSinceStart;
        tournamentSent = false;
        BeamQueueLog.info("Scan {}: found target={} (sqDist={})", attempt, targetPlayer, minSq);
        self.sendMessage(Text.literal("Found " + targetPlayer + "! Messaging...").formatted(Formatting.GREEN), false);
        self.networkHandler.sendChatCommand("msg " + targetPlayer + " hi");
        self.sendMessage(Text.literal("Messaged " + targetPlayer + ": hi").formatted(Formatting.GREEN), false);
    }

    private static void setForwardPressed(MinecraftClient client, boolean pressed) {
        try {
            KeyBinding key = client.options.forwardKey;
            InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(key);
            KeyBinding.setKeyPressed(bound, pressed);
            BeamQueueLog.debug("Forward key set pressed={}", pressed);
        } catch (Exception e) {
            BeamQueueLog.warn("setForwardPressed failed: {}", e.getMessage());
        }
    }

    /** Strip Minecraft § formatting codes so prefix matching works. */
    private static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-or]", "");
    }

    /**
     * Normalize incoming message: strip § codes and unify arrow variants (→ U+2192, » U+00BB) to ASCII "->"
     * so "thekidpika → You: when" matches our prefixes.
     */
    private static String normalizeIncomingMessage(String s) {
        if (s == null) return "";
        s = stripFormatting(s);
        s = s.replace("\u2192", "->");  // Unicode RIGHTWARDS ARROW (→)
        s = s.replace("\u00BB", "->");  // Unicode RIGHT-POINTING DOUBLE ANGLE (»)
        return s;
    }

    /**
     * Extract reply text from a GAME message from the target. Tries strict prefixes first,
     * then a lenient fallback: message contains target name and "you:" and takes text after "you:".
     */
    private static String extractReplyFromGameMessage(String full, String fullLower, String targetLower) {
        // Strict prefixes (order matters). Arrow already normalized to "->" by normalizeIncomingMessage.
        String[] prefixes = {
            targetLower + " whispers to you: ",
            "[" + targetLower + "] whispers to you: ",
            targetLower + " -> you: ",
            targetLower + " -> you:",
            targetLower + " » you: ",
            targetLower + " » you:",
            targetLower + " -> ",   // e.g. "ItzVelzz » yes" (» normalized to ->)
            targetLower + " » "
        };
        for (String prefix : prefixes) {
            if (fullLower.contains(prefix)) {
                int idx = fullLower.indexOf(prefix);
                return full.substring(idx + prefix.length()).trim();
            }
        }
        // Lenient: target name appears and "you:" appears – take everything after "you:"
        if (!fullLower.contains(targetLower) || !fullLower.contains("you:")) return null;
        int youColon = fullLower.indexOf("you:");
        if (youColon == -1) return null;
        int targetPos = fullLower.indexOf(targetLower);
        if (targetPos > youColon) return null;  // target must come before "you:"
        String after = full.substring(youColon + "you:".length()).trim();
        if (after.isEmpty()) return null;
        BeamQueueLog.debug("GAME: lenient extract after 'you:' -> \"{}\"", after);
        return after;
    }

    /** Negative/decline replies: we handle with /leave, never send to AI. */
    private static boolean isDeclineReply(String replyLower) {
        String t = replyLower.trim();
        if (t.equals("no") || t.equals("nope") || t.equals("nah") || t.equals("na")) return true;
        return replyLower.contains("scam") || replyLower.contains("no thanks") || replyLower.contains("no thank u")
            || replyLower.contains("no thank you") || replyLower.contains("nope") || replyLower.contains("nah")
            || replyLower.contains("later") || replyLower.contains("im good")
            || replyLower.contains("im bad") || replyLower.contains("very bad") || replyLower.contains("i'm bad")
            || replyLower.startsWith("na ") || replyLower.endsWith(" na")
            || replyLower.contains("no way") || replyLower.contains("not interested") || replyLower.contains("pass");
    }

    private static void startBeamAgain(MinecraftClient client) {
        if (client.player == null) return;
        targetPlayer = null;
        ticksSinceStart = 0;
        scanAttempts = 0;
        tournamentSent = false;
        postPositiveActive = false;
        postPositiveWaitTicks = 0;
        timeoutRestartPending = false;
        timeoutRestartTicks = 0;
        client.player.networkHandler.sendChatCommand("queue sword");
        client.player.sendMessage(
            Text.literal("Queued sword! Waiting 7s, then moving forward 6s, then scanning...").formatted(Formatting.GREEN), false);
        BeamQueueLog.debug("startBeamAgain: state reset, queue sword sent");
    }

    private static void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) setForwardPressed(client, false);
        BeamQueueLog.debug("reset: active=false, target=null, ticks=0");
        active = false;
        targetPlayer = null;
        ticksSinceStart = 0;
        scanAttempts = 0;
        tournamentSent = false;
        restartAfterLeave = false;
        leaveWaitTicks = 0;
        deathMessageSeen = false;
        deathWaitTicks = 0;
        queueCooldownActive = false;
        queueCooldownTicks = 0;
        postPositiveActive = false;
        postPositiveWaitTicks = 0;
        timeoutRestartPending = false;
        timeoutRestartTicks = 0;
        lastProcessedMessageKey = null;
    }

    /** Parse queue cooldown message (e.g. "You are on queue cooldown for 6m, 54s due to..."). Returns total seconds or 0. */
    private static int parseQueueCooldownSeconds(String message) {
        if (message == null) return 0;
        String lower = message.toLowerCase();
        if (!lower.contains("queue cooldown") && !lower.contains("cooldown for")) return 0;
        int minutes = 0;
        int seconds = 0;
        Matcher mMin = Pattern.compile("(\\d+)\\s*m").matcher(lower);
        if (mMin.find()) minutes = Integer.parseInt(mMin.group(1));
        Matcher mSec = Pattern.compile("(\\d+)\\s*s").matcher(lower);
        if (mSec.find()) seconds = Integer.parseInt(mSec.group(1));
        int total = minutes * 60 + seconds;
        return total > 0 ? total : 0;
    }

    private static String formatCooldown(int totalSeconds) {
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        if (m > 0 && s > 0) return m + "m " + s + "s";
        if (m > 0) return m + "m";
        return s + "s";
    }

    /** True if message is a death message for the given player name (e.g. "X was slain by Y"). */
    private static boolean isOurDeathMessage(String message, String ourName) {
        if (message == null || ourName == null || ourName.isEmpty()) return false;
        String lower = message.toLowerCase();
        String nameLower = ourName.toLowerCase();
        if (!lower.contains(nameLower)) return false;
        return lower.contains(" was slain by") || lower.contains(" was killed by") || lower.contains(" was burnt")
            || lower.contains(" fell from") || lower.contains(" drowned") || lower.contains(" went up in flames")
            || lower.contains(" was blown up") || lower.contains(" hit the ground") || lower.contains(" was shot")
            || lower.contains(" was fireballed") || lower.contains(" was pricked") || lower.contains(" was squashed")
            || lower.contains(" was struck by lightning") || lower.contains(" was slain");
    }
}
