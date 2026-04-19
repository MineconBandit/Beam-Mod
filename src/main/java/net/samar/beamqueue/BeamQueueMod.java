package net.samar.beamqueue;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BeamQueueMod implements ClientModInitializer {

    public static boolean active = false;
    public static String targetPlayer = null;
    public static int ticksSinceStart = 0;
    public static int scanAttempts = 0;
    public static int targetSetTick = 0;
    public static boolean tournamentSent = false;
    public static boolean restartAfterLeave = false;
    public static int leaveWaitTicks = 0;

    private static boolean deathMessageSeen = false;
    private static int deathWaitTicks = 0;
    private static final int TICKS_4_SEC = 80;

    private static boolean queueCooldownActive = false;
    private static int queueCooldownTicks = 0;

    private static String lastProcessedMessageKey = null;
    private static long lastProcessedMessageTime = 0;
    private static final long DEDUPE_MS = 2500;

    private static volatile boolean inMessageHandler = false;

    private static boolean postPositiveActive = false;
    private static int postPositiveWaitTicks = 0;

    private static boolean timeoutRestartPending = false;
    private static int timeoutRestartTicks = 0;
    private static boolean introReplyWaitActive = false;
    private static int introReplyWaitTicks = 0;

    private static String queueMode = "sword";
    private static String beamServer = "mcpvp";
    private static String shareMode = "ip";
    private static String serverIpPlain = "mc-feather.com";
    private static String discordInvitePlain = "discord.gg/fnw";
    private static String introMsg1 = "hi";
    private static String introMsg2 = "theres a 2v2 sword pvp tournament wanna join?";
    private static boolean autoReconnectEnabled = true;
    private static boolean pendingAutoReconnect = false;
    private static int autoReconnectWaitTicks = 0;
    private static final int TICKS_AUTO_RECONNECT_DELAY = 200; // 10s
    private static String lastServerAddress = null;
    private static String lastServerName = "Server";

    private static long lastPrivateMsgAt = 0L;
    private static final long PRIVATE_MSG_GAP_MS = 3500L;
    private static final String FLOWPVP_MATCH_TEXT = "you were matched for";
    private static final String CATPVP_MATCH_TEXT = "found opponent!";
    private static final int FLOWPVP_CONTAINER_SLOT_INDEX = 10; // 0-based index as requested
    private static final int CATPVP_STAGE1_CONTAINER_SLOT_INDEX = 10; // 0-based
    private static final int CATPVP_STAGE2_CONTAINER_SLOT_INDEX = 11; // 0-based
    private static final int CONTAINER_QUEUE_CLICK_RETRIES = 24;
    private static final long FLOWPVP_QUEUE_INITIAL_DELAY_MS = 3000L;
    private static final long CATPVP_QUEUE_INITIAL_DELAY_MS = 2000L;
    private static final long CATPVP_STAGE2_DELAY_MS = 400L;
    private static final long FLOWPVP_QUEUE_CLICK_RETRY_MS = 250L;
    private static final long FLOWPVP_LEAVE_SECOND_DELAY_MS = 2000L;
    private static final ScheduledExecutorService MSG_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "beamqueue-msg-scheduler");
        t.setDaemon(true);
        return t;
    });

    private static final int TICKS_7_SEC = 140;
    private static final int TICKS_3_SEC = 60;
    private static final int TICKS_6_SEC = 120;
    private static final int TICKS_10_SEC = 200;
    private static final int TICKS_30_SEC = 600;
    private static final int TICKS_45_SEC = 900;
    private static final int TICKS_1_SEC = 20;
    private static final int TICKS_TARGET_FOLLOWUP_DELAY = 70; // 3.5s between "hi" and tournament invite
    private static final int TICKS_BEAM_TIMEOUT = 1800;
    private static final int MAX_SCAN_ATTEMPTS = 4;
    private static final double MAX_SQ_DISTANCE = 2500;
    private static final Pattern MINECRAFT_USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static boolean flowPvpAwaitingMatch = false;
    private static int flowPvpMatchTick = -1;
    private static boolean catPvpAwaitingMatch = false;
    private static int catPvpMatchTick = -1;

    @Override
    public void onInitializeClient() {
        BeamQueueLog.info("Beam Queue client init");
        BeamQueueAiReply.startHealthChecks();

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
                        client.player.sendMessage(Text.literal("Beam is already active!").formatted(Formatting.GREEN), false);
                        return 1;
                    }
                    active = true;
                    targetPlayer = null;
                    ticksSinceStart = 0;
                    scanAttempts = 0;
                    startBeamEntryAction(client);
                    return 1;
                })
            );
            dispatcher.register(
                ClientCommandManager.literal("beamstop").executes(ctx -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (!active) {
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("Beam is not active.").formatted(Formatting.GREEN), false);
                        }
                        return 0;
                    }
                    reset();
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("Beam stopped.").formatted(Formatting.GREEN), false);
                    }
                    BeamQueueLog.info("/beamstop: beam session stopped");
                    return 1;
                })
            );

            dispatcher.register(
                ClientCommandManager.literal("changequeue")
                    .then(ClientCommandManager.argument("mode", StringArgumentType.greedyString()).executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        String mode = sanitizeCommandValue(StringArgumentType.getString(ctx, "mode"));
                        if (mode.isBlank()) {
                            client.player.sendMessage(Text.literal("Usage: /changequeue <mode>").formatted(Formatting.GREEN), false);
                            return 0;
                        }
                        queueMode = mode;
                        client.player.sendMessage(Text.literal("Queue mode set to: " + queueMode).formatted(Formatting.GREEN), false);
                        BeamQueueLog.info("Queue mode changed to {}", queueMode);
                        return 1;
                    }))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        client.player.sendMessage(Text.literal("Current queue mode: " + queueMode).formatted(Formatting.GREEN), false);
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("beamserver")
                    .then(ClientCommandManager.literal("mcpvp").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        beamServer = "mcpvp";
                        client.player.sendMessage(Text.literal("Beam server set to MCPvP").formatted(Formatting.GREEN), false);
                        BeamQueueLog.info("Beam server changed to mcpvp");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("minemen").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        beamServer = "minemen";
                        client.player.sendMessage(Text.literal("Beam server set to Minemen").formatted(Formatting.GREEN), false);
                        BeamQueueLog.info("Beam server changed to minemen");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("flowpvp").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        beamServer = "flowpvp";
                        client.player.sendMessage(Text.literal("Beam server set to FlowPvP").formatted(Formatting.GREEN), false);
                        BeamQueueLog.info("Beam server changed to flowpvp");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("catpvp").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        beamServer = "catpvp";
                        client.player.sendMessage(Text.literal("Beam server set to CatPvP").formatted(Formatting.GREEN), false);
                        BeamQueueLog.info("Beam server changed to catpvp");
                        return 1;
                    }))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        client.player.sendMessage(
                            Text.literal("Current beam server: " + beamServer + ". Use /beamserver mcpvp, /beamserver minemen, /beamserver flowpvp, or /beamserver catpvp.")
                                .formatted(Formatting.GREEN),
                            false);
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("autoreconnect")
                    .then(ClientCommandManager.literal("on").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        autoReconnectEnabled = true;
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("Auto reconnect: ON").formatted(Formatting.GREEN), false);
                        }
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        autoReconnectEnabled = false;
                        pendingAutoReconnect = false;
                        autoReconnectWaitTicks = 0;
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("Auto reconnect: OFF").formatted(Formatting.GREEN), false);
                        }
                        return 1;
                    }))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            client.player.sendMessage(
                                Text.literal("Auto reconnect is " + (autoReconnectEnabled ? "ON" : "OFF"))
                                    .formatted(Formatting.GREEN),
                                false);
                        }
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("changeip")
                    .then(ClientCommandManager.argument("ip", StringArgumentType.greedyString()).executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        String ip = sanitizeCommandValue(StringArgumentType.getString(ctx, "ip"));
                        if (ip.isBlank()) {
                            client.player.sendMessage(Text.literal("Usage: /changeip <server ip>").formatted(Formatting.GREEN), false);
                            return 0;
                        }
                        serverIpPlain = ip;
                        client.player.sendMessage(
                            Text.literal("Server IP set to: " + getServerIpMasked() + " (mode stays " + shareMode + ")")
                                .formatted(Formatting.GREEN),
                            false);
                        BeamQueueLog.info("Server IP changed to {}", serverIpPlain);
                        return 1;
                    }))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        client.player.sendMessage(Text.literal("Current server IP: " + getServerIpMasked()).formatted(Formatting.GREEN), false);
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("intromsg")
                    .then(ClientCommandManager.argument("msg1", StringArgumentType.string())
                        .then(ClientCommandManager.argument("msg2", StringArgumentType.string()).executes(ctx -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            if (client.player == null) return 0;
                            String msg1 = sanitizeCommandValue(StringArgumentType.getString(ctx, "msg1"));
                            String msg2 = sanitizeCommandValue(StringArgumentType.getString(ctx, "msg2"));
                            if (msg1.isBlank() || msg2.isBlank()) {
                                client.player.sendMessage(Text.literal("Usage: /intromsg \"<msg 1>\" \"<msg 2>\"").formatted(Formatting.GREEN), false);
                                return 0;
                            }
                            introMsg1 = msg1;
                            introMsg2 = msg2;
                            client.player.sendMessage(
                                Text.literal("Intro messages updated. msg1=\"" + introMsg1 + "\" msg2=\"" + introMsg2 + "\"")
                                    .formatted(Formatting.GREEN),
                                false);
                            BeamQueueLog.info("Intro messages updated via /intromsg: msg1=\"{}\" msg2=\"{}\"", introMsg1, introMsg2);
                            return 1;
                        })))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        client.player.sendMessage(
                            Text.literal("Current intro messages: msg1=\"" + introMsg1 + "\" msg2=\"" + introMsg2 + "\". Usage: /intromsg \"<msg 1>\" \"<msg 2>\"")
                                .formatted(Formatting.GREEN),
                            false);
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("changemode")
                    .then(ClientCommandManager.literal("ip")
                        .then(ClientCommandManager.argument("value", StringArgumentType.greedyString()).executes(ctx -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            if (client.player == null) return 0;
                            String value = sanitizeCommandValue(StringArgumentType.getString(ctx, "value"));
                            if (value.isBlank()) {
                                client.player.sendMessage(Text.literal("Usage: /changemode ip <server ip>").formatted(Formatting.GREEN), false);
                                return 0;
                            }
                            serverIpPlain = value;
                            shareMode = "ip";
                            client.player.sendMessage(Text.literal("Share mode: IP (" + getShareTargetMasked() + ")").formatted(Formatting.GREEN), false);
                            BeamQueueLog.info("Share mode changed to ip with value {}", serverIpPlain);
                            return 1;
                        }))
                        .executes(ctx -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            if (client.player == null) return 0;
                            shareMode = "ip";
                            client.player.sendMessage(Text.literal("Share mode set to IP (" + getShareTargetMasked() + ")").formatted(Formatting.GREEN), false);
                            BeamQueueLog.info("Share mode changed to ip");
                            return 1;
                        }))
                    .then(ClientCommandManager.literal("discord")
                        .then(ClientCommandManager.argument("value", StringArgumentType.greedyString()).executes(ctx -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            if (client.player == null) return 0;
                            String value = sanitizeCommandValue(StringArgumentType.getString(ctx, "value"));
                            if (value.isBlank()) {
                                client.player.sendMessage(Text.literal("Usage: /changemode discord <invite>").formatted(Formatting.GREEN), false);
                                return 0;
                            }
                            discordInvitePlain = value;
                            shareMode = "discord";
                            client.player.sendMessage(Text.literal("Share mode: Discord (" + getShareTargetMasked() + ")").formatted(Formatting.GREEN), false);
                            BeamQueueLog.info("Share mode changed to discord with value {}", discordInvitePlain);
                            return 1;
                        }))
                        .executes(ctx -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            if (client.player == null) return 0;
                            shareMode = "discord";
                            client.player.sendMessage(Text.literal("Share mode set to Discord (" + getShareTargetMasked() + ")").formatted(Formatting.GREEN), false);
                            BeamQueueLog.info("Share mode changed to discord");
                            return 1;
                        }))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        client.player.sendMessage(
                            Text.literal("Usage: /changemode ip <server ip> OR /changemode discord <invite>. Current: " + shareMode + " -> " + getShareTargetMasked())
                                .formatted(Formatting.GREEN),
                            false);
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("apikey")
                    .then(ClientCommandManager.argument("key", StringArgumentType.greedyString()).executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        String key = sanitizeCommandValue(StringArgumentType.getString(ctx, "key"));
                        if (key.isBlank()) {
                            client.player.sendMessage(Text.literal("Usage: /apikey <g4f-api-key>").formatted(Formatting.GREEN), false);
                            return 0;
                        }
                        boolean saved = BeamQueueConfig.setApiKey(key);
                        BeamQueueAiReply.triggerHealthCheckNow();
                        if (saved) {
                            client.player.sendMessage(Text.literal("API key set and saved.").formatted(Formatting.GREEN), false);
                        } else {
                            client.player.sendMessage(Text.literal("API key set for now, but failed to save config file.").formatted(Formatting.GREEN), false);
                        }
                        BeamQueueLog.info("API key updated via /apikey command");
                        return 1;
                    }))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        client.player.sendMessage(Text.literal("Usage: /apikey <g4f-api-key>").formatted(Formatting.GREEN), false);
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("model")
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        String modelName = sanitizeCommandValue(StringArgumentType.getString(ctx, "name"));
                        if (modelName.isBlank()) {
                            client.player.sendMessage(Text.literal("Usage: /model <model-name>").formatted(Formatting.GREEN), false);
                            return 0;
                        }
                        boolean saved = BeamQueueConfig.setModel(modelName);
                        BeamQueueAiReply.triggerHealthCheckNow();
                        if (saved) {
                            client.player.sendMessage(Text.literal("Model set to: " + BeamQueueConfig.getModel()).formatted(Formatting.GREEN), false);
                        } else {
                            client.player.sendMessage(Text.literal("Model set for now, but failed to save config file.").formatted(Formatting.GREEN), false);
                        }
                        BeamQueueLog.info("AI model updated via /model command: {}", modelName);
                        return 1;
                    }))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        client.player.sendMessage(Text.literal("Current model: " + BeamQueueConfig.getModel()).formatted(Formatting.GREEN), false);
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("apiurl")
                    .then(ClientCommandManager.argument("url", StringArgumentType.greedyString()).executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        String url = sanitizeCommandValue(StringArgumentType.getString(ctx, "url"));
                        if (url.isBlank()) {
                            client.player.sendMessage(Text.literal("Usage: /apiurl <base-url>").formatted(Formatting.GREEN), false);
                            return 0;
                        }
                        boolean saved = BeamQueueConfig.setApiUrl(url);
                        BeamQueueAiReply.triggerHealthCheckNow();
                        if (saved) {
                            client.player.sendMessage(Text.literal("API base URL set to: " + BeamQueueConfig.getApiUrl()).formatted(Formatting.GREEN), false);
                        } else {
                            client.player.sendMessage(Text.literal("API base URL set for now, but failed to save config file.").formatted(Formatting.GREEN), false);
                        }
                        BeamQueueLog.info("AI API URL updated via /apiurl command: {}", url);
                        return 1;
                    }))
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        client.player.sendMessage(Text.literal("Current API base URL: " + BeamQueueConfig.getApiUrl()).formatted(Formatting.GREEN), false);
                        return 1;
                    })
            );

            dispatcher.register(
                ClientCommandManager.literal("checkai")
                    .executes(ctx -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        BeamQueueAiReply.triggerHealthCheckNow();
                        client.player.sendMessage(
                            Text.literal("AI check triggered. Current status: " + BeamQueueAiReply.getHealthLabel())
                                .formatted(Formatting.GREEN),
                            false);
                        return 1;
                    })
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            captureCurrentServer(client);
            tickAutoReconnect(client);
            if (!active) return;

            if (queueCooldownActive) {
                if (client.player != null) {
                    queueCooldownTicks--;
                    if (queueCooldownTicks <= 0) {
                        queueCooldownActive = false;
                        queueCooldownTicks = 0;
                        BeamQueueLog.info("Queue cooldown finished -> startBeamAgain");
                        if (client.world != null) {
                            client.player.sendMessage(Text.literal("Cooldown over. Requeuing...").formatted(Formatting.GREEN), false);
                        }
                        startBeamAgain(client);
                    }
                }
                return;
            }

            if (deathMessageSeen) {
                if (client.player != null) {
                    deathWaitTicks++;
                    if (deathWaitTicks >= TICKS_4_SEC) {
                        deathMessageSeen = false;
                        deathWaitTicks = 0;
                        setForwardPressed(client, false);
                        sendLeaveForCurrentServer(client);
                        client.player.sendMessage(Text.literal("Death detected. Leaving, then requeueing in 10s...").formatted(Formatting.GREEN), false);
                        targetPlayer = null;
                        tournamentSent = false;
                        restartAfterLeave = true;
                        leaveWaitTicks = 0;
                    }
                }
                return;
            }

            if (restartAfterLeave) {
                if (client.player != null && client.world != null) {
                    leaveWaitTicks++;
                    if (leaveWaitTicks >= TICKS_10_SEC) {
                        restartAfterLeave = false;
                        leaveWaitTicks = 0;
                        BeamQueueLog.info("Restarting beam after leave (server mode: {})", beamServer);
                        startBeamAgain(client);
                    }
                }
                return;
            }

            if (postPositiveActive) {
                if (client.player != null && client.world != null) {
                    postPositiveWaitTicks++;
                    if (postPositiveWaitTicks >= TICKS_45_SEC) {
                        postPositiveActive = false;
                        postPositiveWaitTicks = 0;
                        setForwardPressed(client, false);
                        sendLeaveForCurrentServer(client);
                        client.player.sendMessage(Text.literal("Leaving now, then requeueing in 10s...").formatted(Formatting.GREEN), false);
                        targetPlayer = null;
                        tournamentSent = false;
                        restartAfterLeave = true;
                        leaveWaitTicks = 0;
                    }
                }
                return;
            }

            if (introReplyWaitActive) {
                if (client.player != null && client.world != null) {
                    introReplyWaitTicks++;
                    if (introReplyWaitTicks >= TICKS_30_SEC) {
                        introReplyWaitActive = false;
                        introReplyWaitTicks = 0;
                        setForwardPressed(client, false);
                        sendLeaveForCurrentServer(client);
                        client.player.sendMessage(Text.literal("No reply in 30s. Leaving, then requeueing in 10s...").formatted(Formatting.GREEN), false);
                        targetPlayer = null;
                        tournamentSent = false;
                        restartAfterLeave = true;
                        leaveWaitTicks = 0;
                    }
                }
                return;
            }

            if (timeoutRestartPending) {
                if (client.player != null && client.world != null) {
                    timeoutRestartTicks++;
                    if (timeoutRestartTicks >= TICKS_10_SEC) {
                        timeoutRestartPending = false;
                        timeoutRestartTicks = 0;
                        client.player.sendMessage(Text.literal("Restarting beam...").formatted(Formatting.GREEN), false);
                        startBeamAgain(client);
                    }
                }
                return;
            }

            if (client.player == null || client.world == null) {
                setForwardPressed(client, false);
                return;
            }

            ticksSinceStart++;

            if (ticksSinceStart >= TICKS_BEAM_TIMEOUT) {
                client.player.sendMessage(Text.literal("Beam timed out. Restarting in 10s...").formatted(Formatting.GREEN), false);
                setForwardPressed(client, false);
                sendLeaveForCurrentServer(client);
                targetPlayer = null;
                tournamentSent = false;
                timeoutRestartPending = true;
                timeoutRestartTicks = 0;
                return;
            }

            if (targetPlayer == null) {
                int moveTicks = getMoveTicksForServer();
                int moveStartTick = TICKS_7_SEC;
                if ("flowpvp".equalsIgnoreCase(beamServer) || "catpvp".equalsIgnoreCase(beamServer)) {
                    int matchedTick = "catpvp".equalsIgnoreCase(beamServer) ? catPvpMatchTick : flowPvpMatchTick;
                    if (matchedTick < 0) {
                        setForwardPressed(client, false);
                        return;
                    }
                    moveStartTick = matchedTick;
                }
                int ticksBeforeFirstScan = moveStartTick + moveTicks;
                int moveSeconds = moveTicks / 20;
                if (ticksSinceStart == moveStartTick) {
                    client.player.sendMessage(Text.literal("Moving forward for " + moveSeconds + "s...").formatted(Formatting.GREEN), false);
                }
                if (ticksSinceStart >= moveStartTick && ticksSinceStart < ticksBeforeFirstScan) {
                    setForwardPressed(client, true);
                } else if (ticksSinceStart == ticksBeforeFirstScan) {
                    setForwardPressed(client, false);
                }
                int nextScanTick = ticksBeforeFirstScan + TICKS_7_SEC * scanAttempts;
                if (scanAttempts < MAX_SCAN_ATTEMPTS && ticksSinceStart == nextScanTick) {
                    runScan(client);
                }
                return;
            }

            if (!isValidTargetUsername(targetPlayer)) {
                BeamQueueLog.warn("Dropping invalid target username: {}", targetPlayer);
                targetPlayer = null;
                tournamentSent = false;
                introReplyWaitActive = false;
                introReplyWaitTicks = 0;
                return;
            }

            if (!tournamentSent && ticksSinceStart >= targetSetTick + TICKS_TARGET_FOLLOWUP_DELAY) {
                tournamentSent = true;
                client.player.networkHandler.sendChatCommand("msg " + targetPlayer + " " + introMsg2);
                client.player.sendMessage(Text.literal("Sent invite to " + targetPlayer + "!").formatted(Formatting.GREEN), false);
                BeamQueueLog.info("Sent tournament invite to target={}", targetPlayer);
                introReplyWaitActive = true;
                introReplyWaitTicks = 0;
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (inMessageHandler) return;
            inMessageHandler = true;
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (!active) return;

                if (client.player != null) {
                    String plain = stripFormatting(message.getString());
                    maybeHandleFlowPvpMatchDetected(client, plain);
                    String ourName = client.player.getName().getString();
                    if (isOurDeathMessage(plain, ourName)) {
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
                        client.player.sendMessage(
                            Text.literal("Queue cooldown: waiting " + formatCooldown(cooldownSec) + ", then requeuing.").formatted(Formatting.GREEN),
                            false);
                        return;
                    }
                }

                if (targetPlayer == null || client.player == null) return;
                String full = normalizeIncomingMessage(message.getString());
                String fullLower = full.toLowerCase();
                if (fullLower.startsWith("you ->") || fullLower.startsWith("(to ") || fullLower.startsWith("to ")) return;

                String targetLower = targetPlayer.toLowerCase();
                String reply = extractReplyFromGameMessage(full, fullLower, targetLower);
                if (reply == null || reply.isEmpty()) return;
                markTargetReplied();

                String key = targetPlayer + "|" + reply;
                long now = System.currentTimeMillis();
                synchronized (BeamQueueMod.class) {
                    if (key.equals(lastProcessedMessageKey) && (now - lastProcessedMessageTime) < DEDUPE_MS) return;
                    lastProcessedMessageKey = key;
                    lastProcessedMessageTime = now;
                }

                if (postPositiveActive) {
                    BeamQueueAiReply.requestReply(reply, targetPlayer);
                    return;
                }

                String replyLower = reply.toLowerCase();
                if (replyLower.contains("yes") || replyLower.contains("yeah") || replyLower.contains("sure")
                    || replyLower.contains("ok") || replyLower.contains("join")) {
                    handlePositiveInterestReply(client);
                } else if (isDeclineReply(replyLower)) {
                    sendLeaveForCurrentServer(client);
                    client.player.sendMessage(Text.literal("Target declined. Leaving queue, restarting in 10s...").formatted(Formatting.GREEN), false);
                    targetPlayer = null;
                    tournamentSent = false;
                    restartAfterLeave = true;
                    leaveWaitTicks = 0;
                } else {
                    if (!shouldForwardToAi(replyLower)) return;
                    BeamQueueAiReply.requestReply(reply, targetPlayer);
                }
            } finally {
                inMessageHandler = false;
            }
        });

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (inMessageHandler) return;
            inMessageHandler = true;
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (!active) return;

                if (client.player != null) {
                    String plain = stripFormatting(message.getString());
                    maybeHandleFlowPvpMatchDetected(client, plain);
                    String ourName = client.player.getName().getString();
                    if (isOurDeathMessage(plain, ourName)) {
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
                        client.player.sendMessage(
                            Text.literal("Queue cooldown: waiting " + formatCooldown(cooldownSec) + ", then requeuing.").formatted(Formatting.GREEN),
                            false);
                        return;
                    }
                }

                if (targetPlayer == null || client.player == null) return;
                if (sender == null || sender.getName() == null) return;
                String full = normalizeIncomingMessage(message.getString());
                String fullLower = full.toLowerCase();
                if (fullLower.startsWith("you ->") || fullLower.startsWith("(to ") || fullLower.startsWith("to ")) return;

                String senderName = stripFormatting(sender.getName()).trim();
                if (senderName.isEmpty()) return;

                boolean fromTarget = senderName.equalsIgnoreCase(targetPlayer)
                    || senderName.toLowerCase().contains(targetPlayer.toLowerCase())
                    || targetPlayer.toLowerCase().contains(senderName.toLowerCase());
                if (!fromTarget) return;

                String reply = stripFormatting(message.getString()).trim();
                if (reply.isEmpty()) return;
                markTargetReplied();

                String key = targetPlayer + "|" + reply;
                long now = System.currentTimeMillis();
                synchronized (BeamQueueMod.class) {
                    if (key.equals(lastProcessedMessageKey) && (now - lastProcessedMessageTime) < DEDUPE_MS) return;
                    lastProcessedMessageKey = key;
                    lastProcessedMessageTime = now;
                }

                if (postPositiveActive) {
                    BeamQueueAiReply.requestReply(reply, targetPlayer);
                    return;
                }

                String replyLower = reply.toLowerCase();
                if (replyLower.contains("yes") || replyLower.contains("yeah") || replyLower.contains("sure")
                    || replyLower.contains("ok") || replyLower.contains("join")) {
                    handlePositiveInterestReply(client);
                } else if (isDeclineReply(replyLower)) {
                    sendLeaveForCurrentServer(client);
                    client.player.sendMessage(Text.literal("Target declined. Leaving queue, restarting in 10s...").formatted(Formatting.GREEN), false);
                    targetPlayer = null;
                    tournamentSent = false;
                    restartAfterLeave = true;
                    leaveWaitTicks = 0;
                } else {
                    if (!shouldForwardToAi(replyLower)) return;
                    BeamQueueAiReply.requestReply(reply, targetPlayer);
                }
            } finally {
                inMessageHandler = false;
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (restartAfterLeave) return;
            if (autoReconnectEnabled) {
                pendingAutoReconnect = true;
                autoReconnectWaitTicks = 0;
                captureServerFromHandler(handler);
                captureCurrentServer(client);
                BeamQueueLog.info("Disconnected -> scheduling auto reconnect in {}s", (TICKS_AUTO_RECONNECT_DELAY / 20));
                return;
            }
            reset();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            captureServerFromHandler(handler);
            captureCurrentServer(client);
            pendingAutoReconnect = false;
            autoReconnectWaitTicks = 0;
            if (lastServerAddress != null && !lastServerAddress.isBlank()) {
                BeamQueueLog.info("Connected -> remembered server {}", lastServerAddress);
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.textRenderer == null) return;
            String status = "AI: " + BeamQueueAiReply.getHealthLabel();
            int color = BeamQueueAiReply.getHealthColor();
            drawContext.drawText(client.textRenderer, status, 6, 6, color, true);
        });
    }

    private static void runScan(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) return;

        int attempt = scanAttempts + 1;
        self.sendMessage(Text.literal("Scanning for nearby players... (attempt " + attempt + "/" + MAX_SCAN_ATTEMPTS + ")").formatted(Formatting.GREEN), false);

        List<AbstractClientPlayerEntity> players = client.world.getPlayers();
        AbstractClientPlayerEntity nearest = null;
        String nearestName = null;
        double minSq = MAX_SQ_DISTANCE + 1;

        for (AbstractClientPlayerEntity p : players) {
            if (p == self) continue;
            String candidateName = stripFormatting(p.getName().getString()).trim();
            if (!isValidTargetUsername(candidateName)) continue;
            double sq = self.squaredDistanceTo(p);
            if (sq < minSq && sq < MAX_SQ_DISTANCE) {
                minSq = sq;
                nearest = p;
                nearestName = candidateName;
            }
        }

        if (nearest == null) {
            scanAttempts++;
            if (scanAttempts >= MAX_SCAN_ATTEMPTS) {
                self.sendMessage(Text.literal("No nearby player found after " + MAX_SCAN_ATTEMPTS + " scans!").formatted(Formatting.GREEN), false);
                BeamQueueLog.info("No player detected after max scans -> /leave then restart in 10s");
                setForwardPressed(client, false);
                sendLeaveForCurrentServer(client);
                self.sendMessage(Text.literal("No player detected. Leaving and restarting in 10s...").formatted(Formatting.GREEN), false);
                targetPlayer = null;
                tournamentSent = false;
                restartAfterLeave = true;
                leaveWaitTicks = 0;
            } else {
                self.sendMessage(Text.literal("No player in range. Retrying in 7s... (" + scanAttempts + "/" + MAX_SCAN_ATTEMPTS + ")").formatted(Formatting.GREEN), false);
            }
            return;
        }

        targetPlayer = nearestName;
        BeamQueueMessagedUsers.recordUsername(targetPlayer);
        targetSetTick = ticksSinceStart;
        tournamentSent = false;
        introReplyWaitActive = false;
        introReplyWaitTicks = 0;

        self.sendMessage(Text.literal("Found " + targetPlayer + "! Messaging...").formatted(Formatting.GREEN), false);
        self.networkHandler.sendChatCommand("msg " + targetPlayer + " " + introMsg1);
        self.sendMessage(Text.literal("Messaged " + targetPlayer + ": " + introMsg1).formatted(Formatting.GREEN), false);
    }

    private static void setForwardPressed(MinecraftClient client, boolean pressed) {
        try {
            KeyBinding key = client.options.forwardKey;
            InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(key);
            KeyBinding.setKeyPressed(bound, pressed);
        } catch (Exception e) {
            BeamQueueLog.warn("setForwardPressed failed: {}", e.getMessage());
        }
    }

    private static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-or]", "");
    }

    private static String normalizeIncomingMessage(String s) {
        if (s == null) return "";
        s = stripFormatting(s);
        s = s.replace("\u2192", "->");
        s = s.replace("\u00BB", "->");
        return s;
    }

    private static String extractReplyFromGameMessage(String full, String fullLower, String targetLower) {
        String[] prefixes = {
            targetLower + " whispers to you: ",
            "[" + targetLower + "] whispers to you: ",
            targetLower + " -> you: ",
            targetLower + " -> you:",
            "(from " + targetLower + ") ",
            targetLower + " -> ",
            targetLower + ":",
            targetLower + ": "
        };

        for (String prefix : prefixes) {
            if (fullLower.contains(prefix)) {
                int idx = fullLower.indexOf(prefix);
                return full.substring(idx + prefix.length()).trim();
            }
        }

        if (!fullLower.contains(targetLower) || !fullLower.contains("you:")) return null;
        int youColon = fullLower.indexOf("you:");
        if (youColon == -1) return null;
        int targetPos = fullLower.indexOf(targetLower);
        if (targetPos > youColon) return null;

        String after = full.substring(youColon + "you:".length()).trim();
        if (after.isEmpty()) return null;
        return after;
    }

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

    private static void handlePositiveInterestReply(MinecraftClient client) {
        if (client == null || client.player == null || targetPlayer == null) return;

        sendThrottledPrivateMessage(targetPlayer, buildJoinFollowupMessage());

        if ("catpvp".equalsIgnoreCase(beamServer)) {
            sendThrottledPrivateMessage(targetPlayer, "alr im disconnecting now and hopping on server, join quick");
            client.player.sendMessage(
                Text.literal("Player interested! Link sent. Announced disconnect, leaving now, then requeueing in 10s...")
                    .formatted(Formatting.GREEN),
                false);
            postPositiveActive = false;
            postPositiveWaitTicks = 0;
            sendLeaveForCurrentServer(client);
            targetPlayer = null;
            tournamentSent = false;
            restartAfterLeave = true;
            leaveWaitTicks = 0;
            return;
        }

        client.player.sendMessage(
            Text.literal("Player interested! Link sent. Waiting 45s for more messages, then leaving...").formatted(Formatting.GREEN),
            false);
        postPositiveActive = true;
        postPositiveWaitTicks = 0;
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
        introReplyWaitActive = false;
        introReplyWaitTicks = 0;
        flowPvpAwaitingMatch = false;
        flowPvpMatchTick = -1;
        catPvpAwaitingMatch = false;
        catPvpMatchTick = -1;
        startBeamEntryAction(client);
    }

    private static void startBeamEntryAction(MinecraftClient client) {
        if (client.player == null) return;
        int moveSeconds = getMoveTicksForServer() / 20;
        if ("minemen".equalsIgnoreCase(beamServer)) {
            // 3rd hotbar slot (1-based) is index 2.
            client.player.getInventory().selectedSlot = 2;
            if (client.interactionManager != null) {
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }
            client.player.sendMessage(
                Text.literal("Minemen mode: selected slot 3 and right-clicked. Waiting 7s, then moving forward " + moveSeconds + "s, then scanning...")
                    .formatted(Formatting.GREEN),
                false);
            BeamQueueLog.info("/beam start action: minemen (slot3 + right-click)");
            return;
        }
        if ("flowpvp".equalsIgnoreCase(beamServer)) {
            // 2nd hotbar slot (1-based) is index 1.
            client.player.getInventory().selectedSlot = 1;
            if (client.interactionManager != null) {
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }
            scheduleFlowPvpQueueClick(CONTAINER_QUEUE_CLICK_RETRIES, FLOWPVP_QUEUE_INITIAL_DELAY_MS);
            flowPvpAwaitingMatch = true;
            flowPvpMatchTick = -1;
            catPvpAwaitingMatch = false;
            catPvpMatchTick = -1;
            client.player.sendMessage(
                Text.literal("FlowPvP mode: selected slot 2, right-clicked, waiting 3s, then queue slot 10. Waiting for match message, then moving forward " + moveSeconds + "s, then scanning...")
                    .formatted(Formatting.GREEN),
                false);
            BeamQueueLog.info("/beam start action: flowpvp (slot2 + right-click + container slot10)");
            return;
        }
        if ("catpvp".equalsIgnoreCase(beamServer)) {
            // 2nd hotbar slot (1-based) is index 1.
            client.player.getInventory().selectedSlot = 1;
            if (client.interactionManager != null) {
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }
            scheduleCatPvpQueueClick(CONTAINER_QUEUE_CLICK_RETRIES, CATPVP_QUEUE_INITIAL_DELAY_MS);
            catPvpAwaitingMatch = true;
            catPvpMatchTick = -1;
            flowPvpAwaitingMatch = false;
            flowPvpMatchTick = -1;
            client.player.sendMessage(
                Text.literal("CatPvP mode: selected slot 2, right-clicked, waiting 2s, then queue slots 10 -> 11. Waiting for opponent match message, then moving forward " + moveSeconds + "s, then scanning...")
                    .formatted(Formatting.GREEN),
                false);
            BeamQueueLog.info("/beam start action: catpvp (slot2 + right-click + container slots 10 -> 11)");
            return;
        }
        flowPvpAwaitingMatch = false;
        flowPvpMatchTick = -1;
        catPvpAwaitingMatch = false;
        catPvpMatchTick = -1;
        client.player.networkHandler.sendChatCommand("queue " + queueMode);
        client.player.sendMessage(
            Text.literal("Queued " + queueMode + "! Waiting 7s, then moving forward " + moveSeconds + "s, then scanning...").formatted(Formatting.GREEN),
            false);
        BeamQueueLog.info("/beam start action: mcpvp queue {}", queueMode);
    }

    private static int getMoveTicksForServer() {
        return ("minemen".equalsIgnoreCase(beamServer) || "flowpvp".equalsIgnoreCase(beamServer) || "catpvp".equalsIgnoreCase(beamServer)) ? TICKS_3_SEC : TICKS_6_SEC;
    }

    private static void scheduleFlowPvpQueueClick(int retriesLeft, long delayMs) {
        scheduleContainerQueueClick(FLOWPVP_CONTAINER_SLOT_INDEX, retriesLeft, delayMs, "FlowPvP");
    }

    private static void scheduleCatPvpQueueClick(int retriesLeft, long delayMs) {
        scheduleContainerQueueClick(CATPVP_STAGE1_CONTAINER_SLOT_INDEX, retriesLeft, delayMs, "CatPvP stage1", () ->
            scheduleContainerQueueClick(CATPVP_STAGE2_CONTAINER_SLOT_INDEX, retriesLeft, CATPVP_STAGE2_DELAY_MS, "CatPvP stage2"));
    }

    private static void scheduleContainerQueueClick(int slotIndex, int retriesLeft, long delayMs, String modeLabel) {
        scheduleContainerQueueClick(slotIndex, retriesLeft, delayMs, modeLabel, null);
    }

    private static void scheduleContainerQueueClick(int slotIndex, int retriesLeft, long delayMs, String modeLabel, Runnable onSuccess) {
        MSG_SCHEDULER.schedule(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c == null) return;
            c.execute(() -> {
                if (c.player == null || c.interactionManager == null) return;
                // Wait until container is open, then right-click configured container slot.
                if (c.player.currentScreenHandler != null && c.player.currentScreenHandler != c.player.playerScreenHandler) {
                    int syncId = c.player.currentScreenHandler.syncId;
                    c.interactionManager.clickSlot(syncId, slotIndex, 1, SlotActionType.PICKUP, c.player);
                    BeamQueueLog.info("{} queue click sent on container slot {}", modeLabel, slotIndex + 1);
                    if (onSuccess != null) onSuccess.run();
                    return;
                }
                if (retriesLeft > 0) {
                    scheduleContainerQueueClick(slotIndex, retriesLeft - 1, FLOWPVP_QUEUE_CLICK_RETRY_MS, modeLabel, onSuccess);
                } else {
                    BeamQueueLog.warn("{} queue click skipped: container did not open in time", modeLabel);
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void tickAutoReconnect(MinecraftClient client) {
        if (!autoReconnectEnabled) return;
        // Connected again; clear reconnect state.
        if (client.player != null && client.world != null) {
            pendingAutoReconnect = false;
            autoReconnectWaitTicks = 0;
            return;
        }
        if (!pendingAutoReconnect) return;
        autoReconnectWaitTicks++;
        if (autoReconnectWaitTicks < TICKS_AUTO_RECONNECT_DELAY) return;
        autoReconnectWaitTicks = 0;
        if (lastServerAddress == null || lastServerAddress.isBlank()) {
            BeamQueueLog.warn("Auto reconnect skipped: no known server address");
            return;
        }
        boolean started = tryReconnect(client);
        if (started) {
            BeamQueueLog.info("Auto reconnect attempt started -> {}", lastServerAddress);
        } else {
            BeamQueueLog.warn("Auto reconnect attempt failed to launch");
        }
    }

    private static void captureCurrentServer(MinecraftClient client) {
        try {
            Method m = client.getClass().getMethod("getCurrentServerEntry");
            Object entry = m.invoke(client);
            if (entry == null) return;
            Field addrField = entry.getClass().getField("address");
            Object addrObj = addrField.get(entry);
            if (addrObj != null) {
                String addr = String.valueOf(addrObj).trim();
                if (!addr.isBlank()) lastServerAddress = addr;
            }
            try {
                Field nameField = entry.getClass().getField("name");
                Object nameObj = nameField.get(entry);
                if (nameObj != null && !String.valueOf(nameObj).isBlank()) {
                    lastServerName = String.valueOf(nameObj);
                }
            } catch (NoSuchFieldException ignored) {
                // best effort
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void captureServerFromHandler(Object handler) {
        if (handler == null) return;
        try {
            Method getConnection = handler.getClass().getMethod("getConnection");
            Object connection = getConnection.invoke(handler);
            if (connection == null) return;
            Method getAddress = connection.getClass().getMethod("getAddress");
            Object address = getAddress.invoke(connection);
            if (address == null) return;
            String raw = String.valueOf(address).trim();
            // Typical values: "/host:port" or "host/ip:port". Keep the part after last '/'.
            if (raw.startsWith("/")) raw = raw.substring(1);
            int slash = raw.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < raw.length()) raw = raw.substring(slash + 1);
            if (!raw.isBlank()) lastServerAddress = raw;
        } catch (Exception ignored) {
            // best effort
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean tryReconnect(MinecraftClient client) {
        try {
            Class<?> connectScreenClass = Class.forName("net.minecraft.client.gui.screen.multiplayer.ConnectScreen");
            Class<?> titleScreenClass = Class.forName("net.minecraft.client.gui.screen.TitleScreen");
            Class<?> serverAddressClass = Class.forName("net.minecraft.client.network.ServerAddress");
            Class<?> serverInfoClass = Class.forName("net.minecraft.client.network.ServerInfo");
            Class<?> serverTypeClass = Class.forName("net.minecraft.client.network.ServerInfo$ServerType");

            Method parse = serverAddressClass.getMethod("parse", String.class);
            Object address = parse.invoke(null, lastServerAddress);
            Object serverTypeOther = Enum.valueOf((Class<Enum>) serverTypeClass, "OTHER");
            Constructor<?> infoCtor = serverInfoClass.getConstructor(String.class, String.class, serverTypeClass);
            Object info = infoCtor.newInstance(lastServerName, lastServerAddress, serverTypeOther);
            Object title = titleScreenClass.getConstructor().newInstance();

            for (Method method : connectScreenClass.getMethods()) {
                if (!method.getName().equals("connect")) continue;
                Class<?>[] pt = method.getParameterTypes();
                if (pt.length < 4) continue;
                Object[] args = new Object[pt.length];
                for (int i = 0; i < pt.length; i++) {
                    Class<?> t = pt[i];
                    if (t.isInstance(title)) args[i] = title;
                    else if (t.isInstance(client)) args[i] = client;
                    else if (t.equals(serverAddressClass)) args[i] = address;
                    else if (t.equals(serverInfoClass)) args[i] = info;
                    else if (t.equals(boolean.class) || t.equals(Boolean.class)) args[i] = false;
                    else args[i] = null;
                }
                method.invoke(null, args);
                return true;
            }
        } catch (Exception e) {
            BeamQueueLog.warn("Auto reconnect error: {}", e.getMessage());
        }
        return false;
    }

    private static void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) setForwardPressed(client, false);
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
        introReplyWaitActive = false;
        introReplyWaitTicks = 0;
        flowPvpAwaitingMatch = false;
        flowPvpMatchTick = -1;
        catPvpAwaitingMatch = false;
        catPvpMatchTick = -1;
        lastProcessedMessageKey = null;
    }

    private static void maybeHandleFlowPvpMatchDetected(MinecraftClient client, String plainMessage) {
        if (client == null || client.player == null) return;
        if (!active) return;
        boolean flowMode = "flowpvp".equalsIgnoreCase(beamServer);
        boolean catMode = "catpvp".equalsIgnoreCase(beamServer);
        if (!flowMode && !catMode) return;
        if (flowMode && (!flowPvpAwaitingMatch || flowPvpMatchTick >= 0)) return;
        if (catMode && (!catPvpAwaitingMatch || catPvpMatchTick >= 0)) return;
        if (plainMessage == null) return;
        String lower = plainMessage.toLowerCase();
        if (flowMode) {
            if (!lower.contains(FLOWPVP_MATCH_TEXT)) return;
            flowPvpMatchTick = ticksSinceStart;
            flowPvpAwaitingMatch = false;
            BeamQueueLog.info("FlowPvP match detected at tick {} -> starting movement phase", flowPvpMatchTick);
            client.player.sendMessage(Text.literal("FlowPvP match found. Starting movement now...").formatted(Formatting.GREEN), false);
            return;
        }
        if (!isCatPvpMatchMessage(lower)) return;
        catPvpMatchTick = ticksSinceStart;
        catPvpAwaitingMatch = false;
        BeamQueueLog.info("CatPvP match detected at tick {} -> starting movement phase", catPvpMatchTick);
        client.player.sendMessage(Text.literal("CatPvP opponent found. Starting movement now...").formatted(Formatting.GREEN), false);
    }

    private static boolean isCatPvpMatchMessage(String lowerPlainMessage) {
        if (lowerPlainMessage == null) return false;
        // Avoid false positives from our own local status/help messages.
        if (lowerPlainMessage.startsWith("catpvp mode:")) return false;
        if (lowerPlainMessage.contains("waiting for 'found opponent!'")) return false;

        // Match the actual server notification line.
        return lowerPlainMessage.startsWith(CATPVP_MATCH_TEXT);
    }

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

    private static void sendLeaveForCurrentServer(MinecraftClient client) {
        if (client == null || client.player == null) return;
        client.player.networkHandler.sendChatCommand("leave");
        if (!"flowpvp".equalsIgnoreCase(beamServer)) return;
        MSG_SCHEDULER.schedule(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c == null) return;
            c.execute(() -> {
                if (c.player != null) {
                    c.player.networkHandler.sendChatCommand("leave");
                }
            });
        }, FLOWPVP_LEAVE_SECOND_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void sendLeaveTwiceWithDelay(MinecraftClient client) {
        if (client == null || client.player == null) return;
        client.player.networkHandler.sendChatCommand("leave");
        MSG_SCHEDULER.schedule(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c == null) return;
            c.execute(() -> {
                if (c.player != null) {
                    c.player.networkHandler.sendChatCommand("leave");
                }
            });
        }, FLOWPVP_LEAVE_SECOND_DELAY_MS, TimeUnit.MILLISECONDS);
    }

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

    public static void handleAiDecline(String target, String aiReply) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        if (target != null && !target.isBlank() && aiReply != null && !aiReply.isBlank()) {
            sendThrottledPrivateMessage(target, aiReply);
        }

        sendLeaveForCurrentServer(client);
        client.player.sendMessage(
            Text.literal("Target declined (AI). Leaving queue, restarting in 10s...").formatted(Formatting.GREEN),
            false);

        targetPlayer = null;
        tournamentSent = false;
        introReplyWaitActive = false;
        introReplyWaitTicks = 0;
        restartAfterLeave = true;
        leaveWaitTicks = 0;
    }

    public static void sendThrottledPrivateMessage(String target, String text) {
        if (target == null || target.isBlank() || text == null || text.isBlank()) return;
        if (!isValidTargetUsername(target)) {
            BeamQueueLog.warn("Skipped private message due to invalid target username: {}", target);
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        long now = System.currentTimeMillis();
        long delta = now - lastPrivateMsgAt;
        long delayMs = delta >= PRIVATE_MSG_GAP_MS ? 0L : (PRIVATE_MSG_GAP_MS - delta);
        lastPrivateMsgAt = now + delayMs;

        Runnable sendTask = () -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c == null) return;
            c.execute(() -> {
                if (c.player != null) {
                    c.player.networkHandler.sendChatCommand("msg " + target + " " + text);
                }
            });
        };

        if (delayMs == 0L) {
            sendTask.run();
            return;
        }
        MSG_SCHEDULER.schedule(sendTask, delayMs, TimeUnit.MILLISECONDS);
    }

    public static String getServerIpPlain() {
        return sanitizeCommandValue(serverIpPlain);
    }

    public static String getServerIpMasked() {
        return maskDots(getServerIpPlain());
    }

    public static String getDiscordInvitePlain() {
        return sanitizeCommandValue(discordInvitePlain);
    }

    public static String getDiscordInviteMasked() {
        return maskDots(getDiscordInvitePlain());
    }

    public static boolean isShareModeDiscord() {
        return "discord".equalsIgnoreCase(shareMode);
    }

    public static String getShareTargetPlain() {
        return isShareModeDiscord() ? getDiscordInvitePlain() : getServerIpPlain();
    }

    public static String getShareTargetMasked() {
        return isShareModeDiscord() ? getDiscordInviteMasked() : getServerIpMasked();
    }

    public static String getIntroMsg1() {
        return sanitizeCommandValue(introMsg1);
    }

    public static String getIntroMsg2() {
        return sanitizeCommandValue(introMsg2);
    }

    private static String buildJoinFollowupMessage() {
        return "join " + getShareTargetMasked() + " for the tournament quick it starts in 10 mins";
    }

    private static boolean shouldForwardToAi(String replyLower) {
        if (replyLower == null) return false;
        String t = replyLower.trim();
        if (t.isEmpty()) return false;
        // Ignore very short / filler chat so AI doesn't send awkward auto-replies like "alr bet".
        return !(t.equals("gg") || t.equals("g") || t.equals("ok") || t.equals("k") || t.equals("kk")
            || t.equals("hi") || t.equals("yo") || t.equals("sup") || t.equals("lol")
            || t.equals("lmao") || t.equals("bruh") || t.equals("hmm"));
    }

    private static boolean isValidTargetUsername(String username) {
        if (username == null) return false;
        String trimmed = stripFormatting(username).trim();
        return MINECRAFT_USERNAME_PATTERN.matcher(trimmed).matches();
    }

    private static void markTargetReplied() {
        introReplyWaitActive = false;
        introReplyWaitTicks = 0;
    }

    private static String sanitizeCommandValue(String raw) {
        if (raw == null) return "";
        String out = raw.trim();
        out = out.replaceAll("\\s*/\\s*", "/");
        out = out.replaceAll("\\s+", " ");
        return out;
    }

    private static String maskDots(String input) {
        if (input == null) return "";
        return input.replace(".", " [dot] ");
    }
}

