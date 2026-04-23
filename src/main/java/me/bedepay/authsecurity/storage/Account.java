package me.bedepay.authsecurity.storage;

import java.sql.Timestamp;
import java.util.UUID;

public record Account(
        UUID uuid,
        String username,
        String hash,
        String lastIp,
        Timestamp createdAt,
        Timestamp updatedAt
) {}
