# Заметки к этапу 2 и 3

Этот файл — не код, а техническая сверка ожиданий. Прочитайте его до того,
как мы начнём интеграцию ByeDPI: здесь есть два важных несовпадения,
которые лучше обсудить сейчас, а не после трёх дней сборки NDK.

---

## 0. Статус: этап 2 реализован

Версия APK `0.2.0-stage2`:

- ByeDPI 0.17.3 собран как ELF executable `libbyedpi.so` и запускается
  через `ProcessBuilder` (вариант A).
- hev-socks5-tunnel 2.20.0 собран из исходников через ndk-build как
  `libhev-socks5-tunnel.so`; JNI-мост — `libhevbridge.so`.
- `VpnService` перехватывает IPv4 (IPv6 по умолчанию отключён) и передаёт TUN fd в hev.
- Петля исключена: собственный UID исключён из VPN, плюс для ciadpi
  работает `--protect-path` (Unix-сокет + `VpnService.protect`).
- Простые аргументы: `-s 1+s -d 1+s --auto=torst` + fallback-группа.
- GitHub Actions собирает debug APK.

Пресеты, Kotlin-ротатор, Per-App VPN и домены — следующие этапы.

---

## 1. Как ByeDPI встраивается в Android

ByeDPI (`ciadpi`) — это **локальный SOCKS5/HTTP-прокси**. Он принимает
TCP-соединение от клиента, открывает своё соединение к серверу и применяет
desync-приёмы (split/disorder/fake/tlsrec и т.п.). Он **не умеет** читать
IP-пакеты из TUN-интерфейса.

`VpnService.Builder.establish()` даёт файловый дескриптор TUN, в который
попадают сырые IP-пакеты. Чтобы соединения приложений попали в ByeDPI,
нужен tun2socks-слой:

```
приложение → TUN (VpnService)
           → hev-socks5-tunnel (tun2socks)
           → SOCKS5 127.0.0.1:1080
           → ByeDPI (ciadpi)
           → интернет
```

Именно так устроены открытые Android-обёртки ByeDPI:
[ByeByeDPI](https://github.com/romanvht/ByeByeDPI) и
[dovecoteescapee/ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid).
Они используют `hev-socks5-tunnel` (MIT) + `byedpi`.

### Что это значит для этапа 2

Нужно собрать под NDK три вещи:

1. `ciadpi`/`libbyedpi.so` — сам ByeDPI, слушает `127.0.0.1:1080`.
2. `hev-socks5-tunnel` — TUN-to-SOCKS5, получает fd TUN и адрес прокси.
3. JNI-мост — передать fd TUN в hev, запустить/остановить оба процесса,
   получить логи.

Варианты интеграции ByeDPI:

- **A. Собрать исполняемый файл** `libbyedpi.so` (имя .so, но ELF executable)
  и запускать через `ProcessBuilder`. Так делает ByeDPIAndroid: проще,
  не нужно переименовывать `main()`, но нужно следить за путями и правами
  на исполнение в `nativeLibraryDir`.
- **B. Собрать разделяемую библиотеку** с JNI-функцией
  `Java_..._run(env, clazz, argc, argv)`, внутри вызывать `main()`.
  Чище для управления, но `main()` использует сигналы/демонизацию —
  придётся вырезать `daemon`, `pidfile`, обработчики сигналов.

Для первой итерации этапа 2 я предлагаю **A**: меньше правок C-кода,
быстрее получить рабочий туннель. Потом, если понадобится тонкое
управление, перейдём на B.

### Защита сокетов от петли

ByeDPI открывает исходящие соединения к серверам. Эти сокеты не должны
снова попасть в TUN. В классической схеме:

- приложение-владелец VPN исключено через `addDisallowedApplication`;
- для ciadpi дополнительно работает `--protect-path` (SCM_RIGHTS →
  `VpnService.protect`).

У `hev-socks5-tunnel` 2.20 **нет** публичного protect-callback. Его сокеты
защищены тем, что весь UID приложения исключён из VPN; дочерний ciadpi
наследует тот же UID. Это тот же практический результат, что protect.
Патчить hev ради callback не нужно.

Важно: protect-сервер проходит self-test и при неудаче не передаётся
ciadpi вовсе, иначе каждый сокет ждал бы таймаут protect.

---

## 2. Реальные опции ByeDPI 0.17.3

Из `README.md` и `main.c` присланного архива:

```
-i/--ip, -p/--port, -c/--max-conn, -b/--buf-size, -I/--conn-ip,
-N/--no-domain, -U/--no-udp, -F/--tfo, -g/--def-ttl,
-A/--auto {torst,redirect,ssl_err,none}, -L/--auto-mode 0-3,
-T/--timeout, -u/--cache-ttl, -y/--cache-dump,
-K/--proto t,h,u,i, -H/--hosts, -j/--ipset, -V/--pf, -R/--round,
-s/--split <pos>, -d/--disorder <pos>, -o/--oob <pos>, -q/--disoob <pos>,
-f/--fake <pos>, -t/--ttl, -S/--md5sig, -l/--fake-data,
-O/--fake-offset, -n/--fake-sni, -Q/--fake-tls-mod {rand,orig,msize=N},
-e/--oob-data, -M/--mod-http, -r/--tlsrec, -m/--tlsminor,
-a/--udp-fake, -Y/--drop-sack, -x/--debug
```

Позиции `split/disorder/oob/fake` поддерживают формат
`offset[:repeats:skip][+s|+h|+n][+e|+m]`, например `1+s`, `1+s+2`,
`-1`, `midsld` **не** поддерживается.

### Чего в 0.17.3 нет

| Из вашей таблицы | В ByeDPI 0.17.3 |
|---|---|
| `--seqovl=681` (multisplit с перекрытием sequence) | ❌ нет |
| `--hostfake=X` | ❌ нет |
| `--fakesplit=2 --fakepattern=0x00` | ❌ нет |
| `--midsld` | ❌ нет |
| `--range-out=-s34228`, `--range-in=-s5556` | ❌ нет (есть только `--round` по номеру запроса) |
| `--tlsmod=Y` | ⚠️ есть `--fake-tls-mod` (`rand`, `orig`, `msize=N`) |
| `--fake=X.bin --tlsmod=Y` | ⚠️ есть `--fake <pos> --fake-data file --fake-tls-mod ...` |
| `--split=1 --seqovl=681` | ⚠️ есть `--split 1`, но без seqovl |
| `--disorder=1 --midsld` | ⚠️ есть `--disorder 1` / `--disorder 1+s` |
| `--filter-tcp=443` | ❌ в CLI нет; фильтр задаётся `--pf 443` / `--hosts` / `--proto` |
| `--hostlist=file.txt` | ⚠️ есть `--hosts file.txt` (без `--hostlist`) |

Причина: `seqovl`, `midsld`, `hostfakesplit`, `fakedsplit`, `range-*` —
это примитивы **zapret2/winws**, которые работают с сырыми пакетами через
WinDivert/netfilter. ByeDPI — пользовательский SOCKS-прокси: он не формирует
сырые IP-пакеты и не может управлять TCP sequence numbers произвольно.

### Вывод

Дословная адаптация AUTO.txt из zapret2-easy под ByeDPI **невозможна**.
Реалистичные варианты:

1. **Семантическая адаптация (рекомендую для первой версии).**
   Каждой стратегии zapret подобрать ближайший аналог из реальных опций
   ByeDPI:
   - fake + multisplit → `--fake -1 --fake-data=... --ttl=... --split 1+s`
   - hostfakesplit → `--fake -1 --fake-sni=www.google.com --ttl=...`
   - fakedsplit → `--split 1+s --disorder 1+s`
   - multidisorder → `--disorder 1+s` (плюс `--split`)
   Это даст рабочий, но более слабый набор. Проверять на вашем провайдере
   придётся заново.
2. **Патч C-кода ByeDPI.** Добавить недостающие приёмы. Для `seqovl`
   потребуются сырые сокеты (`AF_PACKET`/`IP_HDRINCL`), а на Android без
   root это невозможно; для остальных — можно попробовать на уровне
   stream. Это большой и рискованный объём работы.
3. **Другой движок.** `nfqws` из zapret требует root/netfilter, не
   дружит с VpnService. Готового MIT-движка с полным набором
   zapret-примитивов под VpnService нет.

Мой план: на этапе 2 поднять трубу с простыми аргументами
(`--split 1+s --disorder 1+s --auto=torst`), убедиться, что YouTube идёт
через прокси; на этапе 3 сделать **свой** ротатор вокруг тех стратегий,
которые реально поддерживаются. Перед этим пришлите, пожалуйста, чем
именно вы тестировали Windows-сборку и какие ALT-пресеты у вас реально
пробивают блокировку — это поможет выбрать аналоги.

---

## 3. Как делать ротацию (circular) на Kotlin

В zapret `circular` живёт внутри Lua-движка и видит каждый пакет:
входящий RST, ретрансмиссии, HTTP redirect. У ByeDPI такого хука снаружи
нет. Варианты детекта:

### A. Встроенный `--auto` ByeDPI

У ByeDPI есть свой автомат: группы аргументов разделяются `--auto=torst`,
`--auto=ssl_err`, `--timeout`. При таймауте/RST он переключается на
следующую группу. Это ближайший аналог circular, но:

- нет ключа хоста (`yt`/`dc`) и NLD=2;
- нет счётчика «4 неудачи, потом ротация»;
- нет отдельного состояния для YouTube и Discord;
- не видно, какая группа сейчас активна, без парсинга логов.

### B. Свой детектор + перезапуск ByeDPI

Kotlin-ротатор:

- держит `autostate.yt` и `autostate.dc` — по хосту 2-го уровня;
- считает неудачи (таймаут, RST, отсутствие ответа);
- при `fails=4` переключает стратегию и перезапускает ByeDPI с новыми
  аргументами;
- пишет в журнал `circular: rotate strategy to N`.

Проблема: как узнать о неудаче? Варианты:
- патчить ByeDPI, чтобы он писал события в pipe/logcat (нужен C-код);
- держать «канарейку»: периодически открывать TLS-соединение к
  youtube/discord через SOCKS и считать таймауты;
- анализировать `--debug 2` лог ByeDPI.

Для первой версии этапа 3 я предлагаю **канарейку + перезапуск**:
это работает без правок движка, хотя реагирует чуть медленнее и не видит
каждое соединение. Потом можно заменить на нативный детектор.

### C. Патч ByeDPI

Самый точный вариант, но требует C-кода и понимания `proxy.c`/`desync.c`.
Отложим до того, как труба заработает.

---

## 4. Что понадобится на этапе 2 (чек-лист)

- [ ] NDK 26+ и CMake в Android Studio.
- [ ] Скачать `hev-socks5-tunnel` (MIT) и зафиксировать версию.
- [ ] Собрать `ciadpi` под `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- [ ] Собрать `hev-socks5-tunnel` под те же ABI.
- [ ] Проверить, что `libbyedpi.so` исполняется из `nativeLibraryDir`
      (Android 10+ ограничивает exec из writable-директорий, но
      `nativeLibraryDir` — read-only и разрешён).
- [ ] JNI: `hev_init(tun_fd, socks_port)`, `hev_start()`, `hev_stop()`.
- [ ] protect-сервер: self-test + `--protect-path` для ciadpi; hev — через
      исключение UID.
- [ ] Включить `CAPTURE_ALL_TRAFFIC = true`, добавить DNS.
- [ ] Проверить: YouTube открывается, видео играет.
- [ ] Проверить: при выключенном VPN сайт не открывается (если провайдер
      блокирует), при включённом — открывается.

---

## 5. Источники

- ByeDPI: https://github.com/hufrea/byedpi
- ByeByeDPI (Android): https://github.com/romanvht/ByeByeDPI
- ByeDPIAndroid: https://github.com/dovecoteescapee/ByeDPIAndroid
- hev-socks5-tunnel: https://github.com/heiher/hev-socks5-tunnel
- zapret2-easy (ваша Windows-сборка): локальная папка `zapret2-easy`
