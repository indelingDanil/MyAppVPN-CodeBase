# MyAppVPN — Setup Instructions

## Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK API 34

## Step 1: Open Project
Open the `MyAppVPN` folder in Android Studio.

## Step 2: Download XRay Binaries (REQUIRED)

Download the official XRay core binaries and place them in `app/src/main/assets/xray/`:

```
# ARM64 (most modern devices)
URL: https://github.com/XTLS/Xray-core/releases/latest
File: Xray-android-arm64-v8a.zip
Extract: xray → app/src/main/assets/xray/xray-arm64-v8a

# ARM32 (older devices)
File: Xray-android-arm-v7a.zip
Extract: xray → app/src/main/assets/xray/xray-armeabi-v7a

# x86_64 (emulators/some tablets)
File: Xray-android-x86_64.zip
Extract: xray → app/src/main/assets/xray/xray-x86_64
```

## Step 3: Download tun2socks Binaries (REQUIRED)

Download tun2socks from https://github.com/xjasonlyu/tun2socks/releases/latest

Place binaries in `app/src/main/assets/tun2socks/`:
```
tun2socks-linux-arm64 → tun2socks-arm64-v8a
tun2socks-linux-arm   → tun2socks-armeabi-v7a
tun2socks-linux-amd64 → tun2socks-x86_64
```

> **Important**: These are Linux ARM binaries that run on Android.
> Look for files named `tun2socks-android-arm64` or similar in the releases.

## Step 4: Build & Run

```bash
./gradlew assembleDebug
```

Or press **Run** (Shift+F10) in Android Studio.

> **Note**: VPN functionality requires a **physical Android device**.
> Android Emulator does NOT support VpnService.

## Project Structure

```
app/src/main/assets/
├── xray/
│   ├── xray-arm64-v8a       ← Place XRay binary here
│   ├── xray-armeabi-v7a
│   └── xray-x86_64
└── tun2socks/
    ├── tun2socks-arm64-v8a  ← Place tun2socks binary here
    ├── tun2socks-armeabi-v7a
    └── tun2socks-x86_64
```

## Config Source

The app automatically fetches VLESS configs from:
- **Lite** (recommended): `https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt`
- **Full**: `https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt`

Configs update every ~2 minutes on GitHub. The app auto-refreshes every 24 hours.

## Permissions

The app requests:
- **VPN permission**: Required — Android will show system dialog on first connect
- **Notifications**: Optional — shows VPN status in notification bar
- **QUERY_ALL_PACKAGES**: For Split Tunneling app list
