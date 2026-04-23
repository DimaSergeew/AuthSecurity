# AuthSecurity

> Плагин авторизации для **Paper / Folia** серверов Minecraft, который заставляет игрока ввести пароль **до того, как он войдёт в мир**.

Плагин удерживает игрока на стадии **configuration phase** подключения с помощью `AsyncPlayerConnectionConfigureEvent`, показывает нативный диалог входа/регистрации и пропускает дальше только после успешной аутентификации. Никаких временных регионов, лимба или `teleport-to-spawn` костылей — игрок физически не загружается в мир, пока не авторизуется.

![Java](https://img.shields.io/badge/Java-25-orange)
![Paper](https://img.shields.io/badge/Paper_API-1.21.11-blue)
![Folia](https://img.shields.io/badge/Folia-supported-brightgreen)

---

## Возможности

- 🔐 **Гейт в configuration phase** — игрок блокируется до логина нативно, без спавна в мир.
- 🗄 **H2 и MariaDB** на выбор, пул соединений через **HikariCP**, подготовленные запросы хранятся в `.sql` ресурсах.
- 🔑 **Argon2id** для хеширования паролей (`password4j`), параметры зафиксированы и не должны меняться без миграции хешей.
- 🌐 **IP-лимит** — не более N одновременно подключённых аккаунтов с одного IP (настраивается).
- ⏳ **Доверенные сессии** — повторный вход с того же IP в течение TTL пропускает диалог.
- 💬 **Диалог «Забыли пароль?»** — встроенный (inline) диалог с инструкцией открыть тикет и кнопкой-ссылкой на Discord. Использует client-side `show_dialog` action.
- ⚙ **Brigadier-команды** через Cloud annotations: `/unregister`, `/changepassword`, `/accountinfo`.
- 🌸 **Folia-совместимый** — используются `AsyncScheduler` и корректно обрабатываются события подключения на регионах.
- 📝 **Конфиг из рекордов** — все настройки и сообщения строго типизированы, сообщения в формате **MiniMessage**.

---

## Установка

1. Скачайте `AuthSecurity.jar` из релизов (или соберите сами, см. ниже).
2. Положите в папку `plugins/` вашего Paper/Folia сервера.
3. Запустите сервер — при первом запуске будет создан `plugins/AuthSecurity/config.yml`.
4. Отредактируйте конфиг (особенно `database.type`, `support.discord-url` и лимит IP).
5. Перезапустите сервер.

### Требования

| Компонент | Версия |
|-----------|--------|
| Java      | **25** |
| Paper API | **1.21.11** |
| Folia     | поддерживается (`folia-supported: true`) |

---

## Сборка

```bash
./gradlew shadowJar
```

Готовый плагин окажется в `build/libs/AuthSecurity.jar`. Все зависимости (Hikari, H2, MariaDB JDBC, password4j, Cloud) затеняются под пакет `me.bedepay.authsecurity.libs`.

---

## Конфигурация

Файл `plugins/AuthSecurity/config.yml`:

```yaml
database:
  type: "h2"          # "h2" или "mariadb"
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
  max-attempts: 5
  session-ttl-hours: 1
  login-timeout-minutes: 3
  password-min-length: 6
  password-max-length: 72
  accounts-per-ip-limit: 3      # ← лимит одновременных аккаунтов с одного IP

support:
  discord-url: "https://discord.gg/your-invite-here"

messages:
  login-title: "<gold>🔐 Login</gold>"
  login-welcome: "<gray>Welcome back, <yellow><username></yellow>!</gray>"
  # ... полный список сообщений в MiniMessage
```

Все сообщения поддерживают формат [**MiniMessage**](https://docs.advntr.dev/minimessage/format.html) и могут содержать плейсхолдеры вида `<username>`, `<remaining>`, `<limit>`, `<player>`, `<min>`, `<max>`.

---

## Команды и права

| Команда | Назначение | Право |
|---------|------------|-------|
| `/changepassword` | Открывает диалог смены своего пароля (нужен старый пароль). | `authsecurity.changepassword` *(по умолчанию: всем)* |
| `/changepassword <player> <newpass>` | Принудительная смена пароля администратором. | `authsecurity.admin.changepassword` *(по умолчанию: op)* |
| `/unregister <player>` | Удаляет регистрацию игрока. | `authsecurity.admin.unregister` *(по умолчанию: op)* |
| `/accountinfo <player>` | Показывает UUID, последний IP, даты создания и обновления. | `authsecurity.admin.accountinfo` *(по умолчанию: op)* |

Все команды зарегистрированы через Brigadier (Paper's `PaperCommandManager`) и имеют нативное автодополнение.

---

## Архитектура

```
src/main/java/me/bedepay/authsecurity
├── AuthSecurity.java              — entry point, wiring
├── auth/
│   ├── AuthFlow.java              — config-phase gate + custom click handler
│   ├── PendingSession.java        — record состояния ожидающей сессии
│   ├── AuthResult.java            — record результата аутентификации
│   └── PasswordHasher.java        — обёртка Argon2id
├── commands/
│   └── AuthCommands.java          — Cloud-аннотированные команды
├── config/
│   ├── PluginConfig.java          — record всей конфигурации
│   ├── Messages.java              — record всех сообщений (MiniMessage)
│   └── ConfigLoader.java          — YAML → records
├── dialog/
│   └── Dialogs.java               — фабрика всех диалогов
├── ip/
│   └── ConnectionTracker.java     — учёт подключений по IP
└── storage/
    ├── Account.java               — record строки БД
    ├── AccountRepository.java     — интерфейс хранилища
    ├── HikariAccountRepository.java — реализация Hikari + H2/MariaDB
    └── SqlBundle.java             — загружает .sql файлы из resources

src/main/resources
├── config.yml                     — дефолтный конфиг
├── paper-plugin.yml
└── sql/
    ├── h2/*.sql                   — запросы для H2
    └── mariadb/*.sql              — запросы для MariaDB
```

### Ключевые инварианты

- **Нельзя удалять `future().join()`** в `AuthFlow.onConfigure` — это то, что удерживает игрока в configuration phase.
- **`canCloseWithEscape(false)`** на диалогах входа/регистрации — иначе игрок может прорваться мимо.
- **Параметры Argon2id (`19456 KiB, 2 iter, 1 thread, 32-byte, 16-byte salt`) менять нельзя** без миграции хешей.
- **Все SQL — в ресурсах**, код оперирует только `PreparedStatement` и параметрами.

---

## Поток авторизации

```
             ┌────────────────────────┐
             │ Player connects        │
             └──────────┬─────────────┘
                        │
                        ▼
   AsyncPlayerConnectionConfigureEvent (async)
                        │
        ┌───────────────┼────────────────┐
        │               │                │
        ▼               ▼                ▼
  IP trusted?     IP limit hit?     Account exists?
   skip dialog      disconnect      Login / Register
                                         │
                                         ▼
                                   Show Dialog
                                         │
                                future().join()  ◄──── blocks event thread
                                         │
                          PlayerCustomClickEvent
                                         │
                 ┌───────────────────────┼────────────────┐
                 ▼                       ▼                ▼
              submit                  cancel          forgot-password
               │                       │                  │
               ▼                       ▼                  ▼
         Argon2 verify            disconnect       inline dialog
               │                                   (showDialog + Discord URL)
               ▼
         complete(future)  →  player enters world
```

---

## Разработка

Проект использует **Gradle Kotlin DSL** и **Shadow 9.x**. Тесты отключены (`tasks.test { enabled = false }`) — плагин тестируется на реальном Paper/Folia сервере.

```bash
./gradlew shadowJar        # собрать плагин
./gradlew build            # compile + shadowJar
```

### Добавление новых сообщений

1. Добавьте поле в `Messages.java` (record).
2. Добавьте парсинг в `ConfigLoader.readMessages`.
3. Добавьте значение по умолчанию в `src/main/resources/config.yml`.
4. Если нужны плейсхолдеры — добавьте метод-рендер в `Messages`.

### Добавление новой команды

Добавьте аннотированный метод в `AuthCommands.java`:

```java
@Command("mycommand <arg>")
@Permission("authsecurity.admin.mycommand")
public void myCommand(CommandSourceStack source, @Argument("arg") String arg) { ... }
```

Парсер уже сканирует `AuthCommands` в `AuthSecurity#onEnable`.

---

## Безопасность

- Пароли хешируются **Argon2id** с memory cost 19 MiB, 2 итерации. Эти параметры выбраны как разумный компромисс между безопасностью и временем отклика диалога.
- Пароли **никогда** не логируются и не пересылаются между узлами.
- Команда `/changepassword <player> <pass>` всё равно оставляет пароль в истории команд — используйте её только для сервисных операций, а обычные игроки должны менять пароль диалогом.
- Таблица `accounts` хранит только UUID, имя, хеш и технические поля. Никаких плейнтекст-паролей.

---

## Лицензия

TODO: добавьте лицензию по вкусу (MIT / Apache 2.0 / кастомная).

---

## Автор

**bedepay** · [GitHub](https://github.com/DimaSergeew)
