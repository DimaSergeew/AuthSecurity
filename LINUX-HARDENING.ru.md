# Hardening captcha-вебсервера на Ubuntu (Pelican + Docker + TcpShield)

> **Кому это:** другому Claude в shell на хосте, где уже стоят:
> - **Pelican panel** (управляет Paper-сервером в Docker-контейнере),
> - **nginx** (фронт для панели; туда же подвесим домен капчи),
> - **iptables** (Docker сам им рулит, поэтому работаем через
>   `DOCKER-USER`-цепочку, а **не** через ufw),
> - **TcpShield** (защищает Minecraft-порт 25565 на TCP-уровне).
>
> Это **не** учебник, это чек-лист с готовыми командами и тем, на что
> в этом стеке надо обратить внимание особо.

Базовое развёртывание (Cloudflare Turnstile, ключи, nginx-блок,
certbot) — в [`CAPTCHA-DEPLOY.ru.md`](CAPTCHA-DEPLOY.ru.md). Здесь —
что добавить сверху и где этот стек ловит подвохи.

---

## 0. Где что физически лежит в Pelican + Docker

Найди UUID конкретного сервера в панели и его том на хосте:

```bash
# UUID в панели → "Settings" → видно в URL и в API.
# Том сервера на хосте по умолчанию:
ls /var/lib/pelican/volumes/<server-uuid>/

# Логи Paper:
PAPER_LOG=/var/lib/pelican/volumes/<server-uuid>/logs/latest.log
ls -la "$PAPER_LOG"

# config.yml плагина:
ls -la /var/lib/pelican/volumes/<server-uuid>/plugins/AuthSecurity/config.yml
```

Если путь другой — запомни свой и подставляй везде ниже вместо
`<paper-volume>`. Все правки `config.yml` делаются в этом томе и
**требуют рестарта контейнера** через панель (или `docker restart
<container-id>`), а не reload изнутри Pelican-консоли — Javalin
поднимается один раз на запуске JVM.

В `config.yml` для прода:

```yaml
captcha:
  enabled: true
  web-bind: "127.0.0.1"   # <— ОБЯЗАТЕЛЬНО на проде
  web-port: 25590
  public-base-url: "https://captcha.example.tld"
```

> **Важно про Docker bridge-network:** если контейнер Paper стоит в
> bridge-сети (по умолчанию у Pelican), то `127.0.0.1` внутри
> контейнера — это **внутренний loopback контейнера**, не хост.
> nginx, который живёт **на хосте**, не достучится до `127.0.0.1:25590`
> внутри контейнера. Варианты:
>
> 1. **Проксировать через сеть контейнера** (рекомендуется): открой
>    25590 в Pelican-аллокациях, в `config.yml` оставь
>    `web-bind: "0.0.0.0"`, а nginx направь на
>    `proxy_pass http://<docker-bridge-ip>:25590` или на
>    `<host-mapped-port>` если Pelican прокинул его на хост-интерфейс
>    `127.0.0.1`. Проверь:
>    ```bash
>    sudo docker ps --filter name=<server-uuid> --format '{{.Ports}}'
>    # ищи строку вида "127.0.0.1:25590->25590/tcp"
>    ```
> 2. Если Pelican прокинул порт как `0.0.0.0:25590->25590/tcp`,
>    закроем его iptables-ом наружу — см. §1.
>
> До запуска прода: проверь обоими способами `curl`-ом с хоста
> и из публичной сети, что 25590 **наружу не отвечает**, а nginx
> доходит.

---

## 1. iptables в Docker-окружении

Docker сам пишет правила в `nat` и в цепочки `DOCKER`/`DOCKER-USER`.
Свои `INPUT`-правила Docker **проигнорирует** для трафика, идущего к
контейнерам — нужно работать с `DOCKER-USER`. ufw здесь только мешает,
не ставь его.

### 1.1. Закрыть 25590 для всего, кроме хост-loopback

Если Pelican прокинул порт как `0.0.0.0:25590`, добавь правило **в
самое начало `DOCKER-USER`**, чтобы блокировать всё кроме локального
nginx:

```bash
# Разрешаем хост-loopback (nginx говорит с контейнером через docker0):
sudo iptables -I DOCKER-USER -i lo -j ACCEPT
# Разрешаем уже установленные коннекты:
sudo iptables -I DOCKER-USER -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
# Разрешаем nginx (хост) → контейнер на 25590:
sudo iptables -I DOCKER-USER -s 127.0.0.1 -p tcp --dport 25590 -j ACCEPT
# Всё остальное на 25590 — режем:
sudo iptables -A DOCKER-USER -p tcp --dport 25590 -j DROP
```

Сохраняем после рестарта:

```bash
sudo apt install -y iptables-persistent
sudo netfilter-persistent save
```

### 1.2. Проверка снаружи

С другого хоста:

```bash
nc -vz <публичный-ip> 25590
# должно: "Connection refused" или таймаут
nc -vz <публичный-ip> 443
# должно: succeeded
```

### 1.3. Что НЕ трогать

- Не чисти цепочки `DOCKER` и `DOCKER-INGRESS` руками — Docker их
  пересобирает при рестарте.
- Не используй `iptables -P INPUT DROP` без понимания: Pelican-демон,
  Wings и сам Docker полагаются на свои правила. Меняй только
  `DOCKER-USER` и собственный кастомный chain.

---

## 2. nginx (уже стоит для Pelican-панели)

В `/etc/nginx/sites-available/` уже есть конфиг панели. Добавь рядом
**отдельный server-блок** для домена капчи. Ничего не трогай в
конфиге панели.

### 2.1. Rate-limit zone (в `http { ... }` глобально, один раз)

`/etc/nginx/conf.d/captcha-ratelimit.conf`:

```nginx
limit_req_zone $binary_remote_addr zone=captcha_zone:10m rate=10r/s;
```

### 2.2. Server-блок

`/etc/nginx/sites-available/authsecurity-captcha`:

```nginx
server {
    listen 443 ssl http2;
    server_name captcha.example.tld;

    # ssl_certificate / ssl_certificate_key подставит certbot

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options    "nosniff"   always;
    add_header X-Frame-Options           "DENY"      always;
    add_header Referrer-Policy           "no-referrer" always;
    # Turnstile грузит виджет с challenges.cloudflare.com:
    add_header Content-Security-Policy   "default-src 'self'; script-src 'self' https://challenges.cloudflare.com 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-src https://challenges.cloudflare.com; connect-src 'self'" always;

    client_max_body_size 8k;
    client_body_timeout  10s;
    keepalive_timeout    15s;

    # health-check наружу не светим
    location = / { return 404; }

    location = /verify {
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

    location / { return 404; }
}
```

> Если контейнер Paper стоит в bridge и порт **не** проброшен на
> `127.0.0.1` хоста — поменяй `proxy_pass` на адрес контейнера в
> docker-сети, например `http://172.17.0.2:25590` (см. §0).

### 2.3. Включить + сертификат

```bash
sudo ln -s /etc/nginx/sites-available/authsecurity-captcha \
           /etc/nginx/sites-enabled/authsecurity-captcha
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d captcha.example.tld
```

### 2.4. Если у домена **уже стоит Cloudflare proxy** (оранжевая туча)

Тогда `$remote_addr` в логе nginx — это IP CF, а не игрока. Чтобы
fail2ban банил настоящие IP-адреса, поставь модуль и доверь CF:

```bash
sudo apt install -y libnginx-mod-http-realip
```

`/etc/nginx/conf.d/cloudflare-realip.conf`:

```nginx
real_ip_header CF-Connecting-IP;
# актуальные диапазоны: https://www.cloudflare.com/ips/
set_real_ip_from 173.245.48.0/20;
set_real_ip_from 103.21.244.0/22;
set_real_ip_from 103.22.200.0/22;
# ... (полный список — обновлять раз в полгода)
```

Если **Cloudflare proxy НЕ включён** на этом домене — пропусти этот
шаг.

---

## 3. fail2ban

Плагин теперь пишет в `latest.log` строку формата:

```
[12:34:56 INFO]: captcha-web 1.2.3.4 "GET /c/abc..." 410 12ms
[12:34:57 INFO]: captcha-web 1.2.3.4 "POST /verify" 400 5ms
```

Любой статус ≥ 400 — повод сработать.

### 3.1. Установить

```bash
sudo apt update && sudo apt install -y fail2ban
```

### 3.2. Filter

`/etc/fail2ban/filter.d/authsecurity-captcha.conf`:

```ini
[Definition]
failregex = ^.*captcha-web <HOST> "[A-Z]+ [^"]*" [4-5]\d\d \d+ms\s*$
ignoreregex =
datepattern = {^LN-BEG}\[%%H:%%M:%%S
```

### 3.3. Jail (логи внутри Pelican-тома)

`/etc/fail2ban/jail.d/authsecurity-captcha.local`:

```ini
[authsecurity-captcha]
enabled  = true
filter   = authsecurity-captcha
backend  = polling
logpath  = /var/lib/pelican/volumes/<server-uuid>/logs/latest.log
maxretry = 20
findtime = 5m
bantime  = 1h
action   = iptables-multiport[name=captcha, port="80,443", protocol=tcp]
```

> Используем `iptables-multiport`, а не `ufw`, потому что ufw в этом
> стеке не стоит. Бан вешается на `INPUT` (не `DOCKER-USER`), но это
> ОК: трафик капчи приходит на nginx по 80/443, а не напрямую в
> контейнер. fail2ban перехватит атакующего ещё до nginx.

### 3.4. Проверка

```bash
sudo fail2ban-client reload
sudo fail2ban-client status authsecurity-captcha
sudo fail2ban-regex /var/lib/pelican/volumes/<server-uuid>/logs/latest.log \
                    /etc/fail2ban/filter.d/authsecurity-captcha.conf
```

`Lines: matched = N` — должно быть > 0 после первой неудачной
попытки. Если `matched = 0` — открой `latest.log`, проверь, есть ли
там строки `captcha-web ...` (плагин обновлён?) и формат таймстампа.

### 3.5. Ротация лога Paper

Pelican ротирует `latest.log` сам — fail2ban с `backend = polling`
переоткроет файл автоматически, делать ничего не надо. Если
поставлен inotify-бэкенд (`backend = systemd` или `backend =
auto`) — оставь так же.

---

## 4. systemd-юнит

**Не нужен.** Paper-сервером управляет Pelican через Docker. Не
создавай конкурирующий systemd-сервис.

Если нужно автозапустить ВСЁ окружение Pelican после перезагрузки
хоста:

```bash
sudo systemctl enable --now wings docker
```

(Wings — демон Pelican, поднимает контейнеры из БД панели.)

---

## 5. TcpShield и captcha — ВАЖНОЕ разграничение

TcpShield защищает **только TCP-трафик Minecraft-протокола** (порт
25565 или твой кастомный). Он работает на L4 как backconnect-прокси.

Captcha-сервер живёт на **HTTP/443**. TcpShield туда **не относится**:
- Не пропускай домен `captcha.example.tld` через TcpShield-CNAME.
- Если случайно завернёшь — TLS-handshake пойдёт через TcpShield,
  а он не настроен на HTTP/SNI и оборвёт. Будет диагностически
  странный "Network error" в виджете.

Что делать вместо TcpShield для капчи:
1. nginx + Let's Encrypt напрямую (как в §2).
2. **Опционально** Cloudflare proxy перед nginx (§2.4) — даёт
   anti-bot/WAF/rate-limit. Совместим с тем, что Minecraft-порт
   защищён TcpShield-ом отдельно.

**Связь между ними одна:** TcpShield в коннект-логе показывает
реальный IP игрока через Paper-плагин `tcpshield-paper` или через
`bungee-proxy-protocol`. Эти IP-адреса попадают в `last_ip` плагина и
в `captcha-web ... <ip>` лог — поэтому fail2ban-бан на 80/443 ловит
именно атакующего, а не TcpShield-edge.

> Если в логе `captcha-web` IP всегда один и тот же (например,
> Cloudflare edge) — это значит, что Javalin **не доверяет**
> `X-Forwarded-For`. Открой issue / напиши разработчику добавить
> `cfg.jetty.... forwardedHeader` в `CaptchaWebServer.start()`.
> На момент написания этого файла поддержка прокси-заголовка ещё
> **не включена в Javalin-конфиге** — это TODO.

---

## 6. Регулярные проверки

Раз в неделю:

```bash
sudo certbot certificates                                  # сертификат
systemctl status nginx fail2ban wings docker --no-pager    # сервисы живы
sudo fail2ban-client status authsecurity-captcha           # текущие баны
sudo ss -tlnp | grep 25590                                 # 25590 не торчит наружу
sudo iptables -L DOCKER-USER -n -v --line-numbers          # правила Docker не сломались
```

---

## 7. Если ботнет всё равно проламывает

В порядке возрастания "болезненности":

1. `bantime = 24h`, `maxretry = 10` в jail.
2. Включи Cloudflare proxy (оранжевая туча) на `captcha.example.tld`,
   добавь правило WAF `Block: cf.threat_score > 30`.
3. Понизь `captcha.max-concurrent-challenges` в `config.yml`.
4. crowdsec параллельно с fail2ban. Не объединяй цепочки — пусть
   каждый пишет в свою.

---

## 8. Чего НЕ делать в этом стеке

- Не ставить ufw "поверх" Docker. ufw и Docker не дружат, ufw молча
  не применит правила к контейнерному трафику.
- Не запускать `iptables -F` "почистить и переписать" — обнулишь
  Docker и перестанет ходить трафик в контейнеры. Если уж надо —
  `sudo systemctl restart docker` после.
- Не светить `secret-key` Cloudflare нигде. Он лежит в `config.yml`
  внутри Pelican-тома, доступ к тому ограничен правами.
- Не пихать домен капчи через TcpShield-CNAME (§5).
- Не убирать `web-bind: 127.0.0.1` в проде. Если контейнер требует
  bridge-сети — см. §0, ставь `0.0.0.0` + iptables-замок в §1.
