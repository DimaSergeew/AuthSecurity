# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

AuthSecurity is a Paper/Folia plugin (API 1.21.11, Java 25) that forces players to log in or register before leaving the **configuration phase** of the connection — they never enter the world until authenticated.

Code is split across small focused classes under `me.bedepay.authsecurity`:

- `AuthSecurity` — plugin entrypoint, wires up components and owns reload.
- `auth/AuthFlow` — the configuration-phase gate + dialog click handler.
- `auth/PasswordHasher` — Argon2id wrapper (parameters are fixed).
- `auth/PasswordPolicy` — shared password rules (length + optional letter+digit).
- `auth/LockoutTracker` — per-account brute-force lockout surviving reconnects.
- `auth/IdleWatcher` — activity-based idle kick (event listener + async sweeper).
- `auth/PendingSession` / `auth/AuthResult` — immutable value types.
- `commands/AuthCommands` — `/unregister`, `/changepassword`, `/accountinfo`.
- `commands/AdminCommands` — `/authsecurity reload`, `/authsecurity logout`.
- `config/*` — `PluginConfig` record, `ConfigLoader`, `Messages`.
- `dialog/Dialogs` — all `Dialog` definitions in one place.
- `ip/ConnectionTracker` — per-IP concurrent-account limit.
- `storage/*` — `AccountRepository` interface + `HikariAccountRepository`, `SqlBundle`, `Account` record.

## Build

Gradle Kotlin DSL with the `com.gradleup.shadow` plugin.

- `./gradlew shadowJar` — produce the shaded plugin jar at `build/libs/AuthSecurity.jar` (no version suffix; see `archiveVersion = ""` in [build.gradle.kts](build.gradle.kts:44)).
- `./gradlew build` — compile + shadowJar. Tests are disabled (`tasks.test { enabled = false }`).
- Java 25 toolchain is required; Gradle will auto-provision it.
- Paper is `compileOnly`; runtime deps (`HikariCP`, `H2`, `MariaDB`, Cloud, password4j) are shaded and relocated under `me.bedepay.authsecurity.libs.*`.

## Runtime architecture

The gate hinges on Paper's configuration-phase API (`@SuppressWarnings("UnstableApiUsage")`):

1. **`AsyncPlayerConnectionConfigureEvent`** ([AuthFlow.java](src/main/java/me/bedepay/authsecurity/auth/AuthFlow.java)) fires off-thread while the player is still in the config phase. The handler **blocks on `session.future().join()`** to hold the player there until they submit the dialog. Do not move this logic to a sync event — it will deadlock the main thread.
2. Before showing the dialog, `AuthFlow` checks `LockoutTracker.remainingLockMinutes(uuid)` and, when no account exists for this UUID, does a case-insensitive username lookup — if a different-case account already owns the name, the player is disconnected with the correct spelling.
3. A `PendingSession` record is registered in the `pending` map keyed by UUID, with a `CompletableFuture<AuthResult>` that is completed by either the click handler or a `login-timeout-minutes` `completeOnTimeout`.
4. **`PlayerCustomClickEvent`** dispatches on `Key` identifiers under the `authsecurity:` namespace (`submit/login`, `submit/register`, `submit/change-password`, `cancel`, `forgot-back`) and reads input fields from the `DialogResponseView`.
5. On success, if `security.session-ttl-minutes > 0`, the player's IP is stored in `trustedSessions`, and a `ScheduledTask` is recorded in `trustExpiryTasks`. On each new successful auth the previous expiry task is cancelled before the new one is scheduled (prevents the race that would otherwise delete a still-valid entry).
6. On password change (admin or self) and on `/authsecurity logout`, `AuthFlow.invalidate(uuid)` clears the trust entry, cancels its expiry task, and drops the authenticated flag.

Key invariants to preserve when editing:

- **Never remove the `future().join()`** in `AuthFlow.onConfigure` — it is what gates the player.
- **Dialog `canCloseWithEscape(false)`** on login/register — the UI must not be dismissible.
- Argon2id parameters (`19456 KiB, 2 iter, 1 thread, 32-byte, 16-byte salt`, [PasswordHasher.java:17](src/main/java/me/bedepay/authsecurity/auth/PasswordHasher.java:17)) must stay constant — changing them invalidates every stored hash.
- When adding a new `trustedSessions.put(...)` site, always schedule via `scheduleTrustExpiry(uuid)` so the old expiry task is cancelled.

## Persistence

HikariCP pool with two supported dialects, selected by `database.type`:

- **H2** (default) — embedded file at `plugins/AuthSecurity/players.mv.db`, MySQL compatibility mode.
- **MariaDB** — remote server via `database.mariadb.*`.

SQL lives under `src/main/resources/sql/<dialect>/*.sql` and is loaded once into `SqlBundle`. `findByUsername` already uses `LOWER(username) = LOWER(?)` on both dialects, so username lookups are case-insensitive; the config-phase gate relies on this for the "wrong username case" rejection. If a fatal DB error occurs in `onEnable`, the server is shut down deliberately.

## Commands & permissions

Registered via `PaperCommandManager` + Cloud `AnnotationParser`. All `@Command` methods live on `AuthCommands` / `AdminCommands` and are parsed in `onEnable`.

| Command                                | Permission                               | Default |
|----------------------------------------|------------------------------------------|---------|
| `/unregister <player>`                 | `authsecurity.admin.unregister`          | op      |
| `/changepassword <player> <newpass>`   | `authsecurity.admin.changepassword`      | op      |
| `/changepassword`                      | `authsecurity.changepassword`            | true    |
| `/accountinfo <player>`                | `authsecurity.admin.accountinfo`         | op      |
| `/authsecurity reload`                 | `authsecurity.admin.reload`              | op      |
| `/authsecurity logout <player>`        | `authsecurity.admin.logout`              | op      |

Remember to add any new permission to [paper-plugin.yml](src/main/resources/paper-plugin.yml).

## Configuration

[config.yml](src/main/resources/config.yml) is the single source of truth. When adding a new knob:

1. Add it to [config.yml](src/main/resources/config.yml) with a comment.
2. Extend the matching record in [PluginConfig.java](src/main/java/me/bedepay/authsecurity/config/PluginConfig.java).
3. Read it in [ConfigLoader.java](src/main/java/me/bedepay/authsecurity/config/ConfigLoader.java) with a sensible default.
4. If it's runtime-swappable, accept it through each component's `applyConfig(...)` and wire it into `AuthSecurity.reload()`.

Database-section knobs are **not** reload-safe (the Hikari pool owns open connections); document this near any such knob.

Messages are MiniMessage templates. Add a field to the `Messages` record, a `getString` line in `ConfigLoader.readMessages`, and a key in `config.yml`. For interpolated messages, expose a helper method on `Messages` that calls `render(...)` with `Placeholder.unparsed(...)`.
