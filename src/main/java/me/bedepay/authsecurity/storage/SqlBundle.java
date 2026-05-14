package me.bedepay.authsecurity.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads SQL statements from {@code resources/sql/<dialect>/*.sql} once at startup.
 */
public record SqlBundle(
        String schema,
        String loadByUuid,
        String findByUsername,
        String insert,
        String updateHash,
        String updateLastIp,
        String deleteByUuid,
        String insertCaptchaToken,
        String markCaptchaVerified,
        String deleteExpiredCaptcha,
        String touchCaptchaVerified,
        String updateTrustedIpLogin
) {
    public List<String> schemaStatements() {
        return Arrays.stream(schema.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    public static SqlBundle load(String dialect) {
        return new SqlBundle(
                read(dialect, "schema.sql"),
                read(dialect, "load_by_uuid.sql"),
                read(dialect, "find_by_username.sql"),
                read(dialect, "insert_account.sql"),
                read(dialect, "update_hash.sql"),
                read(dialect, "update_last_ip.sql"),
                read(dialect, "delete_by_uuid.sql"),
                read(dialect, "insert_captcha_token.sql"),
                read(dialect, "mark_captcha_verified.sql"),
                read(dialect, "delete_expired_captcha.sql"),
                read(dialect, "touch_captcha_verified.sql"),
                read(dialect, "update_trusted_ip_login.sql")
        );
    }

    private static String read(String dialect, String name) {
        String path = "/sql/" + dialect + "/" + name;
        try (InputStream in = SqlBundle.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing SQL resource: " + path);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n")).trim();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
