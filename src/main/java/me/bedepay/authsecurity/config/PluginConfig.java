package me.bedepay.authsecurity.config;

public record PluginConfig(
        DatabaseConfig database,
        SecurityConfig security,
        SupportConfig support,
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
            long sessionTtlHours,
            long loginTimeoutMinutes,
            int passwordMinLength,
            int passwordMaxLength,
            int accountsPerIpLimit
    ) {}

    public record SupportConfig(String discordUrl) {}
}
