package me.bedepay.authsecurity.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * All user-facing strings as MiniMessage templates.
 *
 * <p>Fields that never carry runtime data are exposed as pre-parsed {@link Component}s.
 * Fields that interpolate values keep their raw MiniMessage source and are rendered on
 * demand through {@link #render(String, TagResolver...)}.
 */
public record Messages(
        Component loginTitle,
        String loginWelcome,
        Component loginButton,
        Component forgotPasswordButton,

        Component registerTitle,
        String registerWelcome,
        Component registerButton,
        Component cancelButton,

        Component passwordLabel,
        Component passwordConfirmLabel,

        String wrongPassword,
        Component tooManyAttempts,
        Component loginCancelled,
        Component loginTimedOut,
        Component internalError,

        Component passwordEmpty,
        String passwordTooShort,
        String passwordTooLong,
        Component passwordsMismatch,

        Component uuidMissing,
        String ipLimitReached,

        Component forgotPasswordTitle,
        Component forgotPasswordBody,
        Component forgotPasswordDiscordButton,
        Component forgotPasswordBackButton,

        Component changePasswordTitle,
        Component changePasswordBody,
        Component changePasswordOldLabel,
        Component changePasswordNewLabel,
        Component changePasswordConfirmLabel,
        Component changePasswordButton,
        Component changePasswordSuccess,
        Component changePasswordWrongOld,

        Component commandOnlyPlayers,
        Component commandNotAuthenticated,
        String commandPlayerNotFound,
        String commandUnregisterSuccess,
        String commandChangePasswordAdminSuccess,
        String commandAccountInfoHeader,
        String commandAccountInfoLine,
        Component commandNoPermission
) {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static Component parse(String template) {
        return MM.deserialize(template);
    }

    public static Component render(String template, TagResolver... resolvers) {
        return MM.deserialize(template, resolvers);
    }

    public Component wrongPassword(int remaining) {
        return render(wrongPassword, Placeholder.unparsed("remaining", Integer.toString(remaining)));
    }

    public Component passwordTooShort(int min) {
        return render(passwordTooShort, Placeholder.unparsed("min", Integer.toString(min)));
    }

    public Component passwordTooLong(int max) {
        return render(passwordTooLong, Placeholder.unparsed("max", Integer.toString(max)));
    }

    public Component loginWelcome(String username) {
        return render(loginWelcome, Placeholder.unparsed("username", username));
    }

    public Component registerWelcome(String username) {
        return render(registerWelcome, Placeholder.unparsed("username", username));
    }

    public Component ipLimitReached(int limit) {
        return render(ipLimitReached, Placeholder.unparsed("limit", Integer.toString(limit)));
    }

    public Component commandPlayerNotFound(String player) {
        return render(commandPlayerNotFound, Placeholder.unparsed("player", player));
    }

    public Component commandUnregisterSuccess(String player) {
        return render(commandUnregisterSuccess, Placeholder.unparsed("player", player));
    }

    public Component commandChangePasswordAdminSuccess(String player) {
        return render(commandChangePasswordAdminSuccess, Placeholder.unparsed("player", player));
    }

    public Component commandAccountInfoHeader(String player) {
        return render(commandAccountInfoHeader, Placeholder.unparsed("player", player));
    }

    public Component commandAccountInfoLine(String key, String value) {
        return render(commandAccountInfoLine,
                Placeholder.unparsed("key", key),
                Placeholder.unparsed("value", value));
    }
}
