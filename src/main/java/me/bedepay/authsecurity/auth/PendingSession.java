package me.bedepay.authsecurity.auth;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State for a player currently stuck in the configuration phase.
 * {@code hash} is {@code null} for players who have not yet registered.
 */
public record PendingSession(
        CompletableFuture<AuthResult> future,
        String hash,
        String username,
        String ip,
        AtomicInteger attempts
) {
    public static PendingSession forLogin(String username, String hash, String ip) {
        return new PendingSession(new CompletableFuture<>(), hash, username, ip, new AtomicInteger(0));
    }

    public static PendingSession forRegister(String username, String ip) {
        return new PendingSession(new CompletableFuture<>(), null, username, ip, new AtomicInteger(0));
    }

    public boolean isRegister() { return hash == null; }
}
