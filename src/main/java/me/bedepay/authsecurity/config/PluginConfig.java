package me.bedepay.authsecurity.config;

public record PluginConfig(
        DatabaseConfig database,
        SecurityConfig security,
        SupportConfig support,
        CaptchaConfig captcha,
        Messages messages
) {
    public record DatabaseConfig(
            String type,
            H2Settings h2,
            MariaDbSettings mariadb,
            PoolSettings pool
    ) {
        public boolean isMariaDb() { return "mariadb".equalsIgnoreCase(type); }
    }

    public record H2Settings(String file, String options) {}

    public record MariaDbSettings(
            String host,
            int port,
            String database,
            String username,
            String password,
            String parameters
    ) {}

    public record PoolSettings(
            int maximumPoolSize,
            int minimumIdle,
            int connectionTimeoutMillis
    ) {}

    public record SecurityConfig(
            int maxAttempts,
            long sessionTtlMinutes,
            long loginTimeoutMinutes,
            int passwordMinLength,
            int passwordMaxLength,
            int accountsPerIpLimit,
            LockoutConfig lockout,
            IdleLogoutConfig idleLogout,
            PasswordPolicyConfig passwordPolicy
    ) {
        public boolean sessionTrustEnabled() { return sessionTtlMinutes > 0; }
    }

    public record LockoutConfig(
            boolean enabled,
            int maxAttempts,
            long banMinutes
    ) {}

    public record IdleLogoutConfig(
            boolean enabled,
            long minutes
    ) {}

    public record PasswordPolicyConfig(
            boolean requireLetterAndDigit
    ) {}

    public record SupportConfig(String discordUrl) {}

    public record CaptchaConfig(
            boolean enabled,
            String siteKey,
            String secretKey,
            String webBind,
            int webPort,
            String publicBaseUrl,
            int tokenTtlMinutes,
            int verificationValidityDays,
            int maxConcurrentChallenges,
            CaptchaWebTexts webTexts
    ) {}

    /**
     * User-facing strings rendered into the Turnstile widget HTML page.
     * Plain text (not MiniMessage). Multi-line values use real newlines.
     */
    public record CaptchaWebTexts(
            String lang,
            String title,
            String brand,
            String tagline,
            String heading,
            String intro,
            String hint,
            String footer,
            String statusVerifying,
            String statusVerified,
            String statusFailed,
            String statusNetwork,
            String statusWidgetError
    ) {}
}
