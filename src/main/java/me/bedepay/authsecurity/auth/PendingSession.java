package me.bedepay.authsecurity.auth;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State for a player currently stuck in the configuration phase.
 *
 * <p>{@code hash} is {@code null} for register flow.
 * {@code captchaToken} is non-null when the player is parked on the captcha gate;
 * once captcha is passed the session is replaced with a fresh login/register session.
 */
public record PendingSession(
        CompletableFuture<AuthResult> future,
        String hash,
        String username,
        String ip,
        AtomicInteger attempts,
        String captchaToken,
        long createdAtMillis
) {
    public static PendingSession forLogin(String username, String hash, String ip) {
        return new PendingSession(new CompletableFuture<>(), hash, username, ip,
                new AtomicInteger(0), null, System.currentTimeMillis());
    }

    public static PendingSession forRegister(String username, String ip) {
        return new PendingSession(new CompletableFuture<>(), null, username, ip,
                new AtomicInteger(0), null, System.currentTimeMillis());
    }

    public static PendingSession forCaptcha(String username, String ip, String token) {
        return new PendingSession(new CompletableFuture<>(), null, username, ip,
                new AtomicInteger(0), token, System.currentTimeMillis());
    }

    public boolean isRegister() { return hash == null && captchaToken == null; }
}
