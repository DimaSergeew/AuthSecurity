package me.bedepay.authsecurity.captcha;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import me.bedepay.authsecurity.config.PluginConfig;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded HTTP server that hosts the Turnstile widget page and the verify endpoint.
 * Runs on a dedicated background thread so it never touches the main server thread.
 */
public final class CaptchaWebServer {

    private static final Pattern TOKEN_REGEX = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final Plugin plugin;
    private final CaptchaService captcha;
    private final String htmlTemplate;
    private Javalin app;

    public CaptchaWebServer(Plugin plugin, CaptchaService captcha) {
        this.plugin = plugin;
        this.captcha = captcha;
        this.htmlTemplate = loadTemplate();
    }

    public void start() {
        String bind = captcha.config().webBind();
        if (bind == null || bind.isBlank()) bind = "0.0.0.0";
        final String bindHost = bind;
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.useVirtualThreads = false;
            cfg.jetty.defaultHost = bindHost;
            cfg.requestLogger.http((ctx, ms) -> {
                // Captcha tokens appear in the URL path. Redact them before logging so the
                // server log doesn't become a list of valid tokens any log-shipper can mine
                // within token-ttl-minutes.
                String path = ctx.path();
                if (path.startsWith("/c/")) path = "/c/[redacted]";
                plugin.getSLF4JLogger().info(
                        "captcha-web {} \"{} {}\" {} {}ms",
                        ctx.ip(), ctx.method(), path, ctx.status().getCode(), ms.longValue());
            });
        });
        app.get("/", ctx -> ctx.result("AuthSecurity captcha gate OK"));
        app.get("/c/{token}", this::serveWidget);
        app.post("/verify", this::handleVerify);
        app.start(captcha.config().webPort());
        plugin.getSLF4JLogger().info("Captcha web server listening on {}:{}", bindHost, captcha.config().webPort());
    }

    public void stop() {
        if (app != null) {
            app.stop();
            app = null;
        }
    }

    private void serveWidget(Context ctx) {
        String token = ctx.pathParam("token");
        if (!TOKEN_REGEX.matcher(token).matches()) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Invalid token");
            return;
        }
        String siteKey = captcha.config().siteKey();
        PluginConfig.CaptchaWebTexts t = captcha.config().webTexts();
        String html = htmlTemplate
                .replace("{{siteKey}}",   escapeAttr(siteKey))
                .replace("{{token}}",     escapeAttr(token))
                .replace("{{lang}}",      escapeAttr(t.lang()))
                .replace("{{title}}",     escapeHtml(t.title()))
                .replace("{{brand}}",     escapeHtml(t.brand()))
                .replace("{{tagline}}",   escapeHtml(t.tagline()))
                .replace("{{heading}}",   escapeHtml(t.heading()))
                .replace("{{introHtml}}", escapeHtmlMultiline(t.intro()))
                .replace("{{hint}}",      escapeHtml(t.hint()))
                .replace("{{footer}}",    escapeHtml(t.footer()))
                .replace("{{i18nJson}}",  buildI18nJson(t));
        // no-store: prevents browser back/forward replay of solved tokens.
        // no-referrer: keeps /c/{token} out of Referer when the user clicks an
        // outbound link (Discord support button, etc.).
        ctx.header("Cache-Control", "no-store")
           .header("Referrer-Policy", "no-referrer")
           .contentType("text/html; charset=utf-8")
           .result(html);
    }

    private void handleVerify(Context ctx) {
        String body = ctx.body();
        String token = jsonField(body, "token");
        String cfResponse = jsonField(body, "cfResponse");
        if (token == null || cfResponse == null) {
            ctx.status(HttpStatus.BAD_REQUEST).result("missing fields");
            return;
        }
        if (!TOKEN_REGEX.matcher(token).matches()) {
            ctx.status(HttpStatus.BAD_REQUEST).result("bad token");
            return;
        }
        // Async: releases the Jetty worker thread while we wait on Cloudflare's siteverify
        // (up to 10s). Without this, a flood of /verify with junk responses would saturate
        // the worker pool and starve legitimate requests.
        ctx.future(() -> captcha.markVerified(token, cfResponse).thenAccept(ok -> {
            if (ok) {
                ctx.status(HttpStatus.OK).result("ok");
            } else {
                ctx.status(HttpStatus.BAD_REQUEST).result("verification failed");
            }
        }));
    }

    private static String jsonField(String body, String name) {
        if (body == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(body);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String escapeAttr(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** Like escapeHtml, but converts real newlines into &lt;br&gt; for use in &lt;p&gt; bodies. */
    private static String escapeHtmlMultiline(String value) {
        return escapeHtml(value).replace("\n", "<br>");
    }

    private static String buildI18nJson(PluginConfig.CaptchaWebTexts t) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"verifying\":").append(jsonString(t.statusVerifying())).append(',');
        sb.append("\"verified\":").append(jsonString(t.statusVerified())).append(',');
        sb.append("\"failed\":").append(jsonString(t.statusFailed())).append(',');
        sb.append("\"network\":").append(jsonString(t.statusNetwork())).append(',');
        sb.append("\"widgetError\":").append(jsonString(t.statusWidgetError()));
        sb.append('}');
        return sb.toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"'  -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<'  -> out.append("\\u003c");
                case '>'  -> out.append("\\u003e");
                case '&'  -> out.append("\\u0026");
                default   -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private String loadTemplate() {
        try (InputStream in = getClass().getResourceAsStream("/web/captcha.html")) {
            if (in == null) throw new IllegalStateException("/web/captcha.html missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load captcha.html", e);
        }
    }
}
