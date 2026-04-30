# Развёртывание captcha-гейта AuthSecurity на Linux

Этот документ — пошаговая инструкция, как поднять anti-bot проверку
(Cloudflare Turnstile + встроенный HTTP-сервер плагина) на Linux-машине,
где работает Paper/Folia сервер.

> **Кратко:** Cloudflare выдаёт капчу. Плагин поднимает свой HTTP-сервер
> на отдельном порту, отдаёт страницу с виджетом Turnstile, валидирует
> ответ и пропускает игрока в configuration-phase. Игроку достаточно
> один раз пройти проверку в браузере — Minecraft-клиент продолжит сам.

---

## 1. Зарегистрируй сайт в Cloudflare Turnstile

1. Зайди в Cloudflare Dashboard → **Turnstile** → **Add site**.
2. Имя — любое (например, `PinkyFoxy MC`).
3. Domain — домен, который будет указан в `public-base-url`
   (например, `captcha.pinkyfoxy.ru`). Если планируешь работать
   по голому IP без TLS — укажи domain `localhost` (Cloudflare разрешает
   тестировать так).
4. Widget Mode — **Managed** (рекомендуется) или Invisible.
5. Нажми Create — получишь два ключа:
   - **Site key** — публичный, попадает в HTML страницу.
   - **Secret key** — приватный, держится только на сервере.

---

## 2. Настрой `config.yml` плагина

После первого запуска плагин создаст `plugins/AuthSecurity/config.yml`.
Найди секцию `captcha:` и заполни её:

```yaml
captcha:
  enabled: true
  site-key: "0x4AAAAAAA..."       # из Cloudflare
  secret-key: "0x4AAAAAAA..."     # из Cloudflare (никому не показывай)
  web-port: 25590                 # порт, на котором плагин поднимет HTTP
  public-base-url: "https://captcha.pinkyfoxy.ru"
  token-ttl-minutes: 10           # сколько живёт ссылка-проверки
  verification-validity-days: 7   # сколько дней не спрашивать капчу повторно
  max-concurrent-challenges: 50   # глобальный лимит одновременных проверок
```

После правки `config.yml` параметры `enabled`, `web-port` и
`public-base-url` **требуют полного рестарта сервера** — они захватывают
порт и поднимают Javalin один раз на старте. Остальные ключи можно
поменять и применить через `/authsecurity reload`.

---

## 3. Открой порт в файрволе

Captcha-сервер слушает на `0.0.0.0:web-port`. Этот порт должен быть
доступен снаружи (либо напрямую, либо через nginx).

### UFW (Ubuntu/Debian)

```bash
sudo ufw allow 25590/tcp comment 'AuthSecurity captcha'
sudo ufw reload
```

### firewalld (Rocky/Alma/RHEL)

```bash
sudo firewall-cmd --permanent --add-port=25590/tcp
sudo firewall-cmd --reload
```

### iptables (legacy)

```bash
sudo iptables -A INPUT -p tcp --dport 25590 -j ACCEPT
sudo iptables-save > /etc/iptables/rules.v4
```

### Облачный провайдер

Если сервер у Hetzner / OVH / AWS / Yandex Cloud — открой порт
**ещё и в группе безопасности (Security Group / Cloud Firewall)**,
не только локально.

---

## 4. (Рекомендуется) Поставь nginx + TLS

Голый HTTP на нестандартном порту работает, но:

- Браузер будет ругаться "Not secure" в адресной строке.
- Cloudflare Turnstile спокойно работает по HTTP, но HTTPS более серьёзен.
- На красивый домен (`captcha.pinkyfoxy.ru`) проще зайти, чем на `http://1.2.3.4:25590`.

### 4.1. Установка nginx и certbot

```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx
```

### 4.2. Поддомен в DNS

В DNS-зоне домена `pinkyfoxy.ru` добавь A-запись:

```
captcha.pinkyfoxy.ru.   A   <ip-сервера>
```

Подожди несколько минут до распространения DNS.

### 4.3. nginx-конфиг

`/etc/nginx/sites-available/pinkyfoxy-captcha`:

```nginx
server {
    listen 80;
    server_name captcha.pinkyfoxy.ru;

    location / {
        proxy_pass         http://127.0.0.1:25590;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
```

Включи и проверь конфиг:

```bash
sudo ln -s /etc/nginx/sites-available/pinkyfoxy-captcha \
           /etc/nginx/sites-enabled/pinkyfoxy-captcha
sudo nginx -t
sudo systemctl reload nginx
```

### 4.4. Получи TLS-сертификат

```bash
sudo certbot --nginx -d captcha.pinkyfoxy.ru
```

Certbot сам перепишет конфиг на 443 + добавит редирект 80 → 443
+ запланирует автообновление через systemd timer.

### 4.5. Закрой прямой доступ к 25590 наружу

Если nginx стоит на той же машине, плагин уже слушает на `0.0.0.0`,
и теоретически доступен напрямую по `http://ip:25590`. Чтобы пускать
трафик только через nginx, запрети порт 25590 в файрволе:

```bash
sudo ufw delete allow 25590/tcp
sudo ufw allow from 127.0.0.1 to any port 25590 proto tcp
```

(При желании в `CaptchaWebServer` можно поменять `defaultHost` на
`127.0.0.1` — тогда внешний доступ закрыт на уровне сокета.)

### 4.6. Поправь `config.yml`

```yaml
captcha:
  public-base-url: "https://captcha.pinkyfoxy.ru"
  web-port: 25590
```

Перезапусти сервер.

---

## 5. Тестирование

1. Запусти Minecraft-сервер.
2. В консоли должно быть:
   ```
   [AuthSecurity] Captcha web server listening on port 25590
   ```
3. Открой в браузере `https://captcha.pinkyfoxy.ru/` — должно вернуться
   `AuthSecurity captcha gate OK`.
4. Подключись к серверу новым аккаунтом. Должен появиться диалог
   "PINKY FOXY · Anti-bot Verification". Кликни на ссылку, пройди
   капчу — Minecraft-клиент сам продолжит на регистрацию.

---

## 6. Эксплуатация

### Перезагрузка конфига без рестарта

```
/authsecurity reload
```

Перезагружает: site-key, secret-key, token-ttl-minutes,
verification-validity-days, max-concurrent-challenges, тексты.
**Не перезагружает**: enabled, web-port, public-base-url, базу данных.

### Временно выключить капчу

В `config.yml`:
```yaml
captcha:
  enabled: false
```
Перезапусти сервер. (Reload здесь не сработает — Javalin живёт пока сервер
работает.)

### Сбросить дату последней верификации игрока

В БД (H2 файл `plugins/AuthSecurity/players.mv.db` или MariaDB):
```sql
UPDATE accounts SET captcha_verified_at = NULL WHERE username = 'имя';
```
При следующем входе игроку покажется диалог обновления верификации.

### Очистить просроченные токены вручную

```sql
DELETE FROM captcha_tokens WHERE expires_at <= CURRENT_TIMESTAMP;
```
(Это и так делается раз в 10 минут автоматически.)

---

## 7. Защита от DoS

Каждая открытая капча-сессия держит **один Paper async-поток** до
`token-ttl-minutes`. Без ограничений ботнет может полностью съесть
пул потоков.

Защита:

- **`accounts-per-ip-limit`** (`security:`) — не больше 3 одновременных
  подключений с одного IP.
- **`max-concurrent-challenges`** (`captcha:`) — глобальный потолок
  открытых проверок (по умолчанию 50). При превышении новые подключения
  получают сообщение "verification queue is full" и отключаются.
- Поставь Cloudflare перед Minecraft (через `proxy_protocol`) или
  fail2ban / crowdsec на уровне ОС, если нагрузка серьёзная.

---

## 8. Частые проблемы

| Симптом | Причина | Решение |
|---------|---------|---------|
| `Failed to start captcha web server` в логе | Порт занят | Поменяй `web-port` или останови процесс на этом порту: `sudo lsof -i :25590` |
| Игроку приходит "Failed to issue a verification link" | Ошибка БД | Проверь логи Hikari, права у пользователя БД |
| Виджет Turnstile не появляется | Неверный `site-key` или CSP блокирует Cloudflare | Открой DevTools → Console, проверь, что подгрузился `https://challenges.cloudflare.com/turnstile/v0/api.js` |
| Капча проходит, но Minecraft не выходит из "Joining world" | `secret-key` неверный → siteverify возвращает 200 без `"success":true` | Перепроверь secret-key в Cloudflare Dashboard |
| `verification queue is full` | Достигнут `max-concurrent-challenges` | Подними потолок, либо разбирайся, кто атакует |
| Cloudflare ругается на domain mismatch | В Turnstile-сайте указан другой домен | Либо добавь `*` в Turnstile, либо синхронизируй `public-base-url` с тем, что зарегистрировано |

---

## 9. Безопасность

- **`secret-key` никогда не светится в логи и не отправляется клиенту** —
  он используется только в server-side вызове `siteverify`.
- Токены капчи (`captcha_tokens.token`) одноразовые в пределах своего
  TTL и не привязаны к `last_ip` после выпуска — этого достаточно,
  потому что Cloudflare-ответ привязан к `cf-token`, который проверяется
  на сервере вместе с `remoteip`.
- HTTPS (через nginx) **обязателен** на публичном домене — иначе токены
  капчи могут перехватить через MITM на пути от игрока к твоему серверу.
- Не публикуй файл `players.mv.db` или дамп таблицы `accounts` —
  там лежат хеши Argon2id, которые хоть и медленные, но всё же атакуемые.
