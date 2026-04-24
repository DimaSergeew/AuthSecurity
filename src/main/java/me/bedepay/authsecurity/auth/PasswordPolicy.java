package me.bedepay.authsecurity.auth;

import me.bedepay.authsecurity.config.Messages;
import me.bedepay.authsecurity.config.PluginConfig;
import net.kyori.adventure.text.Component;

/**
 * Shared password validation used by the register flow and change-password flow.
 * Returns {@code null} when the password passes all rules.
 */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static Component validate(String password,
                                     String confirm,
                                     PluginConfig.SecurityConfig security,
                                     Messages messages) {
        if (password == null || password.isBlank()) return messages.passwordEmpty();
        if (password.length() < security.passwordMinLength()) return messages.passwordTooShort(security.passwordMinLength());
        if (password.length() > security.passwordMaxLength()) return messages.passwordTooLong(security.passwordMaxLength());
        if (!password.equals(confirm)) return messages.passwordsMismatch();
        if (security.passwordPolicy().requireLetterAndDigit() && !hasLetterAndDigit(password)) {
            return messages.passwordRequiresAlphanumeric();
        }
        return null;
    }

    private static boolean hasLetterAndDigit(String s) {
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) letter = true;
            else if (Character.isDigit(c)) digit = true;
            if (letter && digit) return true;
        }
        return false;
    }
}
