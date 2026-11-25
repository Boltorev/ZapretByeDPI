# Отличия vendored ByeDPI от upstream 0.17.3

## desync.c: send_fake()

На Android часть прошивок запрещает `vmsplice()`/`splice()` через seccomp.
В upstream fake-пакет в этом случае не отправлялся, и стратегии `--fake`
молча не работали. Патч при ошибке `vmsplice`/`splice` отправляет тот же
поддельный пакет обычным `send()`; TTL уже выставлен, логика desync не
меняется. Это не seqovl и не raw-seq, а переносимость fake на Android.

## extend.c: socket_mod()

В upstream при `--protect-path` ошибка `protect()` возвращает -1 и соединение
не создаётся. В ZapretByeDPI приложение исключено из VPN по UID, поэтому
protect — вторая линия защиты. Патч логирует ошибку и продолжает соединение,
чтобы сбой protect-сервера не отключал весь обход.

Всё остальное — upstream 0.17.3 без изменений.
