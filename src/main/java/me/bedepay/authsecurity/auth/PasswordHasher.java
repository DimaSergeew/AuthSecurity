package me.bedepay.authsecurity.auth;

import com.password4j.Argon2Function;
import com.password4j.HashingFunction;
import com.password4j.Password;
import com.password4j.types.Argon2;

/**
 * Pre-configured Argon2id hasher.
 *
 * <p>Changing these parameters invalidates every stored hash. Do not tune them in place —
 * introduce a migration strategy instead.
 */
public final class PasswordHasher {

    private static final HashingFunction ARGON2 =
            Argon2Function.getInstance(19456, 2, 1, 32, Argon2.ID, 16);

    private PasswordHasher() {}

    public static String hash(String password) {
        return Password.hash(password).with(ARGON2).getResult();
    }

    public static boolean verify(String password, String storedHash) {
        if (password == null || password.isBlank() || storedHash == null) return false;
        return Password.check(password, storedHash).with(ARGON2);
    }
}
