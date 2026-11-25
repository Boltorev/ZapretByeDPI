# ZapretByeDPI

Android-приложение для обхода DPI на базе [ByeDPI](https://github.com/hufrea/byedpi)
и [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

Цель проекта — повторить логику Windows-сборки **zapret2-easy**: пресеты AUTO/ALT,
автоматическая ротация стратегий, Per-App VPN и свои списки доменов. Работаем
без root и без эмуляторов, сборка — через GitHub Actions.

Сейчас готовы этапы 1–5: труба `VpnService → hev-socks5-tunnel → SOCKS5 → ciadpi`,
per-host стратегии с QUIC fake, AUTO-ротация, Per-App VPN, свои домены,
полный журнал, Telegram MTProto→WS и нижняя навигация из трёх разделов.

---

## 1. Что уже работает

| Возможность | Статус |
|---|---|
| VpnService перехватывает IPv4-трафик | ✅ |
| `ciadpi` (ByeDPI 0.17.3) как ELF executable через ProcessBuilder | ✅ |
| `hev-socks5-tunnel` 2.20.0 как нативная библиотека через JNI | ✅ |
| SOCKS5 `127.0.0.1:1080` (или свободный порт, если 1080 занят) | ✅ |
| Защита от петли: self-excluded UID + `--protect-path` для ciadpi | ✅ |
| Аргументы: per-host группы YouTube/Discord, fake TLS, QUIC fake, disorder | ✅ |
| Автопроба медиа+API, per-host выбор, QUIC Initial fake | ✅ |
| Диагностика: TUN-статистика, HTTPS-проба, скачивание логов | ✅ |
| Foreground-служба, уведомление, старт/стоп | ✅ |
| Счётчик трафика, состояние движка, журнал ciadpi | ✅ |
| GitHub Actions: сборка APK | ✅ |
| Пресеты, per-host стратегии, AUTO-ротация | ✅ этап 3 |
| Per-App VPN, редактор доменов, экран логов | ✅ этап 4 |
| Нижняя навигация: Главная / Настройки / Логи | ✅ этап 5 |
| Telegram MTProto→WS (libtgwsproxy, JNA) | ✅ этап 5 |
| Авто-перезапуск VPN после смены приложений/доменов | ✅ этап 5 |

---

## 2. Архитектура

```
приложения
   │
   ▼
TUN (VpnService, 10.10.10.10/32, 0.0.0.0/0, ::/0)
   │  fd
   ▼
libhev-socks5-tunnel.so  (tun2socks, JNI-мост libhevbridge.so)
   │  SOCKS5
   ▼
127.0.0.1:1080  →  libbyedpi.so (ciadpi, отдельный процесс)
   │  desync (split/disorder/fake/...)
   ▼
интернет
```

Почему нет петли:

1. Само приложение исключено из VPN через
   `VpnService.Builder.addDisallowedApplication(packageName)`. Дочерний
   процесс `ciadpi` наследует тот же UID, поэтому его сокеты тоже
   не попадают в TUN.
2. Дополнительно `ciadpi` запускается с `--protect-path`: на каждый новый
   сокет он отправляет fd в Unix-сокет приложения, а мы вызываем
   `VpnService.protect()`. Это вторая линия защиты.
3. `hev` не имеет публичного callback для protect, но его сокеты
   принадлежат исключённому UID — этого достаточно.

ByeDPI 0.17.3 — это SOCKS5-прокси, а не tun2socks. Именно поэтому hev
обязателен; напрямую подключить ciadpi к TUN нельзя.

---

## 3. Сборка через GitHub Actions

В проекте лежит [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml).

1. Залей проект в GitHub-репозиторий (ветка `main` или `master`).
2. Открой **Actions → Build APK → Run workflow** (или просто сделай push).
3. Workflow сам установит:
   - JDK 17;
   - Android SDK Platform 34;
   - Build-Tools 35.0.0 (требование AGP 8.8);
   - NDK 27.0.12077973;
   - CMake 3.22.1 (для альтернативной сборки ByeDPI).
4. Дождись зелёной галочки.
5. Скачай артефакт **ZapretByeDPI-debug** — внутри `app-debug.apk`.

Если репозиторий ещё не создан:

```bash
cd ZapretByeDPI
git init
git add .
git commit -m "Stage 2: ciadpi + hev tunnel"
git branch -M main
git remote add origin https://github.com/<user>/<repo>.git
git push -u origin main
```

Локально ставить Android Studio не нужно. Артефакт подписан debug-ключом
GitHub Actions, устанавливается на телефон как обычный APK.

> Каждый запуск GitHub Actions генерирует новый debug-ключ. Если очередная
> сборка не ставится поверх предыдущей («Приложение не установлено»),
> сначала удали старую версию ZapretByeDPI с телефона.

### Если Actions не видит `sdkmanager`

Workflow использует `android-actions/setup-android@v3` и `sdkmanager`
из PATH. Если репозиторий приватный и runner не может скачать SDK —
проверь лимиты/квоты GitHub.

### Локальная сборка (необязательно)

Нужны JDK 17+, Android SDK 34, NDK 27.0.12077973, Build-Tools 35.
`local.properties` создаётся Android Studio.

```bash
./gradlew assembleDebug
```

Сборка проверена: `clean assembleDebug` проходит, `lintDebug` — 0 ошибок.

Включены ABI-splits, поэтому на выходе в `app/build/outputs/apk/debug/`:

| Файл | Для чего |
|---|---|
| `app-arm64-v8a-debug.apk` | современные телефоны (почти все) |
| `app-armeabi-v7a-debug.apk` | старые 32-битные ARM-телефоны |
| `app-x86-debug.apk` | старые 32-битные эмуляторы |
| `app-x86_64-debug.apk` | современные эмуляторы |
| `app-universal-debug.apk` | все ABI в одном файле (больше размером) |

`extractNativeLibs=true` (через `jniLibs { useLegacyPackaging true }`) обязателен:
`libbyedpi.so` — исполняемый файл, а не библиотека.

---

## 4. Как проверить этап 2 на телефоне

1. Установи `app-debug.apk`.
2. Запусти приложение, нажми большую кнопку.
3. Разреши уведомления (Android 13+) и VPN.
4. Дождись состояния **РАБОТАЕТ**. В карточке состояния должно быть:
   - VPN — «Работает»;
   - TUN — `tun0`;
   - Движок — `ciadpi + hev`;
   - SOCKS5 — `127.0.0.1:1080`;
   - Пресет — `PER_HOST_YT` / `PER_HOST_YT_TLSREC` / `SPLIT1` / `TLSREC` /
     `FAKE` / `MULTI...` (что выбрала автопроба);
   - Трафик — растёт при открытии сайтов.
5. Открой YouTube, включи видео. Открой Discord в браузере.
6. Выключи VPN кнопкой или из уведомления — трафик должен вернуться
   напрямую, состояние «Остановлен».

> Первый запуск может занять 5–20 секунд: приложение проверяет стратегии
> HTTPS-пробой через ciadpi и выбирает ту, которая проходит TLS.

### Если не работает — скачай логи

В карточке «ЖУРНАЛ» есть кнопка **«Скачать»**. Она открывает системный
диалог сохранения и пишет текстовый отчёт:

- модель/версия Android и ABI;
- состояние VPN и выбранная стратегия;
- `HevBridge` + статистика TUN (`tx/rx` пакеты и байты);
- список нативных библиотек;
- полный `AppLog`;
- хвосты `ciadpi.log` и `hev.log`.

Пришли этот файл — по нему сразу видно, на каком слое затык:
`tx=0` — трафик не доходит до TUN; `tx>0, rx=0` — hev/ciadpi не отвечает;
`tx>0, rx>0`, но сайты не открываются — DNS или стратегия.

### Диагностика через adb

```bash
# Процесс ciadpi жив?
adb shell ps -A | grep ciadpi

# Служба и VPN
adb shell dumpsys activity services com.zapret.byedpi
adb shell dumpsys connectivity | grep -i vpn

# Логи приложения
adb logcat -s Zapret/VpnService Zapret/ciadpi Zapret/hev ZapretHev

# Файлы приложения (debug-сборка)
adb shell run-as com.zapret.byedpi ls -l files
adb shell run-as com.zapret.byedpi cat files/hev.log
```

Если состояние **ОШИБКА**, в журнале на экране будет причина:
не запустился ciadpi, не открылся SOCKS-порт, hev упал, нет разрешения VPN.

---

## 5. Что важно знать про эту сборку

### ByeDPI собирается NDK clang напрямую

`CMakeLists.txt` для ByeDPI лежит в
[app/src/main/cpp/CMakeLists.txt](app/src/main/cpp/CMakeLists.txt), но
Gradle-задача `buildByeDpiExecutable` вызывает NDK clang напрямую. Причина:
в среде разработки CMake/Ninja зависает на compiler check (особенность
песочницы), а прямой clang даёт тот же ELF и полностью воспроизводим в
GitHub Actions. Флаги и компилятор те же, что у CMake.

### `libbyedpi.so` — это executable

AGP собирает `libbyedpi.so` как ELF executable и кладёт в `jniLibs`.
Для запуска через `ProcessBuilder` критично:

```groovy
packaging {
    jniLibs {
        useLegacyPackaging true
    }
}
```

В APK попадает `android:extractNativeLibs="true"`, файл извлекается в
`nativeLibraryDir` с правом execute. Не меняйте `useLegacyPackaging`,
иначе `ProcessBuilder` получит `EACCES`.

### protect-сервер

[ProtectServer.kt](app/src/main/java/com/zapret/byedpi/service/ProtectServer.kt)
биндит Unix-сокет в **filesystem namespace** (не abstract — стандартный
`LocalServerSocket(String)` на Android использует abstract и не подходит
для `connect()` из ciadpi). При получении fd вызывается
`VpnService.protect()` через `ParcelFileDescriptor.dup(fd).fd`.

После старта protect-сервер проходит self-test: клиентский LocalSocket
отправляет тестовый fd, сервер должен ответить. Если тест не прошёл,
`--protect-path` не передаётся вовсе — иначе каждый новый сокет ciadpi
ждал бы таймаут protect и соединения были бы медленными.

Если protect-сервер не поднялся, приложение не падает: ciadpi запускается
без `--protect-path`, защита остаётся за счёт исключения UID. Кроме того,
в vendored ByeDPI есть маленький патч: ошибка `protect()` больше не рвёт
соединение (см. [PATCHES.md](app/src/main/cpp/byedpi/PATCHES.md)). Это не
патч desync-примитивов, а страховка от сбоя protect-сервера.

### DNS и IPv6

DNS: `77.88.8.8` (Яндекс, обычно доступен из РФ), `8.8.8.8`, `1.1.1.1`.
Запросы идут UDP через SOCKS5 и ciadpi.

IPv6 на этапе 2 **отключён** (`VpnConfig.ENABLE_IPV6 = false`): на части
мобильных сетей IPv6-маршрут в VPN превращается в чёрную дыру, и приложения
висят на подключении. Если на твоём операторе IPv6 работает — можно
включить обратно:

```kotlin
// core/VpnConfig.kt
const val ENABLE_IPV6 = true
```

### Автопроба и per-host стратегии

Перед созданием TUN приложение запускает ciadpi и делает два реальных
HTTPS-запроса через SOCKS5:

- `redirector.googlevideo.com` — медиа-цепочка YouTube;
- `youtubei.googleapis.com` — API приложения.

`PER_HOST_YT` — основной режим для этого оператора. Внутри одного ciadpi
собраны группы по SNI:

| SNI | Стратегия |
|---|---|
| `*.googlevideo.com` | `-s 1` (split по первому байту) |
| `youtubei.googleapis.com` | `-o 3+s` |
| `*.youtube.com`, `ytimg`, `ggpht`, `gstatic` | `-o 3+s` |
| Discord | `-s 1` |
| остальное | `-s 1+s -d 1+s` |

Перед TCP-группами стоит UDP-only группа `-K u -a 11 -l quic_initial.bin`:
11 поддельных QUIC Initial перед настоящим. Именно это лечит «страница
грузится, видео висит»: страницы идут по TCP через OOB3, а медиа — по QUIC
с fake Initial. Если QUIC не сработал, приложение откатывается на TCP
`googlevideo`, где действует `-s 1`.

Остальные кандидаты (`PER_HOST_YT_TLSREC`, `SPLIT1`, `TLSREC`, `FAKE`,
`FAKE_MD5`, `MULTIDISORDER`, `DISORDER1`, `OOB3`) — резерв, если per-host
не проходит пробу.

Во всех группах стоит `-V 443`: desync не трогает Private DNS (853),
mtalk (5228) и DNS.

### AUTO-ротация

После перехода в RUNNING запускается канарейка:

- раз в **10 секунд** проверяются `youtubei.googleapis.com` (`yt`) и
  `discord.com` (`dc`) реальным HTTPS-запросом через локальный SOCKS5;
- успех сбрасывает счётчик;
- **4 отказа** в окне 45 секунд → берётся следующая стратегия из
  `EngineConfig.strategies()`, ciadpi перезапускается, TUN/hev не трогаются;
- индекс и счётчик хранятся в `autostate.yt` / `autostate.dc`;
- счётчик ротаций отображается в UI.

Идея и список стратегий подсмотрены у **ByeByeDPI**
(`proxytest_strategies.list`, `SiteCheckUtils`, `-An`/`-Ku`/`-a`);
наш вариант остаётся на ProcessBuilder + отдельном ciadpi, без JNI-движка.

### Этап 4: Per-App, домены, журнал

- **Приложения** — экран `AppListActivity`: режимы «Все», «Только выбранные»,
  «Все, кроме выбранных». Список берётся из `PackageManager`, само приложение
  всегда исключено из VPN.
- **Домены** — экран `DomainListActivity`, файл `files/user_hosts.txt`.
  Домены добавляются отдельной группой ciadpi `-H ":a b c"` со стратегией
  `-s 1` перед общим fallback.
- **Журнал** — полноэкранный `LogActivity`: поиск, фильтр по уровню,
  копирование, очистка и экспорт диагностического отчёта.
- Правила Per-App и список доменов применяются при следующем включении VPN.

### 0.6.2: публикационные сборки под все ABI

- **Добавлена ABI `x86`** (32-битные эмуляторы): `ciadpi` собирается через
  NDK clang для `i686-linux-android26`, `hev-socks5-tunnel` и JNI-мост —
  через ndk-build. Полный список: `arm64-v8a`, `armeabi-v7a`, `x86`,
  `x86_64`.
- **ABI-splits**: `assembleDebug` выдаёт отдельный APK на каждую ABI плюс
  `app-universal-debug.apk` со всеми четырьмя.
- Отдельного ABI `armeabi` (ARMv5 soft-float) в NDK 27 нет — он удалён
  с NDK r17, 32-битные ARM-устройства обслуживает `armeabi-v7a`.
- Telegram-прокси (`libtgwsproxy.so`) по-прежнему только для `arm64-v8a`
  и `armeabi-v7a`: на x86/x86_64 модуль Telegram недоступен, VPN работает.
  Сообщение об этом теперь содержит фактическую ABI устройства.

### 0.6.1: порт SOCKS5 и устойчивый запуск туннеля

Патч поверх 0.6.0: без правок логики стратегий, только старт/остановка движка.

- **Свободный порт вместо жёсткого 1080** (`core/PortPicker.kt`). Порт
  проверяется bind'ом перед стартом; 1080 используется, если свободен,
  иначе берётся эфемерный. Выбранный порт фиксируется на сессию в
  `EngineConfig.SOCKS_PORT` и одинаково попадает в аргументы `-p` ciadpi,
  в конфиг hev и во все TLS-пробы.
- **Уборка зависших ciadpi** (`service/StaleProcesses.kt`): перед стартом
  и при ротации ищутся процессы `libbyedpi.so` и убиваются SIGKILL.
  Раньше такой процесс держал 1080, и каждый новый ciadpi падал с
  `code=255`.
- **Диагностика старта ciadpi**: последние 40 строк вывода (stdout+stderr)
  хранятся в кольцевом буфере. При неудаче в журнал пишется код возврата,
  расшифровка (`255` — порт/аргументы), хвост вывода и занят ли порт.
- **`establish() returned null`**: перед созданием TUN перечисляются
  активные VPN-транспорты, делается **две** попытки `establish()` с паузой
  700 мс, при отказе — конкретный текст: нет разрешения VPN / активен
  другой VPN / перезагрузите телефон. `Builder` создаётся заново на каждую
  попытку.
- `VpnState` при неудаче уходит в `ERROR`, а не залипает в `RUNNING`.

### 0.6.0: устойчивость сети, DNS и туннеля

- **Собственный DNS-резолвер** (`DnsResolver`): параллельная UDP-гонка
  1.1.1.1 / 8.8.8.8 / 9.9.9.9 / 77.88.8.8, system fallback и DoH.
  Кэш положительных и короткий отрицательный.
- **Пробы стратегий идут по IP**: резолвер приложения получает адрес,
  TLS-проба подключается через SOCKS5 к IP, SNI остаётся настоящим.
  `not resolved` от ciadpi больше не считается провалом стратегии.
- **Классификация отказов**: DNS / ROUTE / TIMEOUT / RESET / TLS / IO.
  AutoRotator не переключает split/disorder при DNS/ROUTE.
- **Preflight сети**: перед перебором проверяются сеть, DNS и маршрут;
  при их отсутствии перебор не запускается. Есть один быстрый retry.
- **NetworkCallback**: смена Wi-Fi ↔ мобильные данные пересоздаёт ciadpi,
  DNS-кэш сбрасывается.
- **hev**: MTU 1400, connect-timeout 8 с, TCP timeout 90 с, UDP 30 с,
  max sessions 512 — меньше `io timeout` и зависших сессий.
- **DNS VPN**: 1.1.1.1, 8.8.8.8, 9.9.9.9, 77.88.8.8.
- **Telegram**: размер пула 8, статистика `stats:` и CF-переключатель;
  `cf>0` означает, что Cloudflare-канал реально используется.

### 0.5.4: IPv4 и диагностика Telegram

- **`-X` / no-ipv6 для ciadpi**, когда туннель IPv4-only. На Samsung с IPv6
  ciadpi уходил в AAAA (`2001:4860:...`), из-за чего YouTube мог резать
  качество до 480p. Теперь upstream принудительно IPv4.
- **Telegram WS**: в разделе Telegram появился переключатель
  **Cloudflare fallback** и периодический `stats:` в журнале. Если пинг
  высокий — выключить fallback и перезапустить прокси; если Telegram
  перестал подключаться — включить обратно.
- **0.5.4** включает все исправления 0.5.3: Per-App ONLY, X-автоперебор,
  выравнивание нижней панели.

### 0.5.3: исправления после теста

- **Per-App ONLY** больше не падает с `addAllowedApplication already called`:
  в ONLY-режиме список только `addAllowedApplication`, без смешивания
  с `addDisallowedApplication`. VPN снова поднимается.
- **X / Twitter** добавлен в per-host правила. `-s 1` поднял соединение,
  но ответа не было; теперь для X работает автоперебор вариантов
  `-o 3+s` → `-r 3+s` → `-d 1` → fake → fallback. Канарейка
  `api.x.com` проверяется отдельно (порог 2), состояние — `autostate.x`.
- **Нижняя полоса** выровнена: у «Логов» убран внешний padding, у всех
  разделов одинаковая ширина/высота, выбранный пункт ставится до
  `setContentView`, горизонтальный сдвиг иконок отключён.
- **Журнал на Главной** — 6 строк.

### Telegram WS, четыре раздела, авто-перезапуск

- **Нижняя навигация** — четыре постоянных раздела: Главная, Telegram,
  Настройки, Логи. Полоска не скрывается при прокрутке: контент скроллится
  выше неё.
- **Telegram WS-прокси** — отдельный раздел: локальный MTProto 127.0.0.1:1443
  через WebSocket/Cloudflare. Кнопки «Запустить/Остановить», «Применить
  в Telegram», «Копировать ссылку». Реализация — Rust `libtgwsproxy.so` из
  `amurcanov/tg-ws-proxy-android`, вызовы через JNA.
  Исходник `tg-ws-proxy-1.12.0` — Python; на Android напрямую он не
  запускается, поэтому взят Android-порт того же протокола на Rust.
- **Журнал на главной** сокращён до 6 строк: полный журнал — в разделе «Логи».
- **Авто-перезапуск VPN** — после сохранения Per-App или списка доменов
  приложение само останавливает и снова поднимает туннель, чтобы правила
  применились. Activity не закрывается, пока перезапуск не завершится.
- **Ограничения**: Telegram-модуль использует prebuilt GPLv3-библиотеку
  (см. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)); библиотеки
  нет, на эмуляторе модуль покажет ошибку. Telegram нужно один раз
  подтвердить через `tg://proxy`.

Чек-листы тестирования: [docs/STAGE3-TEST.md](docs/STAGE3-TEST.md),
[docs/STAGE4-TEST.md](docs/STAGE4-TEST.md),
[docs/STAGE5-TEST.md](docs/STAGE5-TEST.md),
[docs/STAGE6-TEST.md](docs/STAGE6-TEST.md).

---

## 6. Структура проекта

```
ZapretByeDPI/
├── .github/workflows/build-apk.yml   сборка APK в Actions
├── app/
│   ├── build.gradle                  NDK, CMake/Clang задача, упаковка
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── byedpi/               ByeDPI 0.17.3 (vendored)
│       │   └── CMakeLists.txt        альтернативная сборка ByeDPI
│       ├── jni/
│       │   ├── Android.mk            hev + JNI-мост
│       │   ├── Application.mk
│       │   ├── hev-socks5-tunnel/    hev 2.20.0 + submodules (vendored)
│       │   └── hevbridge/            hev_bridge.c — JNI
│       └── java/com/zapret/byedpi/
│           ├── MainActivity.kt
│           ├── ZapretApp.kt
│           ├── core/                 VpnConfig, EngineConfig, Prefs, AppLog, VpnState
│           ├── nativebridge/         HevBridge.kt
│           ├── service/              ZapretVpnService, ByeDpiProcess,
│           │                         Tun2Socks, ProtectServer, VpnController
│           └── util/                 NotificationHelper
├── docs/
│   ├── STAGE2-NOTES.md               почему именно так, ограничения ByeDPI
│   └── STAGE2-TEST.md                чек-лист тестирования
├── README.md
├── THIRD_PARTY_NOTICES.md
└── LICENSE (MIT)
```

---

## 7. Частые проблемы

| Симптом | Причина / решение |
|---|---|
| `ciadpi завершился с кодом 1` | Неверные аргументы или не найден executable. Смотрите лог ciadpi |
| `ciadpi завершился с кодом 255` | Занят SOCKS-порт или неверный аргумент. С 0.6.1 порт выбирается свободный, зависшие ciadpi убиваются; в журнале есть хвост вывода |
| `establish() вернул null` | Другой VPN / always-on / отозванное разрешение. С 0.6.1 — две попытки и конкретный текст причины |
| `Порт 1080 занят — использую N` | Это не ошибка: приложение взяло свободный порт, всё остальное переключено на него |
| `libbyedpi.so не исполняется` | Проверьте `useLegacyPackaging true` и `extractNativeLibs` |
| `ciadpi не открыл SOCKS-порт` | Процесс упал на старте; смотрите stderr в журнале |
| `hev не запустился` | Смотрите `files/hev.log`; чаще всего кривой YAML или ABI |
| `hev-socks5-tunnel не загрузился` | APK собран без нужной ABI или libs не извлеклись |
| YouTube не открывается, трафик растёт | Стратегия не подходит провайдеру — этап 3 (пресеты) |
| Сайты не открываются, трафик 0 | DNS/UDP или hev; скачайте логи, проверьте `hev.log` |
| Устройство без VPN-разрешения | Другой активный VPN / always-on; отключите |
| Android 14: `startForeground() не удался` | Не удаляйте `foregroundServiceType="specialUse"` и разрешение `FOREGROUND_SERVICE_SPECIAL_USE` |

---

## 8. Лицензия

MIT. См. [LICENSE](LICENSE) и [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
ByeDPI и hev-socks5-tunnel распространяются по MIT; их исходники включены
в проект с сохранением лицензий.
