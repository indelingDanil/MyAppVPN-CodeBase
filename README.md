# MyAppVPN

Android VPN-приложение на Kotlin + Jetpack Compose. Протокол VLESS через XRay-core + tun2socks.

## Быстрый старт

### Требования

- **Android Studio** Ladybug (2024.2) или новее
- **JDK 17** (встроен в Android Studio)
- **Android SDK API 34** (ставится через SDK Manager)
- **NDK + CMake** (нужны для сборки JNI — `tun_helper.cpp`)
  - Android Studio → Settings → SDK Tools → ✅ NDK (Side by side) + ✅ CMake
- **Физическое Android-устройство** (эмулятор не поддерживает VpnService)

### 1. Клонировать репо

```bash
git clone https://github.com/indelingDanil/MyAppVPN-CodeBase.git
cd MyAppVPN-CodeBase
```

### 2. Открыть в Android Studio

File → Open → выбрать папку проекта. Android Studio предложит Gradle Sync — дождаться завершения.

### 3. Скачать бинарники (ОБЯЗАТЕЛЬНО)

Бинарники не включены в репо (~74 MB). Их нужно скачать вручную.

#### XRay-core

Скачать с [XTLS/Xray-core releases](https://github.com/XTLS/Xray-core/releases/latest):

| Файл в релизе | Куда положить |
|---|---|
| `Xray-android-arm64-v8a.zip` → `xray` | `app/src/main/assets/xray/xray-arm64-v8a` |

Также нужны geo-файлы (из того же релиза или [v2fly/geoip](https://github.com/v2fly/geoip/releases)):
- `geoip.dat` → `app/src/main/assets/xray/geoip.dat`
- `geosite.dat` → `app/src/main/assets/xray/geosite.dat`

#### tun2socks

Скачать с [xjasonlyu/tun2socks releases](https://github.com/xjasonlyu/tun2socks/releases/latest):

| Файл в релизе | Куда положить |
|---|---|
| `tun2socks-linux-arm64` | `app/src/main/assets/tun2socks/tun2socks-arm64-v8a` |

> Достаточно только arm64, если тестируешь на современном устройстве. Для других архитектур смотри `SETUP.md`.

### 4. Собрать и запустить

```bash
./gradlew assembleDebug
```

Или нажать **Run** (Shift+F10) в Android Studio. Debug-сборка имеет суффикс `.debug` в applicationId.

## Зависимости

Все зависимости объявлены в [`gradle/libs.versions.toml`](gradle/libs.versions.toml) (version catalog).

| Библиотека | Назначение |
|---|---|
| **Jetpack Compose** (BOM 2024.08) | UI — Material 3 |
| **Hilt** 2.57 | Dependency Injection |
| **Room** 2.6.1 | Локальная БД серверов |
| **DataStore Preferences** 1.1.1 | Настройки приложения |
| **Retrofit** 2.11 + OkHttp 4.12 | HTTP (загрузка конфигов) |
| **Navigation Compose** 2.7.7 | Навигация между экранами |
| **Lottie Compose** 6.4.1 | Анимации |
| **WorkManager** 2.9.1 | Фоновое обновление серверов (каждые 24ч) |
| **Coroutines** 1.8.1 | Асинхронность |
| **Gson** 2.10.1 | JSON (XRay конфиг) |

Плагины: AGP 9.1.0, Kotlin 2.2.10, KSP 2.2.10.

## Архитектура

```
MVVM + Clean Architecture + Hilt DI
```

### Слои

```
ui/              → Screens (Compose) + ViewModels
domain/usecase/  → Бизнес-логика (Connect, Fetch, Ping, Stats)
data/            → Repository, DAO, DataStore, API, Parser
service/         → VPN Service, XRay/tun2socks managers
util/            → Config builder, Binary installer, JNI helper
```

### Ключевые файлы

| Файл | Описание |
|---|---|
| `MyApp.kt` | Hilt Application entry point |
| `service/MyVpnService.kt` | Android VpnService — управление туннелем, watchdog, failover |
| `service/XrayManager.kt` | Запуск/остановка XRay процесса (exec native binary) |
| `service/Tun2SocksManager.kt` | Запуск/остановка tun2socks (exec через JNI fork) |
| `util/XrayConfigBuilder.kt` | Генерация JSON-конфига для XRay из VlessConfig |
| `data/parser/VlessUriParser.kt` | Парсинг VLESS URI → VlessConfig → ServerProfile |
| `data/repository/ConfigRepository.kt` | Загрузка/хранение серверов из подписок |
| `data/db/AppDatabase.kt` | Room database (version=2) |
| `cpp/tun_helper.cpp` | JNI: fork()+exec() для запуска бинарников с наследованием TUN fd |

### VPN Flow

```
Подписки (GitHub/xync.net) → VlessUriParser → ServerProfile (Room DB)
  → XrayConfigBuilder → JSON config
  → XrayManager (exec xray binary) → SOCKS5 proxy :10808
  → Tun2SocksManager (exec tun2socks, JNI fork) → TUN fd
  → MyVpnService (Android VpnService) → системный VPN
```

### Источники конфигов

- `zieng2/wl` (GitHub + xync.net зеркало) — основной источник VLESS Lite
- `igareck/vpn-configs-for-russia` — BLACK_VLESS_RUS_mobile (по умолчанию)

Все источники фетчатся параллельно. Дубликаты убираются по `id` (MD5 от URI).

### Экраны

1. **Home** — подключение, выбранный сервер, статистика трафика
2. **Servers** — список серверов, пинг, выбор
3. **Stats** — скорость, длительность сессии
4. **Settings** — подписки, DNS, split tunneling, hotspot sharing

## Структура проекта

```
MyAppVPN/
├── app/
│   ├── build.gradle.kts          ← зависимости, SDK config
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt    ← JNI build
│       │   └── tun_helper.cpp    ← fork()+exec() для бинарников
│       ├── assets/
│       │   ├── xray/             ← XRay binary + geo files (не в git)
│       │   └── tun2socks/        ← tun2socks binary (не в git)
│       ├── java/com/myappvpn/
│       │   ├── data/             ← модели, БД, API, парсер, репозиторий
│       │   ├── di/               ← Hilt модули
│       │   ├── domain/usecase/   ← use cases
│       │   ├── service/          ← VPN service, managers
│       │   ├── ui/               ← screens, viewmodels, components, theme
│       │   ├── util/             ← helpers
│       │   └── worker/           ← WorkManager задачи
│       └── res/                  ← ресурсы (иконки, строки)
├── gradle/
│   └── libs.versions.toml       ← version catalog (все версии тут)
├── build.gradle.kts              ← root build file
├── settings.gradle.kts
├── SETUP.md                      ← подробные инструкции по бинарникам
└── README.md                     ← этот файл
```

## Важные нюансы

### W^X Policy (Android 10+)
Android запрещает exec() файлов из app-writable директорий. Бинарники (xray, tun2socks) загружаются из assets в `nativeLibraryDir` (read-only) при первом запуске. См. `BinaryInstaller.kt`.

### JNI fork() для tun2socks
`ProcessBuilder` не позволяет передать TUN file descriptor дочернему процессу. Поэтому используется нативный `fork()+exec()` через JNI (`tun_helper.cpp`). Это даёт наследование fd.

### Routing Loop
VPN-приложение само исключается из туннеля (`addDisallowedApplication(packageName)`), иначе его трафик зацикливается.

## Kotlin Multiplatform / iOS

Текущий проект — чистый Android. Для iOS-порта на Kotlin:

- **Kotlin Multiplatform (KMP)** позволяет шарить бизнес-логику (парсер, модели, репозиторий) между Android и iOS
- **UI**: на iOS — SwiftUI или Compose Multiplatform
- **VPN на iOS**: используется `NetworkExtension` framework (NEPacketTunnelProvider) — это iOS-аналог Android VpnService
- **XRay на iOS**: нужна iOS-сборка XRay-core (есть: [AnyXray](https://github.com/nicaxx/AnyXray) или собрать gomobile)
- **Что переиспользуется без изменений**: `VlessConfig`, `ServerProfile`, `VlessUriParser`, `XrayConfigBuilder`, логика `ConfigRepository`
- **Что нужно писать с нуля для iOS**: VPN service (NEPacketTunnelProvider), запуск XRay/tun2socks, UI

Рекомендуемый стек для iOS:
- **Xcode 15+** на Mac
- **Kotlin Multiplatform plugin** для Android Studio
- Shared module: `shared/` с expect/actual для платформенного кода

## Сборка Release APK

```bash
./gradlew assembleRelease
```

Для подписи нужен keystore — создать через Android Studio (Build → Generate Signed APK) и указать в `local.properties` или `keystore.properties` (не коммитить!).

## Лицензия

Приватный проект. Не для распространения.
