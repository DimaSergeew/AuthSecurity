# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

AuthSecurity is a Paper/Folia plugin (API 1.21.11, Java 25) that forces players to log in or register before leaving the **configuration phase** of the connection — they never enter the world until authenticated. Built as a single class (`me.bedepay.AuthSecurity`).

## Build

Gradle Kotlin DSL with the `com.gradleup.shadow` plugin.

- `./gradlew shadowJar` — produce the shaded plugin jar at `build/libs/AuthSecurity.jar` (no version suffix; see `archiveVersion = ""` in [build.gradle.kts](build.gradle.kts:36)).
- `./gradlew build` — compile + shadowJar. Tests are disabled (`tasks.test { enabled = false }`).
- Java 25 toolchain is required; Gradle will auto-provision it.
- Paper is `compileOnly` — runtime classes (`HikariCP`, `H2`, Cloud) are expected to be present on the Paper server classpath, so the shaded jar stays small. `password4j` is imported but **not declared** as a dependency in [build.gradle.kts](build.gradle.kts) — if compilation fails on a clean checkout, that is the cause.

## Runtime architecture

The entire flow hinges on Paper's configuration-phase API (`@SuppressWarnings("UnstableApiUsage")`):

1. **`AsyncPlayerConnectionConfigureEvent`** ([AuthSecurity.java:180](src/main/java/me/bedepay/AuthSecurity.java:180)) fires off-thread while the player is still in the config phase. Because the event handler runs async, the code **blocks on `session.future().join()`** to hold the player there until they submit the dialog. Do not move this logic to a sync event — it will deadlock the main thread.
2. A `PendingSession` record (login or register variant) is registered in the `pending` map keyed by UUID, with a `CompletableFuture<AuthResult>` that is completed by either the click handler or a 3-minute `completeOnTimeout`.
3. **`PlayerCustomClickEvent`** ([AuthSecurity.java:234](src/main/java/me/bedepay/AuthSecurity.java:234)) dispatches on three `Key` identifiers (`loginplugin:submit/login`, `loginplugin:submit/register`, `loginplugin:cancel`) and reads password fields from the `DialogResponseView`.
4. On success, the player's IP is stored in `trustedSessions` for `SESSION_TTL_HOURS = 1`, scheduled to expire via `getAsyncScheduler().runDelayed`. Subsequent reconnects from the same IP skip the dialog entirely ([AuthSecurity.java:189](src/main/java/me/bedepay/AuthSecurity.java:189)).

Key invariants to preserve when editing:

- **Never remove the `future().join()`** in `onConfigure` — it is what gates the player. If you need to await differently, you must still block that async event.
- **Dialog `canCloseWithEscape(false)`** — the UI must not be dismissible, otherwise an un-authenticated player proceeds into the world.
- `MAX_ATTEMPTS = 5` / `SESSION_TTL_HOURS = 1` / 3-minute timeout are the security knobs; changing them is fine but keep them as named constants.
- Argon2id parameters (`19456 KiB, 2 iter, 1 thread, 32-byte, 16-byte salt`, [AuthSecurity.java:57](src/main/java/me/bedepay/AuthSecurity.java:57)) must stay constant — changing them invalidates every stored hash.

## Persistence

Embedded H2 via HikariCP, DB file at `plugins/AuthSecurity/players.mv.db` (pool name `LoginPlugin-H2`, MySQL compatibility mode, `AUTO_SERVER=FALSE`). Single `accounts(uuid PK, username, hash)` table created in `initSchema()`. All DB access goes through `loadAccount` / `saveAccount` (a `MERGE INTO ... KEY(uuid)` upsert). If a fatal DB error occurs in `onEnable`, the server is shut down deliberately.

## Commands

A `PaperCommandManager` + Cloud `AnnotationParser` is wired up in `onEnable` ([AuthSecurity.java:94](src/main/java/me/bedepay/AuthSecurity.java:94)), but **no `@Command` methods exist yet**. Add them as annotated methods on the `AuthSecurity` class; the parser already scans `this.getClass()`.

## Plugin manifest

[paper-plugin.yml](src/main/resources/paper-plugin.yml) declares `folia-supported: true` and references a `bootstrapper`/`loader` (`io.canvasmc.testplugin.*`) that are **not present in this project**. If the plugin fails to load, either remove those lines or add the referenced classes.
