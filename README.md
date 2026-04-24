<h1 align="center">🔐 AuthSecurity</h1>

<p align="center">
  <b>Гейт авторизации для Paper / Folia прямо в <i>configuration phase</i></b><br>
  <sub>Игрок не попадает в мир, пока не введёт пароль. Без лимба, без временных миров, без телепортов.</sub>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/Paper-1.21.11-blue?logo=minecraft&logoColor=white" alt="Paper 1.21.11">
  <img src="https://img.shields.io/badge/Folia-supported-brightgreen" alt="Folia">
  <img src="https://img.shields.io/badge/Argon2id-password4j-8A2BE2" alt="Argon2id">
  <img src="https://img.shields.io/badge/DB-H2%20%2F%20MariaDB-lightgrey?logo=mariadb" alt="H2 / MariaDB">
</p>

---

## ✨ Возможности

|   | Фича | Описание |
|---|------|----------|
| 🚪 | **Config-phase gate** | Блокировка в `AsyncPlayerConnectionConfigureEvent` — игрок физически не грузится в мир. |
| 🔑 | **Argon2id** | `password4j`, 19 MiB / 2 iter / 16-byte salt. Параметры зафиксированы. |
| 🗄 | **H2 / MariaDB** | HikariCP + prepared statements из `.sql` ресурсов. |
| 🌐 | **IP-лимит** | Макс. N одновременных аккаунтов с одного IP. |
| ⏳ | **Trusted sessions** | Повторный вход с того же IP в течение TTL — без диалога. |
| 🚫 | **Account lockout** | Блокировка по UUID после N неудач — **переживает реконнект**. |
| 💤 | **Idle kick** | Кик авторизованного игрока после бездействия (движение/чат/команды). |
| 🅰 | **Password policy** | Опционально: обязательна буква + цифра. |
| 🔡 | **Case-sensitive login** | Регистр логина проверяется — «другой регистр» вежливо отклоняется. |
| 🚪 | **Force logout** | Админ может разлогинить игрока и инвалидировать trusted-сессию. |
| ♻ | **Hot reload** | `/authsecurity reload` без рестарта (кроме секции `database`). |
| 💬 | **Forgot password** | Inline-диалог с кнопкой-ссылкой на Discord. |
| ⚙ | **Brigadier-команды** | Cloud annotations + нативное автодополнение. |
| 🌸 | **Folia-ready** | `AsyncScheduler`, корректная обработка событий по регионам. |
| 📝 | **MiniMessage** | Все сообщения строго типизированы (records). |

---

## 🚀 Установка

```bash
# 1. Скачайте AuthSecurity.jar (Releases) или соберите:
./gradlew shadowJar      # → build/libs/AuthSecurity.jar

# 2. Скопируйте в plugins/, запустите сервер.
# 3. Отредактируйте plugins/AuthSecurity/config.yml.
# 4. /authsecurity reload — применить без рестарта.
```

**Требования:** Java **25** · Paper API **1.21.11** · Folia опционально.

---

## 🧭 Команды

| Команда | Назначение | Право | Default |
|---------|------------|-------|---------|
| `/changepassword` | Смена своего пароля (диалогом). | `authsecurity.changepassword` | all |
| `/changepassword <player> <newpass>` | Принудительная смена пароля. | `authsecurity.admin.changepassword` | op |
| `/unregister <player>` | Удалить регистрацию. | `authsecurity.admin.unregister` | op |
| `/accountinfo <player>` | UUID, IP, даты создания/обновления. | `authsecurity.admin.accountinfo` | op |
| `/authsecurity reload` | Перечитать конфиг без рестарта. | `authsecurity.admin.reload` | op |
| `/authsecurity logout <player>` | Разлогинить + инвалидировать trust. | `authsecurity.admin.logout` | op |

> 💡 Смена пароля **инвалидирует trusted-сессию** — игрок должен залогиниться заново.

---

## ⚙️ Конфигурация

<details>
<summary><b>plugins/AuthSecurity/config.yml</b> — кликабельно</summary>

```yaml
database:
  type: "h2"                    # "h2" | "mariadb"
  h2:
    file: "players"
    options: "AUTO_SERVER=FALSE;MODE=MySQL"
  mariadb:
    host: "127.0.0.1"
    port: 3306
    database: "authsecurity"
    username: "auth"
    password: "change-me"
    parameters: "useSSL=false&autoReconnect=true&characterEncoding=utf8"
  pool:
    maximum-pool-size: 8
    minimum-idle: 2
    connection-timeout-millis: 5000

security:
  max-attempts: 5               # попыток за сессию диалога
  session-ttl-minutes: 60       # TTL trusted-сессии (0 = всегда спрашивать)
  login-timeout-minutes: 3      # таймаут диалога
  password-min-length: 6
  password-max-length: 72
  accounts-per-ip-limit: 3      # одновременных аккаунтов с IP

  lockout:                      # persistent lockout (переживает реконнект)
    enabled: true
    max-attempts: 5
    ban-minutes: 15

  idle-logout:                  # кик за бездействие
    enabled: false
    minutes: 30

  password-policy:
    require-letter-and-digit: false

support:
  discord-url: "https://discord.gg/your-invite-here"

messages:
  login-title: "<gold>🔐 Login</gold>"
  # ... полный набор сообщений — MiniMessage
```

Плейсхолдеры: `<username>`, `<remaining>`, `<limit>`, `<player>`, `<min>`, `<max>`, `<minutes>`, `<correct>`, `<key>`, `<value>`.
Формат: [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

> ⚠️ Секция `database` **не** применяется через `/authsecurity reload` — Hikari держит открытые соединения. Всё остальное hot-reloadable.

</details>

---

## 🧩 Поток авторизации

```
Player connects
       │
       ▼
AsyncPlayerConnectionConfigureEvent  (async)
       │
  ┌────┼──────────────┬──────────────┬──────────────┐
  ▼                   ▼              ▼              ▼
IP trusted?    IP limit hit?   Locked out?   Username case OK?
  skip            disconnect     disconnect    disconnect (hint)
                                       │
                                       ▼
                                 Show Dialog
                                       │
                                future().join()  ← блок до submit
                                       │
                            PlayerCustomClickEvent
                                       │
              ┌────────────────────────┼─────────────────┐
              ▼                        ▼                 ▼
           submit                   cancel        forgot-password
              │                        │                 │
              ▼                        ▼                 ▼
        Argon2 verify             disconnect       inline dialog
       /         \                                  (Discord URL)
      ✓           ✗
      │       LockoutTracker++
      ▼
 complete(future) → IdleWatcher arm → player enters world
```

---

## 🏗 Архитектура

<details>
<summary><b>Дерево проекта</b></summary>

```
src/main/java/me/bedepay/authsecurity
├── AuthSecurity.java              — entry point, wiring, reload()
├── auth/
│   ├── AuthFlow.java              — config-phase gate + click handler
│   ├── PendingSession.java        — record ожидающей сессии
│   ├── AuthResult.java            — record результата
│   ├── PasswordHasher.java        — Argon2id wrapper
│   ├── PasswordPolicy.java        — общие правила паролей
│   ├── LockoutTracker.java        — persistent brute-force lockout
│   └── IdleWatcher.java           — idle-kick (listener + async sweeper)
├── commands/
│   ├── AuthCommands.java          — /unregister, /changepassword, /accountinfo
│   └── AdminCommands.java         — /authsecurity reload · logout
├── config/
│   ├── PluginConfig.java          — record всего конфига
│   ├── Messages.java              — record сообщений (MiniMessage)
│   └── ConfigLoader.java          — YAML → records
├── dialog/Dialogs.java            — фабрика диалогов
├── ip/ConnectionTracker.java      — учёт подключений по IP
└── storage/
    ├── Account.java               — record строки БД
    ├── AccountRepository.java     — интерфейс
    ├── HikariAccountRepository.java — H2 / MariaDB реализация
    └── SqlBundle.java             — загрузка .sql из resources

src/main/resources
├── config.yml · paper-plugin.yml · psw4j.properties
└── sql/{h2,mariadb}/*.sql         — LOWER(username)=LOWER(?) везде
```

</details>

### 🛑 Инварианты (не трогать)

- **`future().join()`** в `AuthFlow.onConfigure` — именно он держит игрока в config-phase.
- **`canCloseWithEscape(false)`** на login/register — иначе можно прорваться мимо.
- **Параметры Argon2id** менять нельзя без миграции хешей.
- **SQL — только в ресурсах** и только через `PreparedStatement`.
- При любой новой записи в `trustedSessions` — планировать expiry через `scheduleTrustExpiry(uuid)` (отменяет старую задачу, иначе гонка).
- `findByUsername` использует `LOWER(...)` — на этом держится проверка «неверный регистр».

---

## 🧪 Разработка

```bash
./gradlew shadowJar        # сборка плагина
./gradlew build            # compile + shadowJar (тесты отключены)
```

Все runtime-зависимости (Hikari, H2, MariaDB, Cloud, password4j) затеняются и релокейтятся под `me.bedepay.authsecurity.libs.*`.

<details>
<summary><b>Добавить сообщение</b></summary>

1. Поле в `Messages.java` (record).
2. Парсинг в `ConfigLoader.readMessages`.
3. Дефолт в `src/main/resources/config.yml`.
4. Для плейсхолдеров — метод-рендер через `Placeholder.unparsed(...)`.

</details>

<details>
<summary><b>Добавить команду</b></summary>

```java
@Command("mycommand <arg>")
@Permission("authsecurity.admin.mycommand")
public void myCommand(CommandSourceStack source, @Argument("arg") String arg) { ... }
```

Парсер сканирует `AuthCommands` / `AdminCommands` в `AuthSecurity#onEnable`.
Не забыть добавить permission в [paper-plugin.yml](src/main/resources/paper-plugin.yml).

</details>

<details>
<summary><b>Добавить конфиг-параметр</b></summary>

1. Запись в `config.yml` с комментарием.
2. Поле в соответствующем record'е `PluginConfig`.
3. Чтение в `ConfigLoader` с дефолтом.
4. Если hot-reloadable — пробросить через `applyConfig(...)` компонента в `AuthSecurity.reload()`.

</details>

---

## 🛡 Безопасность

- Пароли — **Argon2id**, никогда не логируются.
- **Lockout** переживает реконнект — брутфорс через новое TCP-соединение не помогает.
- **Idle kick** разлогинивает отошедших игроков, защищая от захвата сессии.
- `/changepassword <player> <pass>` оставляет пароль в истории — только для сервисных задач; смена пароля инвалидирует trusted-сессию.
- Таблица `accounts`: UUID, username, hash, служебные поля. Никакого plaintext.

---

<p align="center">
  <sub>© <b>bedepay</b> · <a href="https://github.com/DimaSergeew">GitHub</a> · Лицензия: <i>TODO (MIT / Apache 2.0)</i></sub>
</p>
