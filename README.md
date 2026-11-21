<h1 align="center">MyAppVPN — source code</h1>

<p align="center"><b>Source of the MyAppVPN Android app: Kotlin + Jetpack Compose on top of Xray, tun2socks and OpenFlux.</b></p>

<p align="center">
  <b>English</b> · <a href="README.ru.md">Русский</a>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License: GPL-3.0"></a>
  <a href="https://github.com/indelingDanil/MyAppVPN/releases/latest"><img src="https://img.shields.io/github/v/release/indelingDanil/MyAppVPN?label=app%20release" alt="Latest app release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.2">
  <img src="https://img.shields.io/badge/Go-1.26-00ADD8?logo=go&logoColor=white" alt="Go 1.26">
  <a href="https://t.me/myappvpn_dprog"><img src="https://img.shields.io/badge/Telegram-channel-26A5E4?logo=telegram&logoColor=white" alt="Telegram channel"></a>
</p>

## What this is

This repository holds the full source of **[MyAppVPN](https://github.com/indelingDanil/MyAppVPN)**, an Xray / VLESS client for Android that finds a working server by itself. It contains the Android app, the Go helpers that ship inside it as native executables, and the Node.js server for the OpenFlux exit.

**Looking for the app?** APKs, the changelog, screenshots and the FAQ are in the main repo: [indelingDanil/MyAppVPN → Releases](https://github.com/indelingDanil/MyAppVPN/releases/latest).

## Architecture

```
 apps ──► Android VpnService (TUN)
            │
            ▼
   libnetstack.so  (tun2socks-owner) ── "who owns this flow?" ──► OwnerUidServer (app)
            │  SOCKS5, loopback, random port, user/password
            ▼
   libcore.so  (Xray-core) ──────────────────────────────► VLESS server (REALITY / TLS)
            │
            └─ OpenFlux mode ─► libofbridge.so (SOCKS5) ─► your exit: Node v2 over HTTP(S),
                                                           or an OpenFlux core carrier
```

- **App** (`app/`) — Kotlin, Jetpack Compose, Hilt, Room, DataStore, WorkManager. Fetches subscriptions, parses `vless://` links, checks servers, builds the Xray config and runs `MyVpnService` (watchdog, failover, kill switch, split tunneling, hotspot sharing).
- **Xray** — `libcore.so` is Xray-core **v26.2.6**, started with `ProcessBuilder` (`run -c config.json`). It exposes a password-protected SOCKS5 inbound on a random loopback port.
- **tun2socks** — `libnetstack.so` is our **tun2socks-owner** build: [tun2socks v2.6.0](https://github.com/xjasonlyu/tun2socks) (MIT) plus a per-flow **owner check**. It is started through a small JNI `fork()+exec()` helper (`app/src/main/cpp/tun_helper.cpp`) so it inherits the TUN file descriptor.
- **Owner check** — for every new TCP connection / UDP session, tun2socks asks the app over an abstract Unix socket which UID owns the flow (`ConnectivityManager.getConnectionOwnerUid`, Android 10+). Flows from apps outside the VPN that bound to `tun0` are dropped ([issue #8](https://github.com/indelingDanil/MyAppVPN/issues/8)). See `OwnerUidServer.kt` and `tun2socks-owner/owner/`.
- **OpenFlux bridge** — `libofbridge.so` is a Go SOCKS5 server (CONNECT only, loopback, auth) that links the [OpenFlux core](https://github.com/p1neappleXpress/OpenFlux) **v0.17.1** (GPL-3.0). By default it tunnels streams to your own exit over the resumable v2 protocol (`openflux-bridge/PROTOCOL-v2.md`); `-transport mailru|cups|session` switches to the core's carriers and `openflux://` links. It also has a `-diag` mode used by the in-app connection diagnostics.
- **Node exit** — `openflux-bridge/server-node/` is the server side of protocol v2: plain Node.js 20+, no npm dependencies. Hosting guide: the [`openflux-exit`](https://github.com/indelingDanil/MyAppVPN/tree/openflux-exit) branch of the main repo.

Native executables live in `app/src/main/jniLibs/<abi>/` under generic library names, so Android extracts them to `nativeLibraryDir`, where they are allowed to run (`useLegacyPackaging = true`).

## Repository layout

```
app/
  src/main/java/com/myappvpn/
    data/            subscriptions, parser, Room DB, DataStore
    di/              Hilt modules
    domain/          connection controller, server validation, OpenFlux diagnostics
    service/         MyVpnService, Xray / tun2socks / OpenFlux managers, OwnerUidServer
    ui/              Compose screens, view models, theme
    util/            Xray config builder, DNS, binary paths, JNI helper
    worker/          background server refresh
  src/main/cpp/      tun_helper.cpp (JNI fork+exec that keeps the TUN fd)
  src/main/jniLibs/  arm64-v8a/ and armeabi-v7a/: libcore.so, libnetstack.so, libofbridge.so
  src/main/assets/xray/  Xray LICENSE and README; geoip.dat / geosite.dat go here (not in git)
openflux-bridge/     Go OpenFlux bridge (libofbridge.so), protocol spec, NOTICE
  third_party/sctp/  OpenFlux's patched pion/sctp (MIT)
  server-node/       Node.js OpenFlux v2 exit + tests
tun2socks-owner/     Go tun2socks build with the owner check (libnetstack.so), NOTICE
gradle/libs.versions.toml   all library and plugin versions
```

## Building

### Requirements

| Tool | Version |
|---|---|
| Android Studio | recent stable (AGP 9.1, Gradle 9.3 wrapper) |
| JDK | 17 or newer — the JBR bundled with Android Studio works |
| Android SDK | platform 36 (`compileSdk = 36`) |
| Android NDK | **28.2.13676358** (pinned in `app/build.gradle.kts`) |
| CMake | 3.22.1 (SDK Manager → SDK Tools) |
| Go | **1.26.6+** for `openflux-bridge`, 1.23+ for `tun2socks-owner` — only to rebuild native parts |
| Node.js | 20+ — only for the exit server and its tests |

The native executables are committed, so building the app itself needs only the Android toolchain.

### 1. Clone and add geo files

```bash
git clone https://github.com/indelingDanil/MyAppVPN-CodeBase.git
cd MyAppVPN-CodeBase
```

`geoip.dat` and `geosite.dat` are not tracked, but they are required: the APK builds without them, yet Xray will not start (the config uses `geoip:private`). Take them from an [Xray-core release](https://github.com/XTLS/Xray-core/releases/tag/v26.2.6) archive and put them into `app/src/main/assets/xray/`. The app copies them to its files directory on first start; the Xray config uses `geoip:private`.

### 2. Build the APKs

Linux / macOS:

```bash
export JAVA_HOME="/path/to/android-studio/jbr"
./gradlew assembleDebug
```

Windows (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

Or open the folder in Android Studio and press **Run**. ABI splits produce one APK per architecture:

```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

Notes:

- Debug builds use the application ID `com.myappvpn.debug`, so they install next to the release app.
- Each ABI gets its own version code (`versionCode × 10 + 1` for armeabi-v7a, `+ 2` for arm64-v8a), so a 64-bit phone can always update to the arm64 APK.
- `./gradlew assembleRelease` runs R8 and resource shrinking. No signing config is committed: sign with your own keystore and keep it out of git (`*.jks` / `*.keystore` are ignored).
- Optional for tester builds: `-PofPresetUrl=… -PofPresetToken=…` bakes in an OpenFlux exit that is applied once on a fresh install. Empty by default. Never commit real values.

## Native binaries

All of them are built for `arm64-v8a` and `armeabi-v7a`, linked against Android API level 26 (the app's minSdk).

### `libnetstack.so` — tun2socks-owner

```bash
cd tun2socks-owner
./build-android.sh --test --install   # go test, build into out/<abi>/, copy into app/src/main/jniLibs
```

### `libofbridge.so` — OpenFlux bridge

```bash
cd openflux-bridge
./build-android.sh --install          # builds ofbridge-android-arm64 / -arm, copies them as libofbridge.so
```

Both scripts locate the newest NDK under `$ANDROID_HOME/ndk` (set `ANDROID_NDK` to override) and use `GO` if set. On Windows run them from Git Bash. The bridge needs `-checklinkname=0`, which the script already passes (the OpenFlux core's dependencies use `go:linkname`).

### `libcore.so` — Xray-core v26.2.6

Unmodified Xray-core, renamed. No build script is kept for it: use the Android build from the [v26.2.6 release](https://github.com/XTLS/Xray-core/releases/tag/v26.2.6), or build `./main` from the Xray-core source with the NDK clang for the target ABI (see the "One-line Compilation" section of the Xray README), then copy the binary to `app/src/main/jniLibs/<abi>/libcore.so`.

## Tests

```bash
(cd openflux-bridge && go test ./...)                   # bridge: carriers, mux, protocol
(cd tun2socks-owner && go test ./...)                   # owner check
(cd openflux-bridge/server-node && node --test)        # Node exit + Mail.ru channel
```

The app has no unit-test module yet. Releases are checked on real phones.

## License

MyAppVPN is free software under the **[GNU GPL v3.0](LICENSE)**. It is GPL because the OpenFlux bridge links the GPL-3.0 OpenFlux core.

Third-party components and their notices:

| Component | License | Notice |
|---|---|---|
| [OpenFlux core](https://github.com/p1neappleXpress/OpenFlux) v0.17.1 and its dependencies | GPL-3.0 (+ MIT / BSD / Apache-2.0 deps) | [`openflux-bridge/NOTICE`](openflux-bridge/NOTICE) |
| pion/sctp v1.11.3, OpenFlux-patched copy | MIT | [`openflux-bridge/third_party/sctp/LICENSE`](openflux-bridge/third_party/sctp/LICENSE) |
| [tun2socks](https://github.com/xjasonlyu/tun2socks) v2.6.0 | MIT | [`tun2socks-owner/NOTICE`](tun2socks-owner/NOTICE), [`tun2socks-owner/LICENSE.tun2socks`](tun2socks-owner/LICENSE.tun2socks) |
| [Xray-core](https://github.com/XTLS/Xray-core) v26.2.6 | MPL-2.0 | [`app/src/main/assets/xray/LICENSE`](app/src/main/assets/xray/LICENSE) |

Android libraries and their versions are listed in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Contributing

Bug reports, ideas and questions go to the **main repo**: bugs → [#4](https://github.com/indelingDanil/MyAppVPN/issues/4), ideas → [#5](https://github.com/indelingDanil/MyAppVPN/issues/5), chat → [#6](https://github.com/indelingDanil/MyAppVPN/issues/6). Pull requests to this repo are welcome. Keep them small and say how you tested them. By contributing you agree that your code is released under GPL-3.0.

Never commit keystores, OpenFlux tokens, exit URLs or `config.json` files.

## Links

- **App downloads and changelog:** [indelingDanil/MyAppVPN](https://github.com/indelingDanil/MyAppVPN/releases)
- **Auto-collected subscription:** [indelingDanil/MyAppVPN-subs](https://github.com/indelingDanil/MyAppVPN-subs)
- **OpenFlux exit setup:** [`openflux-exit` branch](https://github.com/indelingDanil/MyAppVPN/tree/openflux-exit)
- **News:** [Telegram channel](https://t.me/myappvpn_dprog)

## Disclaimer

> ⚠️ This is an **educational** project, made for learning and research purposes. The author **is not responsible** for how you use the code or the app. In Russia, VPN services for bypassing blocks are restricted by law and may be considered illegal. Use at your own risk.
