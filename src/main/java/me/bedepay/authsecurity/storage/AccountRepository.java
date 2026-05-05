package me.bedepay.authsecurity.storage;

import java.sql.SQLException;
import java.util.UUID;

public interface AccountRepository extends AutoCloseable {

    void initSchema() throws SQLException;

    Account findByUuid(UUID uuid) throws SQLException;

    Account findByUsername(String username) throws SQLException;

    void insert(UUID uuid, String username, String hash, String lastIp) throws SQLException;

    void updateHash(UUID uuid, String hash) throws SQLException;

    void updateLastIp(UUID uuid, String lastIp) throws SQLException;

    boolean delete(UUID uuid) throws SQLException;

    void insertCaptchaToken(String token, UUID uuid, String username, String ip, long ttlSeconds) throws SQLException;

    boolean isCaptchaVerified(String token) throws SQLException;

    /**
     * Marks the captcha token verified, but only if the IP that solved it matches the IP
     * that requested it (or if no IP was recorded at issue time). Returns {@code true} on a
     * successful update; {@code false} if the token is missing, expired, or comes from a
     * different IP than the one that requested it.
     */
    boolean markCaptchaVerified(String token, String clientIp) throws SQLException;

    int deleteExpiredCaptchaTokens() throws SQLException;

    void touchCaptchaVerifiedAt(UUID uuid, String ip) throws SQLException;

    void updateTrustedIpLoginEnabled(UUID uuid, boolean enabled) throws SQLException;

    @Override
    void close();
}
