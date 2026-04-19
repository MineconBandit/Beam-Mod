# 🚀 Beam Queue (Fabric Client Mod)

<u>Automated queue + player messaging assistant for PvP servers (Minecraft 1.21.4).</u>

## ✨ What This Mod Does

- Starts queue flow automatically.
- Finds a nearby player and sends intro + tournament invite.
- Handles replies with smart logic (yes/no/AI).
- Requeues automatically after leave, timeout, death, or cooldown.
- Shows AI health on-screen (`AI: Working / Not Working / No API Key`).

## 📦 Requirements

- **Minecraft:** `1.21.4`
- **Loader:** `Fabric Loader 0.16.7+`
- **Dependency:** `Fabric API`
- **Java:** `21`

## 🛠️ Build & Run

### Windows

```bash
.\gradlew.bat runClient
.\gradlew.bat build
```

### Linux / macOS

```bash
./gradlew runClient
./gradlew build
```

Output JAR: `build/libs/beamqueue-1.0.0.jar`

## ▶️ Quick Start

1. Join your server.
2. Run `/beam`.
3. Beam flow: enter queue, move, scan, message target, then auto-handle replies.

## 🎮 Modes

### <u>Beam Server Mode</u>

Commands:

- `/beamserver mcpvp`
- `/beamserver minemen`
- `/beamserver flowpvp`

Function:

- **mcpvp:** runs `/queue <queueMode>`
- **minemen:** selects hotbar slot 3 and right-clicks
- **flowpvp:** selects hotbar slot 2, right-clicks, then clicks container slot 10

### <u>Share Mode</u>

Commands:

- `/changemode ip <server-ip>`
- `/changemode discord <invite>`

Function:

- **ip mode:** shares server IP
- **discord mode:** shares Discord invite
- Dots are masked to `[dot]` in chat

### <u>Queue Mode</u>

Command:

- `/changequeue <mode>`

Function:

- Used by `mcpvp` server mode as `/queue <mode>`
- Default queue mode is `sword`

### <u>Auto Reconnect Mode</u>

Commands:

- `/autoreconnect on`
- `/autoreconnect off`

Function:

- If disconnected unexpectedly, reconnect attempt starts after ~10s

## 🤖 AI Mode

AI handles non-yes/non-decline target replies.

- HUD status is shown in top-left
- Health checks run automatically
- Temporary API issues do not instantly flip to red status
- Default AI endpoint is `https://g4f.space/api/ollama/chat/completions`
- AI requests include your configured API key (from `/apikey`, config, or env); model is optional

### <u>API Key (Optional)</u>

Command:

- `/apikey <your-g4f-key>` (optional)

Function:

- Saves key to `config/beamqueue.properties` if you still want to keep one configured
- Triggers immediate AI health re-check

Config/env options:

- `config/beamqueue.properties`
- `BEAMQUEUE_API_KEY`
- `BEAMQUEUE_OPENAI_API_KEY`

## 💬 Commands Reference

- `/beam` -> start automation
- `/beamstop` -> stop automation
- `/changequeue <mode>` -> set queue mode
- `/beamserver <mcpvp|minemen|flowpvp>` -> set server behavior mode
- `/changemode ip <value>` -> use IP share mode
- `/changemode discord <value>` -> use Discord share mode
- `/changeip <ip>` -> update stored IP without switching share mode
- `/autoreconnect <on|off>` -> toggle reconnect mode
- `/apikey <key>` -> optionally set and persist API key

## 🧠 Reply Logic

When target replies:

- **Positive** (`yes`, `sure`, `join`, etc.) -> sends join follow-up, waits ~45s, then leaves and requeues
- **Decline** (`no`, `nah`, `later`, `im good`, etc.) -> leaves and requeues in ~10s
- **Other** -> forwards message to AI and replies naturally

Safety helpers:

- Duplicate message dedupe
- Private message throttle
- Very short filler messages are ignored in AI path

## ⏱️ Automatic Recovery Rules

- No target after max scans -> leave + restart
- No reply after invite for 30s -> leave + restart
- Beam timeout around 90s -> restart
- Death detected -> leave + restart
- Queue cooldown detected -> wait full cooldown, then requeue

## 🧾 Logging / Debug

- Logs: `logs/latest.log`
- Enable debug with env: `BEAMQUEUE_DEBUG=true`
- Or set `debug=true` in `config/beamqueue.properties`

## 📄 License

CC0-1.0
