# Hardening captcha-вебсервера на Ubuntu 24

> **Кому это:** другому Claude, который уже сидит в shell на хосте,
> где работает Paper + AuthSecurity. Это **не** учебник, это чек-лист
> с готовыми командами. Шаги идут в порядке выполнения.

Базовое развёртывание (Cloudflare site-key, nginx, certbot) описано в
[`CAPTCHA-DEPLOY.ru.md`](CAPTCHA-DEPLOY.ru.md). Этот файл — что добавить
сверху для прод-беты.

---

## 0. Контекст и допущения

- ОС: Ubuntu 24.04 LTS.
- Paper-сервер запущен под отдельным пользователем (например, `mc`)
  через systemd, screen или tmux. Если нет — сделай systemd-юнит,
  пример в §4.
- Домен `captcha.<example>.tld` уже указывает на этот хост, TLS получен
  certbot'ом, nginx проксирует на `127.0.0.1:25590`.
- В `config.yml` стоит `captcha.web-bind: "127.0.0.1"`. Если стоит
  `0.0.0.0` — поменяй и перезапусти Paper (или закрой 25590 в ufw).
- Лог Paper лежит в `/home/mc/server/logs/latest.log` (поправь путь).

---

## 1. Файрвол (ufw)

Открыть только то, что реально нужно публично:

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp                 # nginx (HTTP -> HTTPS redirect)
sudo ufw allow 443/tcp                # nginx (TLS)
sudo ufw allow 25565/tcp              # Minecraft. Поменяй, если порт другой.
sudo ufw enable
sudo ufw status numbered
```

Порт **25590 наружу не открываем** — его слушает только nginx через
`127.0.0.1`. Проверь:

```bash
sudo ss -tlnp | grep 25590
# Должно быть: 127.0.0.1:25590, а не 0.0.0.0:25590
```

---

## 2. nginx: security headers + rate-limit

В `/etc/nginx/sites-available/<project>-captcha` блок `server { ... 443 ... }`
дополнить так:

```nginx
# /etc/nginx/nginx.conf, в http { } — один раз на весь хост:
limit_req_zone $binary_remote_addr zone=captcha_zone:10m rate=10r/s;

server {
    listen 443 ssl http2;
    server_name captcha.example.tld;

    # ssl_certificate / ssl_certificate_key — уже подставит certbot

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options    "nosniff"               always;
    add_header X-Frame-Options           "DENY"                  always;
    add_header Referrer-Policy           "no-referrer"           always;
    # Turnstile грузится с challenges.cloudflare.com:
    add_header Content-Security-Policy   "default-src 'self'; script-src 'self' https://challenges.cloudflare.com 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-src https://challenges.cloudflare.com; connect-src 'self'" always;

    client_max_body_size 8k;          # /verify шлёт ~1 KB JSON
    client_body_timeout  10s;
    keepalive_timeout    15s;

    location = / {
        return 404;                   # нет смысла светить health-check наружу
    }

    location /verify {
        limit_req zone=captcha_zone burst=20 nodelay;
        proxy_pass         http://127.0.0.1:25590;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }

    location ~ ^/c/[A-Za-z0-9_-]{1,128}$ {
        limit_req zone=captcha_zone burst=20 nodelay;
        proxy_pass         http://127.0.0.1:25590;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }

    location / {
        return 404;                   # любые другие пути — мимо
    }
}
```

Применить:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

> Плагин уже видит реальный IP игрока: Javalin читает `X-Forwarded-For`
> через `ctx.ip()`, потому что в request-логе нужен IP клиента, а не
> nginx'а. **Если** в логах появляется `127.0.0.1` вместо настоящих
> IP — значит, Javalin не доверяет proxy. В этом случае пинай меня
> добавить `cfg.jetty.... forwardedHeader = "X-Forwarded-For"` в
> `CaptchaWebServer.start()`. Сейчас это **TODO на сервере** — проверь
> один раз в логе и скажи разработчику.

---

## 3. fail2ban: ловим перебор по /c/<token> и /verify

Плагин теперь пишет в `latest.log` строку формата:

```
[12:34:56 INFO]: captcha-web 1.2.3.4 "GET /c/abc..." 410 12ms
[12:34:57 INFO]: captcha-web 1.2.3.4 "POST /verify" 400 5ms
```

Любой статус ≥ 400 — повод срабатывания.

### 3.1. Поставить fail2ban

```bash
sudo apt update && sudo apt install -y fail2ban
```

### 3.2. Filter

`/etc/fail2ban/filter.d/authsecurity-captcha.conf`:

```ini
[Definition]
# Срабатываем на любой 4xx/5xx ответ от captcha-web.
failregex = ^.*captcha-web <HOST> "[A-Z]+ [^"]*" [4-5]\d\d \d+ms\s*$
ignoreregex =
datepattern = {^LN-BEG}\[%%H:%%M:%%S
```

> `<HOST>` — встроенный плейсхолдер fail2ban для IP. Paper по умолчанию
> пишет таймстамп в формате `[HH:mm:ss INFO]` без даты, поэтому
> `datepattern` подсунут вручную — иначе fail2ban проигнорирует
> совпадение.

### 3.3. Jail

`/etc/fail2ban/jail.d/authsecurity-captcha.local`:

```ini
[authsecurity-captcha]
enabled  = true
filter   = authsecurity-captcha
backend  = polling
logpath  = /home/mc/server/logs/latest.log
maxretry = 20
findtime = 5m
bantime  = 1h
action   = ufw
```

`action = ufw` опирается на `/etc/fail2ban/action.d/ufw.conf` (есть из
коробки). Если используешь iptables — оставь дефолтное `iptables-multiport`.

### 3.4. Тест

```bash
sudo fail2ban-client reload
sudo fail2ban-client status authsecurity-captcha
sudo fail2ban-regex /home/mc/server/logs/latest.log /etc/fail2ban/filter.d/authsecurity-captcha.conf
```

Последняя команда покажет, сколько строк подошло. Если `0 matched` —
проверь путь к логу и формат таймстампа.

---

## 4. systemd-юнит для Paper (если ещё нет)

`/etc/systemd/system/minecraft.service`:

```ini
[Unit]
Description=Minecraft (Paper) server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=mc
Group=mc
WorkingDirectory=/home/mc/server
ExecStart=/usr/bin/java -Xms4G -Xmx4G -XX:+UseG1GC -jar paper.jar nogui
Restart=on-failure
RestartSec=15s

# hardening — ничего из этого не ломает Paper:
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=/home/mc/server
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft
sudo journalctl -u minecraft -f
```

---

## 5. Регулярные проверки

Раз в неделю прогоняй:

```bash
# 1. Сертификат не помер
sudo certbot certificates

# 2. nginx и fail2ban живы
systemctl status nginx fail2ban --no-pager

# 3. Текущие баны
sudo fail2ban-client status authsecurity-captcha

# 4. captcha-web слушает только localhost
sudo ss -tlnp | grep 25590

# 5. ufw в ожидаемом состоянии
sudo ufw status verbose
```

---

## 6. Что делать, если ботнет всё равно проламывает

В порядке возрастания "болезненности":

1. Поднять `bantime` до `24h`, понизить `maxretry` до `10`.
2. Поставить Cloudflare DNS + proxy перед `captcha.example.tld` —
   получишь бесплатный rate-limit и WAF до того, как трафик доходит до
   nginx.
3. Понизить `captcha.max-concurrent-challenges` (в `config.yml`) —
   игроки получат "queue is full" вместо мёртвого Paper.
4. Включить `crowdsec` параллельно с fail2ban (использует общую базу
   плохих IP). Не пытайся ставить оба в одну ban-цепочку, это
   гонка — пусть crowdsec пишет в свою цепочку iptables.

---

## 7. Чего НЕ делать

- Не открывай 25590 наружу "на всякий случай" — там нет ни авторизации,
  ни TLS. Весь публичный трафик ходит через nginx.
- Не клади `secret-key` Cloudflare в репозиторий и не отправляй его в
  логах. Если попал — сгенерируй новый в Turnstile dashboard.
- Не запускай Paper из-под `root`. Если пришлось — переделай systemd.
- Не выключай fail2ban на время "теста" и не возвращайся проверить
  через неделю — забывают всегда.
