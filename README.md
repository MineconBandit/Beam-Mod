# Beam Queue

Client-side Fabric mod for Minecraft 1.21.4 that automates queuing for sword PvP and messaging nearby players for 2v2 tournaments.

- **Mod ID:** `beamqueue`
- **Version:** 1.0.0
- **Client-side only;** no server dependency.

## Requirements

- Minecraft 1.21.4
- Fabric Loader 0.16.7+
- Fabric API
- Java 21

## Build & run

**Gradle wrapper:** If `gradlew` or `gradlew.bat` fails (e.g. missing `gradle/wrapper/gradle-wrapper.jar`), install [Gradle](https://gradle.org/install/) and run in the project root: `gradle wrapper`. Or use the [Fabric template generator](https://fabricmc.net/develop/template/) and copy the `gradle/wrapper` folder and `gradlew`/`gradlew.bat` into this project.

**Java version:** The mod is built for Java 21. If you see `Unsupported class file major version 69`, one of the build plugins was compiled with a newer JDK—try setting `JAVA_HOME` to JDK 21 when running Gradle, or use a JDK that supports the required class file version.

### Generate Minecraft sources (for IDE)

```bash
./gradlew genSources
```

On Windows:

```bash
.\gradlew.bat genSources
```

### Run the game with the mod

```bash
./gradlew runClient
```

On Windows:

```bash
.\gradlew.bat runClient
```

### Build the JAR

```bash
./gradlew build
```

Output: `build/libs/beamqueue-1.0.0.jar`

Install Fabric for 1.21.4, put this JAR and [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder, then launch the game.

## Usage

1. Join a server (e.g. Hypixel or a local server).
2. In chat, run: `/beam`
3. The mod will:
   - Send `/queue sword`
   - After **7 seconds**, move forward **6 seconds**, then scan for the nearest player within 50 blocks (up to **4 scans** at 13s, 20s, 27s, 34s if no one is found)
   - Message them "hi", then 1s later the 2v2 tournament invite
   - Listen for **all** whispers from that player:
- **Positive** ("yes", "sure", "join", etc.) → send short join link (`join feather-mc [dot] net, add donutskelesz on dc`), then **immediately** `/leave`, wait 5s and restart from `/queue sword`
   - **Anything else** → if an OpenAI API key is set, the mod uses AI to reply with event info (when: 20 mins, where: feather-mc [dot] net, how many: ~50, Discord: donutskelesz)
   - Time out after **90 seconds** if nothing happens

Only one `/beam` run is active at a time. State resets on disconnect or world change. If you **die** while beam is active (mod detects chat messages like "X was slain by Y"), it waits 4s, runs `/leave`, then 5s and restarts. If you see **queue cooldown** (e.g. "You are on queue cooldown for 6m, 54s due to leaving the match too often"), the mod waits that full duration then runs `startBeamAgain` (requeue).

### AI replies (optional, g4f.dev)

The mod uses the [g4f.dev](https://github.com/gpt4free/g4f.dev) API. Defaults: **`https://g4f.space/api/groq/v1/chat/completions`** with model **`meta-llama/llama-4-maverick-17b-128e-instruct`** (Groq Llama 4; no API key required). Use g4f.space for server-side requests—g4f.dev often returns **405** for non-browser requests.

To enable AI-powered answers (e.g. "when's the event?", "where?", "discord?"):

- The mod uses a hardcoded API key by default; you can override via **`<game-dir>/config/beamqueue.properties`** or env **`BEAMQUEUE_API_KEY`**.
- Optional in config: `api_url=...`, `model=...` (default URL and model above).

Without a valid API key, the mod still works: it only auto-replies to positive answers with the join link; other messages use fallback replies when the API fails.

**Why the AI might return 500 or fail**

- **Service down / Cloudflare** – You may see `status=500` and an HTML body; the mod falls back to hardcoded answers.
- **405 Not Allowed** – g4f.dev may reject non-browser requests. Use `api_url=https://g4f.space/api/groq/v1/chat/completions` (default).
- **404 / wrong model** – Use the default Groq URL and model, or set `api_url` and `model` in config to match a supported endpoint.
- **Rate limiting** – The mod falls back to hardcoded answers when the API fails.

### Debugging and logging

The mod logs to the **BeamQueue** logger. In your game folder, check **`logs/latest.log`** (or the launcher’s log file).

- **Normal runs:** Important events are logged (e.g. `/beam` started, scan attempts, target found, GAME/CHAT message from target, positive/decline/AI, disconnect).
- **Verbose debug:** Set **`debug=true`** in `config/beamqueue.properties` or env **`BEAMQUEUE_DEBUG=true`** to get extra logs: tick phase, world null pause, prefix match failures, forward key, scan distances, AI API status/length.

## Development

- **VS Code:** Open the project folder; use Java Extension Pack and Gradle for Java. Run `genSources` then use Gradle tasks: `runClient`, `build`.
- **IntelliJ:** Import as Gradle project, run `genSources`, then use the Gradle tool window to run `runClient` or `build`.

## License

CC0-1.0
