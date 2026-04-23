package me.bedepay.authsecurity.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.bedepay.authsecurity.config.PluginConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class HikariAccountRepository implements AccountRepository {

    private final HikariDataSource pool;
    private final SqlBundle sql;

    private HikariAccountRepository(HikariDataSource pool, SqlBundle sql) {
        this.pool = pool;
        this.sql = sql;
    }

    public static HikariAccountRepository create(PluginConfig.DatabaseConfig db, File dataFolder) {
        HikariConfig cfg = new HikariConfig();
        String dialect;

        if (db.isMariaDb()) {
            dialect = "mariadb";
            PluginConfig.MariaDbSettings m = db.mariadb();
            String params = m.parameters() == null || m.parameters().isBlank() ? "" : "?" + m.parameters();
            cfg.setJdbcUrl("jdbc:mariadb://" + m.host() + ":" + m.port() + "/" + m.database() + params);
            cfg.setUsername(m.username());
            cfg.setPassword(m.password());
            cfg.setDriverClassName("org.mariadb.jdbc.Driver");
            cfg.setPoolName("AuthSecurity-MariaDB");
        } else {
            dialect = "h2";
            PluginConfig.H2Settings h2 = db.h2();
            dataFolder.mkdirs();
            String dbPath = new File(dataFolder, h2.file()).getAbsolutePath();
            String opts = h2.options() == null || h2.options().isBlank() ? "" : ";" + h2.options();
            cfg.setJdbcUrl("jdbc:h2:" + dbPath + opts);
            cfg.setUsername("sa");
            cfg.setPassword("");
            cfg.setDriverClassName("org.h2.Driver");
            cfg.setPoolName("AuthSecurity-H2");
        }

        cfg.setMaximumPoolSize(db.pool().maximumPoolSize());
        cfg.setMinimumIdle(db.pool().minimumIdle());
        cfg.setConnectionTimeout(db.pool().connectionTimeoutMillis());

        HikariDataSource ds = new HikariDataSource(cfg);
        return new HikariAccountRepository(ds, SqlBundle.load(dialect));
    }

    @Override
    public void initSchema() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql.schema());
        }
    }

    @Override
    public Account findByUuid(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.loadByUuid())) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    @Override
    public Account findByUsername(String username) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.findByUsername())) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    @Override
    public void upsert(UUID uuid, String username, String hash, String lastIp) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.upsert())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, hash);
            ps.setString(4, lastIp);
            ps.executeUpdate();
        }
    }

    @Override
    public void updateHash(UUID uuid, String hash) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.updateHash())) {
            ps.setString(1, hash);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    @Override
    public void updateLastIp(UUID uuid, String lastIp) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.updateLastIp())) {
            ps.setString(1, lastIp);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    @Override
    public boolean delete(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.deleteByUuid())) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    private static Account map(ResultSet rs) throws SQLException {
        return new Account(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getString("hash"),
                rs.getString("last_ip"),
                rs.getTimestamp("created_at"),
                rs.getTimestamp("updated_at")
        );
    }

    @Override
    public void close() {
        if (!pool.isClosed()) pool.close();
    }
}
