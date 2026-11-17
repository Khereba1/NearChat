# NearChat: handoff notes for an AI assistant

Paste this file together with the project zip when asking an AI to change the app.
The family-wide rules are in STANDARD.md (ask the owner to attach it if missing).

## What this is
Local-first organized chat & media vault (text, voice notes, images, videos, files) with external Share Sheet receiving, built-in image/video/audio players, QR-based LAN sharing (Viewer or Editor permissions via embedded HTTP server + browser Web UI + native LAN client), and offline Bluetooth Classic RFCOMM chat (no Wi-Fi needed).
Android app, Kotlin + Jetpack Compose Material3. Source package `com.khereba1.app`, applicationId `com.khereba1.nearchat`.
minSdk 29, compileSdk 35. UI languages: English (primary) + Arabic (RTL). Developer name: Khereba1.

## How it is built (IMPORTANT)
The owner has only a phone. There is NO local build. Code is pushed from Termux to GitHub and GitHub Actions
(.github/workflows/build.yml) builds the APK with Gradle 8.10.2 (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01).
You cannot compile: be careful and conservative. A mistake shows up only as a failed Actions run.
- Avoid Kotlin JVM signature clashes (`var x` with `private set` plus `fun setX(...)` breaks the build).
- Every new string needs an entry in both `values/strings.xml` and `values-ar/strings.xml`.
- Do not bump library versions without checking that the exact version exists.

## Files (app/src/main/java/com/khereba1/app)
- `App.kt`: Application entry, `AppLog` crash/error logger, `LocaleHelper`.
- `MainActivity.kt`: Main Compose host with `AnimatedContent` transitions, permissions, Share Sheet handler, Bluetooth navigation.
- `data/Models.kt`: `ChatRoom`, `ChatMessage`, `MessageType`, `LanPermission`, `RemoteChatInfo`.
- `data/NearChatDb.kt`: SQLite database + internal media storage (`filesDir/media/<chatId>/`).
- `media/VoiceRecorder.kt`: AAC `.m4a` voice note recorder (`MediaRecorder`).
- `media/AudioPlayerManager.kt`: Inline voice note player (`MediaPlayer`) with live progress & seek.
- `network/NetworkUtils.kt`: Local Wi-Fi/Hotspot IPv4 address lookup.
- `network/PhysicalNetwork.kt`: finds the real (non-VPN) default network via ConnectivityManager and watches it (debounced).
- `network/NearChatVpnService.kt`: VpnService + hev-socks5-tunnel (tun2socks) to a SOCKS host. Not sticky; protect(fd) for the core; stops itself when the built-in tunnel (127.0.0.1:10808) stops.
- `full/.../XrayTunnelManager.kt` (flavor `full`; `lite` has a stub): sing-box core via libbox. Probes the server before auto-starting the VPN, reloads on Wi-Fi/mobile switch, DNS hijack + auto_detect_interface config with fallback to the basic config.
- `network/QrGenerator.kt`: QR code bitmap generator (`com.google.zxing:core:3.5.3`).
- `network/BluetoothChatManager.kt`: Bluetooth Classic RFCOMM server/client for offline chat without Wi-Fi.
- `network/LanHttpServer.kt`: Embedded HTTP/1.1 server (JSON API, Range media streaming, embedded HTML5 Web UI).
- `network/LanShareService.kt`: Foreground Service (`dataSync`) keeping LAN server alive.
- `network/LanClient.kt`: Native HTTP client for joining a host chat from another NearChat app.
- `ui/theme/Theme.kt`: Material 3 theme & chat accent colors.
- `ui/HomeScreen.kt`: Telegram-style chat list, search, create/rename/delete, Join LAN, Bluetooth button, Settings.
- `ui/ChatScreen.kt`: Telegram-style chat bubbles, auto-scroll, voice player, video thumbnails, QR Share dialog.
- `ui/MediaViewerOverlay.kt`: Full-screen pinch-to-zoom Image Viewer & VideoView Player dialogs.
- `ui/BluetoothChatScreen.kt`: Real-time Bluetooth chat UI (Host / Connect to Paired Device).
- `ui/RemoteChatScreen.kt`: Native remote LAN chat screen (Viewer/Editor).
- `ui/SharePickerDialog.kt`: Chat picker when receiving shared items from other apps.

## REQUIRED answer format (the owner has only a phone + Termux)
1. Briefly say what you changed and why (plain language; the owner writes Egyptian Arabic mixed with English terms, reply in the same).
2. Deliver ONE zip containing only the changed/new files, complete (never snippets or diffs), with paths relative to the project root.
   **IMPORTANT: Name update zips `NearChatN.zip` (e.g. `NearChat1.zip`, `NearChat2.zip`, ...), NOT `fixN.zip`.**
3. Raise `versionCode` (+1) and `versionName` in `app/build.gradle.kts` for every functional change, and include that file.
4. Give the install command, ready to copy, with the real folder name:
   `cd ~/nearchat && unzip -o "$(ls -t ~/storage/downloads/NearChatN*.zip | head -1)" -d . && git add . && git commit -m "short message" && git push`
   (GitHub repo: Khereba1/NearChat, APK: github.com/Khereba1/NearChat/releases/tag/latest)
5. Say honestly that nothing was compiled or tested, and what to check first on the phone.
6. If the build fails, the owner sends lines containing `e: ` from the Actions log; fix only those.

## Signing and sharing
The stable signing key lives in GitHub Secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD).
Never print, commit or ask for them, and never ask for the backup folder.
`bash share_zip.sh` builds a clean `<folder>-for-AI.zip` (no keys, env files, build output or APKs) for sharing.
