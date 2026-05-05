package me.bedepay.authsecurity.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.bedepay.authsecurity.config.Messages;
import me.bedepay.authsecurity.config.PluginConfig;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public final class Dialogs {

    public static final Key KEY_SUBMIT_LOGIN     = Key.key("authsecurity", "submit/login");
    public static final Key KEY_SUBMIT_REGISTER  = Key.key("authsecurity", "submit/register");
    public static final Key KEY_SUBMIT_CHANGEPWD = Key.key("authsecurity", "submit/change-password");
    public static final Key KEY_CANCEL           = Key.key("authsecurity", "cancel");
    public static final Key KEY_FORGOT_BACK      = Key.key("authsecurity", "forgot-back");

    public static final String FIELD_PASSWORD         = "password";
    public static final String FIELD_PASSWORD_CONFIRM = "password_confirm";
    public static final String FIELD_PASSWORD_OLD     = "password_old";

    private final Messages m;
    private final PluginConfig.SupportConfig support;

    public Dialogs(Messages messages, PluginConfig.SupportConfig support) {
        this.m = messages;
        this.support = support;
    }

    public Dialog login(String username, Component error) {
        Dialog forgot = forgotPassword();
        List<ActionButton> buttons = List.of(
                customButton(m.loginButton(), KEY_SUBMIT_LOGIN),
                ActionButton.builder(m.forgotPasswordButton())
                        .action(DialogAction.staticAction(ClickEvent.showDialog(forgot)))
                        .build(),
                customButton(m.cancelButton(), KEY_CANCEL)
        );
        return Dialog.create(f -> f.empty()
                .base(DialogBase.builder(m.loginTitle())
                        .canCloseWithEscape(false)
                        .body(body(m.loginWelcome(username), error))
                        .inputs(List.of(textInput(FIELD_PASSWORD, m.passwordLabel())))
                        .build())
                .type(DialogType.multiAction(buttons).columns(buttons.size()).build()));
    }

    public Dialog register(String username, Component error) {
        List<ActionButton> buttons = List.of(
                customButton(m.registerButton(), KEY_SUBMIT_REGISTER),
                customButton(m.cancelButton(), KEY_CANCEL)
        );
        return Dialog.create(f -> f.empty()
                .base(DialogBase.builder(m.registerTitle())
                        .canCloseWithEscape(false)
                        .body(body(m.registerWelcome(username), error))
                        .inputs(List.of(
                                textInput(FIELD_PASSWORD, m.passwordLabel()),
                                textInput(FIELD_PASSWORD_CONFIRM, m.passwordConfirmLabel())))
                        .build())
                .type(DialogType.multiAction(buttons).columns(buttons.size()).build()));
    }

    /**
     * Inline recovery dialog, embedded in the login dialog via a static
     * {@link ClickEvent#showDialog(Dialog) show_dialog} click action.
     * The Discord button is also a static click action so the whole panel works client-side.
     * The "Back" button round-trips through the server so we can re-send the login dialog.
     */
    public Dialog forgotPassword() {
        List<ActionButton> buttons = new ArrayList<>();
        String discord = support.discordUrl();
        if (discord != null && !discord.isBlank()) {
            buttons.add(ActionButton.builder(m.forgotPasswordDiscordButton())
                    .action(DialogAction.staticAction(ClickEvent.openUrl(discord)))
                    .build());
        }
        buttons.add(customButton(m.forgotPasswordBackButton(), KEY_FORGOT_BACK));

        return Dialog.create(f -> f.empty()
                .base(DialogBase.builder(m.forgotPasswordTitle())
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(m.forgotPasswordBody(), 256)))
                        .build())
                .type(DialogType.multiAction(buttons).columns(buttons.size()).build()));
    }

    /**
     * Captcha gate dialog. Shown to new players (welcome=true) before registration,
     * and to returning players whose captcha verification has expired or whose IP
     * has changed (welcome=false).
     *
     * <p>The verification URL is exposed as an action button ({@code Open captcha}).
     * Clicking opens the page in the browser (after Minecraft's trust prompt) — the
     * dialog gets dismissed, but that's fine: when the player completes the Turnstile
     * challenge, {@code CaptchaService.markVerified} fires the registered push callback
     * which unblocks the configuration-phase gate, and the next dialog (login or
     * register) is sent automatically.
     *
     * <p>A Discord help button is shown when {@code support.discord-url} is configured,
     * mirroring the forgot-password flow. The Disconnect button always lets the player
     * bail out.
     */
    public Dialog captcha(String url, String username, boolean welcome, Component error) {
        Component body = welcome
                ? m.captchaWelcomeBody(username)
                : m.captchaRenewalBody(username);
        Component title = welcome ? m.captchaWelcomeTitle() : m.captchaRenewalTitle();

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(m.captchaButtonOpen())
                .action(DialogAction.staticAction(ClickEvent.openUrl(url)))
                .build());
        String discord = support.discordUrl();
        if (discord != null && !discord.isBlank()) {
            buttons.add(ActionButton.builder(m.captchaButtonDiscord())
                    .action(DialogAction.staticAction(ClickEvent.openUrl(discord)))
                    .build());
        }
        buttons.add(customButton(m.captchaButtonDisconnect(), KEY_CANCEL));

        return Dialog.create(f -> f.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(false)
                        .body(body(body, error))
                        .build())
                .type(DialogType.multiAction(buttons).columns(buttons.size()).build()));
    }

    public Dialog changePassword(Component error) {
        List<ActionButton> buttons = List.of(
                customButton(m.changePasswordButton(), KEY_SUBMIT_CHANGEPWD),
                customButton(m.cancelButton(), KEY_CANCEL)
        );
        return Dialog.create(f -> f.empty()
                .base(DialogBase.builder(m.changePasswordTitle())
                        .canCloseWithEscape(true)
                        .body(body(m.changePasswordBody(), error))
                        .inputs(List.of(
                                textInput(FIELD_PASSWORD_OLD, m.changePasswordOldLabel()),
                                textInput(FIELD_PASSWORD,     m.changePasswordNewLabel()),
                                textInput(FIELD_PASSWORD_CONFIRM, m.changePasswordConfirmLabel())))
                        .build())
                .type(DialogType.multiAction(buttons).columns(buttons.size()).build()));
    }

    private static List<DialogBody> body(Component main, Component error) {
        DialogBody first = DialogBody.plainMessage(main, 256);
        if (error == null) return List.of(first);
        return List.of(first, DialogBody.plainMessage(error, 256));
    }

    private static DialogInput textInput(String key, Component label) {
        return DialogInput.text(key, label).build();
    }

    private static ActionButton customButton(Component label, Key actionKey) {
        return ActionButton.builder(label)
                .action(DialogAction.customClick(actionKey, null))
                .build();
    }
}
