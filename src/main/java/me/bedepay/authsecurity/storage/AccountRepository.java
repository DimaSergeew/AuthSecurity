package me.bedepay.authsecurity.storage;

import java.sql.SQLException;
import java.util.UUID;

public interface AccountRepository extends AutoCloseable {

    void initSchema() throws SQLException;

    Account findByUuid(UUID uuid) throws SQLException;

    Account findByUsername(String username) throws SQLException;

    void upsert(UUID uuid, String username, String hash, String lastIp) throws SQLException;

    void updateHash(UUID uuid, String hash) throws SQLException;

    void updateLastIp(UUID uuid, String lastIp) throws SQLException;

    boolean delete(UUID uuid) throws SQLException;

    @Override
    void close();
}
