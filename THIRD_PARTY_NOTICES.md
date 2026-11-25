# Сторонние компоненты

Проект ZapretByeDPI использует следующие открытые компоненты.
Их лицензии сохраняются за авторами.

## ByeDPI (ciadpi)

- Репозиторий: https://github.com/hufrea/byedpi
- Версия: 0.17.3
- Лицензия: MIT
- Исходники: `app/src/main/cpp/byedpi/`
- Назначение: движок обхода DPI, локальный SOCKS5-прокси.
- Сборка: ELF executable `libbyedpi.so`, запуск через `ProcessBuilder`.

## hev-socks5-tunnel

- Репозиторий: https://github.com/heiher/hev-socks5-tunnel
- Версия: 2.20.0
- Лицензия: MIT
- Исходники: `app/src/main/jni/hev-socks5-tunnel/`
- Назначение: TUN-to-SOCKS5 мост (tun2socks).
- Сборка: `libhev-socks5-tunnel.so` через ndk-build.

### Подмодули hev

| Компонент | Лицензия | Путь |
|---|---|---|
| hev-task-system | MIT | `jni/hev-socks5-tunnel/third-part/hev-task-system` |
| lwIP | BSD-3-Clause | `jni/hev-socks5-tunnel/third-part/lwip` |
| yaml (heiher fork) | MIT | `jni/hev-socks5-tunnel/third-part/yaml` |
| hev-socks5-core | MIT | `jni/hev-socks5-tunnel/src/core` |

Тексты лицензий находятся в соответствующих каталогах (`LICENSE`).

## zapret2-easy (источник пресетов, этап 3)

- Репозиторий: https://github.com/bol-van/zapret
- Назначение: исходные пресеты, списки доменов и логика ротации.
- Пресеты и Lua-скрипты не входят в APK в исходном виде: на этапе 3
  они будут адаптированы под реальные возможности ByeDPI.

## ByeByeDPI

- Репозиторий: https://github.com/romanvht/ByeByeDPI
- Лицензия: MIT
- Использованы идеи и форматы: список тестовых стратегий
  (`proxytest_strategies.list`), проверка сайтов через SOCKS5
  (`SiteCheckUtils`), аргументы `-An` / `-Ku` / `-a`.
- Код ByeByeDPI в APK не копировался: наш движок остаётся на ProcessBuilder
  и отдельном `libbyedpi.so`.

## TG WS Proxy Android (Telegram MTProto→WS)

- Репозиторий: https://github.com/amurcanov/tg-ws-proxy-android
- Оригинальный tg-ws-proxy: https://github.com/Flowseal/tg-ws-proxy (MIT)
- Android-форк: **GPLv3**
- Используется prebuilt `libtgwsproxy.so` для arm64-v8a и armeabi-v7a
  (`app/src/main/jniLibs/`), вызовы через JNA.
- Назначение: локальный MTProto-прокси 127.0.0.1:1443, который
  перенаправляет Telegram через WebSocket (CF/Worker).
- Исходники форка доступны по ссылке выше; при распространении APK
  с этой библиотекой нужно соблюдать GPLv3.

## JNA

- Репозиторий: https://github.com/java-native-access/jna
- Версия: 5.14.0 (AAR)
- Лицензия: Apache-2.0 / LGPL-2.1 (двойная)
- Назначение: вызов C-экспортов `libtgwsproxy.so` без написания JNI.

## QUIC Initial replay blob

- Файл: `app/src/main/assets/quic_initial.bin`
- Источник: `quic_initial_www_google_com.bin` из zapret2-easy (сборка
  Запрет2 Easy, MIT).
- Назначение: поддельный QUIC Initial для `-a 11 -l ...` в UDP-группе
  `PER_HOST_YT`, чтобы YouTube-видео шло через QUIC.
- Это только байты пакета, не код; лицензия MIT сохраняется.

## AndroidX / Material Components

- Лицензия: Apache License 2.0
- Назначение: UI-библиотеки Android.
