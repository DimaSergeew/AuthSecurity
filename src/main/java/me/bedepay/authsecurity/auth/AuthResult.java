package me.bedepay.authsecurity.auth;

import net.kyori.adventure.text.Component;

public record AuthResult(boolean ok, Component disconnectReason) {
    public static AuthResult allowed() { return new AuthResult(true, null); }
    public static AuthResult denied(Component reason) { return new AuthResult(false, reason); }
}
