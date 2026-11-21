<h1 align="center">MyAppVPN — исходный код</h1>

<p align="center"><b>Исходники Android-приложения MyAppVPN: Kotlin + Jetpack Compose поверх Xray, tun2socks и OpenFlux.</b></p>

<p align="center">
  <a href="README.md">English</a> · <b>Русский</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="Лицензия GPL-3.0"></a>
  <a href="https://github.com/indelingDanil/MyAppVPN/releases/latest"><img src="https://img.shields.io/github/v/release/indelingDanil/MyAppVPN?label=app%20release" alt="Последний релиз приложения"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.2">
  <img src="https://img.shields.io/badge/Go-1.26-00ADD8?logo=go&logoColor=white" alt="Go 1.26">
  <a href="https://t.me/myappvpn_dprog"><img src="https://img.shields.io/badge/Telegram-channel-26A5E4?logo=telegram&logoColor=white" alt="Telegram-канал"></a>
</p>

## Что это

Здесь лежит полный исходный код **[MyAppVPN](https://github.com/indelingDanil/MyAppVPN)** — клиента Xray / VLESS для Android, который сам находит рабочий сервер. В репозитории: Android-приложение, Go-помощники, которые входят в APK как нативные исполняемые файлы, и Node.js-сервер для выхода OpenFlux.

**Нужно само приложение?** APK, история версий, скриншоты и FAQ — в основном репозитории: [indelingDanil/MyAppVPN → Releases](https://github.com/indelingDanil/MyAppVPN/releases/latest).

## Архитектура

```
 приложения ──► Android VpnService (TUN)
                  │
                  ▼
   libnetstack.so  (tun2socks-owner) ── «чей это поток?» ──► OwnerUidServer (приложение)
                  │  SOCKS5, loopback, случайный порт, логин/пароль
                  ▼
   libcore.so  (Xray-core) ───────────────────────────────► VLESS-сервер (REALITY / TLS)
                  │
                  └─ режим OpenFlux ─► libofbridge.so (SOCKS5) ─► ваш выход: Node v2 по HTTP(S)
                                                                  или канал ядра OpenFlux
```

- **Приложение** (`app/`) — Kotlin, Jetpack Compose, Hilt, Room, DataStore, WorkManager. Загружает подписки, разбирает ссылки `vless://`, проверяет серверы, собирает конфиг Xray и запускает `MyVpnService` (watchdog, переключение серверов, kill switch, раздельное туннелирование, раздача через точку доступа).
- **Xray** — `libcore.so`, это Xray-core **v26.2.6**. Запускается через `ProcessBuilder` (`run -c config.json`) и поднимает SOCKS5-вход с паролем на случайном loopback-порту.
- **tun2socks** — `libnetstack.so`, наша сборка **tun2socks-owner**: [tun2socks v2.6.0](https://github.com/xjasonlyu/tun2socks) (MIT) плюс **проверка владельца** каждого потока. Запускается через маленький JNI-помощник `fork()+exec()` (`app/src/main/cpp/tun_helper.cpp`), чтобы унаследовать файловый дескриптор TUN.
- **Проверка владельца** — на каждое новое TCP-соединение / UDP-сессию tun2socks спрашивает у приложения через абстрактный Unix-сокет, какому UID принадлежит поток (`ConnectivityManager.getConnectionOwnerUid`, Android 10+). Потоки приложений вне VPN, привязавшихся к `tun0`, отбрасываются ([issue #8](https://github.com/indelingDanil/MyAppVPN/issues/8)). См. `OwnerUidServer.kt` и `tun2socks-owner/owner/`.
- **Мост OpenFlux** — `libofbridge.so`, SOCKS5-сервер на Go (только CONNECT, loopback, авторизация), в который слинковано [ядро OpenFlux](https://github.com/p1neappleXpress/OpenFlux) **v0.17.1** (GPL-3.0). По умолчанию передаёт потоки на ваш выход по возобновляемому протоколу v2 (`openflux-bridge/PROTOCOL-v2.md`); `-transport mailru|cups|session` переключает на каналы ядра и ссылки `openflux://`. Режим `-diag` используется встроенной диагностикой подключения.
- **Node-выход** — `openflux-bridge/server-node/`, серверная часть протокола v2: чистый Node.js 20+, без npm-зависимостей. Инструкция по размещению — ветка [`openflux-exit`](https://github.com/indelingDanil/MyAppVPN/tree/openflux-exit) основного репозитория.

Нативные исполняемые файлы лежат в `app/src/main/jniLibs/<abi>/` под нейтральными именами библиотек: Android распаковывает их в `nativeLibraryDir`, откуда их разрешено запускать (`useLegacyPackaging = true`).

## Структура репозитория

```
app/
  src/main/java/com/myappvpn/
    data/            подписки, парсер, БД Room, DataStore
    di/              модули Hilt
    domain/          управление подключением, проверка серверов, диагностика OpenFlux
    service/         MyVpnService, менеджеры Xray / tun2socks / OpenFlux, OwnerUidServer
    ui/              экраны Compose, ViewModel, тема
    util/            сборка конфига Xray, DNS, пути к бинарникам, JNI-помощник
    worker/          фоновое обновление серверов
  src/main/cpp/      tun_helper.cpp (JNI fork+exec с сохранением TUN fd)
  src/main/jniLibs/  arm64-v8a/ и armeabi-v7a/: libcore.so, libnetstack.so, libofbridge.so
  src/main/assets/xray/  LICENSE и README Xray; сюда кладутся geoip.dat / geosite.dat (не в git)
openflux-bridge/     Go-мост OpenFlux (libofbridge.so), спецификация протокола, NOTICE
  third_party/sctp/  пропатченный pion/sctp из OpenFlux (MIT)
  server-node/       Node.js-выход OpenFlux v2 + тесты
tun2socks-owner/     сборка tun2socks с проверкой владельца (libnetstack.so), NOTICE
gradle/libs.versions.toml   версии всех библиотек и плагинов
```

## Сборка

### Что нужно

| Инструмент | Версия |
|---|---|
| Android Studio | свежая стабильная (AGP 9.1, Gradle wrapper 9.3) |
| JDK | 17 или новее — подойдёт JBR из комплекта Android Studio |
| Android SDK | platform 36 (`compileSdk = 36`) |
| Android NDK | **28.2.13676358** (зафиксирован в `app/build.gradle.kts`) |
| CMake | 3.22.1 (SDK Manager → SDK Tools) |
| Go | **1.26.6+** для `openflux-bridge`, 1.23+ для `tun2socks-owner` — только для пересборки нативных частей |
| Node.js | 20+ — только для сервера выхода и его тестов |

Нативные исполняемые файлы лежат в репозитории, поэтому для сборки самого приложения нужен только Android-тулчейн.

### 1. Клонировать и добавить geo-файлы

```bash
git clone https://github.com/indelingDanil/MyAppVPN-CodeBase.git
cd MyAppVPN-CodeBase
```

`geoip.dat` и `geosite.dat` не хранятся в git, но они обязательны: APK без них соберётся, а Xray не запустится (конфиг использует `geoip:private`). Возьмите их из архива [релиза Xray-core](https://github.com/XTLS/Xray-core/releases/tag/v26.2.6) и положите в `app/src/main/assets/xray/`. При первом запуске приложение копирует их в свою папку; конфиг Xray использует `geoip:private`.

### 2. Собрать APK

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

Или откройте папку в Android Studio и нажмите **Run**. Сборка делится по архитектурам, на выходе отдельный APK для каждой:

```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

Заметки:

- У debug-сборки application ID `com.myappvpn.debug`, она ставится рядом с релизной.
- У каждой архитектуры свой versionCode (`versionCode × 10 + 1` для armeabi-v7a, `+ 2` для arm64-v8a), поэтому 64-битный телефон всегда может обновиться на arm64-APK.
- `./gradlew assembleRelease` включает R8 и сжатие ресурсов. Конфига подписи в репозитории нет: подписывайте своим keystore и не коммитьте его (`*.jks` / `*.keystore` в `.gitignore`).
- Для тестовых сборок можно передать `-PofPresetUrl=… -PofPresetToken=…` — выход OpenFlux, который применится один раз на чистой установке. По умолчанию пусто. Реальные значения не коммитьте.

## Нативные бинарники

Все собираются под `arm64-v8a` и `armeabi-v7a`, минимальный API Android — 26 (minSdk приложения).

### `libnetstack.so` — tun2socks-owner

```bash
cd tun2socks-owner
./build-android.sh --test --install   # go test, сборка в out/<abi>/, копирование в app/src/main/jniLibs
```

### `libofbridge.so` — мост OpenFlux

```bash
cd openflux-bridge
./build-android.sh --install          # собирает ofbridge-android-arm64 / -arm и копирует как libofbridge.so
```

Оба скрипта берут самый новый NDK из `$ANDROID_HOME/ndk` (переопределяется через `ANDROID_NDK`) и учитывают `GO`, если задан. На Windows запускайте из Git Bash. Мосту нужен `-checklinkname=0`, скрипт его уже передаёт (зависимости ядра OpenFlux используют `go:linkname`).

### `libcore.so` — Xray-core v26.2.6

Xray-core без изменений, только переименован. Скрипта сборки для него в репозитории нет: возьмите Android-сборку из [релиза v26.2.6](https://github.com/XTLS/Xray-core/releases/tag/v26.2.6) или соберите `./main` из исходников Xray-core через clang из NDK под нужную архитектуру (см. раздел «One-line Compilation» в README Xray), затем скопируйте файл в `app/src/main/jniLibs/<abi>/libcore.so`.

## Тесты

```bash
(cd openflux-bridge && go test ./...)                   # мост: каналы, мультиплексор, протокол
(cd tun2socks-owner && go test ./...)                   # проверка владельца
(cd openflux-bridge/server-node && node --test)        # Node-выход + канал Mail.ru
```

Модуля unit-тестов у приложения пока нет. Релизы проверяются на реальных телефонах.

## Лицензия

MyAppVPN — свободное ПО под **[GNU GPL v3.0](LICENSE)**. GPL — потому что мост OpenFlux линкует ядро OpenFlux под GPL-3.0.

Сторонние компоненты и их уведомления:

| Компонент | Лицензия | Уведомление |
|---|---|---|
| [Ядро OpenFlux](https://github.com/p1neappleXpress/OpenFlux) v0.17.1 и его зависимости | GPL-3.0 (+ зависимости под MIT / BSD / Apache-2.0) | [`openflux-bridge/NOTICE`](openflux-bridge/NOTICE) |
| pion/sctp v1.11.3, копия с патчем OpenFlux | MIT | [`openflux-bridge/third_party/sctp/LICENSE`](openflux-bridge/third_party/sctp/LICENSE) |
| [tun2socks](https://github.com/xjasonlyu/tun2socks) v2.6.0 | MIT | [`tun2socks-owner/NOTICE`](tun2socks-owner/NOTICE), [`tun2socks-owner/LICENSE.tun2socks`](tun2socks-owner/LICENSE.tun2socks) |
| [Xray-core](https://github.com/XTLS/Xray-core) v26.2.6 | MPL-2.0 | [`app/src/main/assets/xray/LICENSE`](app/src/main/assets/xray/LICENSE) |

Android-библиотеки и их версии — в [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Участие

Ошибки, идеи и вопросы — в **основной репозиторий**: ошибки → [#4](https://github.com/indelingDanil/MyAppVPN/issues/4), идеи → [#5](https://github.com/indelingDanil/MyAppVPN/issues/5), пообщаться → [#6](https://github.com/indelingDanil/MyAppVPN/issues/6). Pull request'ы в этот репозиторий приветствуются: небольшие и с описанием, как вы их проверяли. Отправляя код, вы соглашаетесь, что он распространяется под GPL-3.0.

Никогда не коммитьте keystore, токены OpenFlux, адреса выходов и файлы `config.json`.

## Ссылки

- **Скачать приложение и история версий:** [indelingDanil/MyAppVPN](https://github.com/indelingDanil/MyAppVPN/releases)
- **Автособираемая подписка:** [indelingDanil/MyAppVPN-subs](https://github.com/indelingDanil/MyAppVPN-subs)
- **Настройка выхода OpenFlux:** [ветка `openflux-exit`](https://github.com/indelingDanil/MyAppVPN/tree/openflux-exit)
- **Новости:** [Telegram-канал](https://t.me/myappvpn_dprog)

## Дисклеймер

> ⚠️ Это **учебный** проект — сделан в образовательных и исследовательских целях. Автор **не несёт ответственности** за то, как вы используете код и приложение. Помните: в РФ VPN-сервисы для обхода блокировок ограничены законом и могут считаться незаконными. Используйте на свой страх и риск.
