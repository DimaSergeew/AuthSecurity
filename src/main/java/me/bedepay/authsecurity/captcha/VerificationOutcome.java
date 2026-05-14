package me.bedepay.authsecurity.captcha;

/**
 * Outcome of a {@code /verify} call. Distinguishes real Cloudflare rejections
 * (which should count against rate-limit thresholds) from infrastructure errors
 * (network, HTTP, DB) which should not penalise the player.
 */
public enum VerificationOutcome {
    /** Cloudflare confirmed the response and the DB row was updated. */
    SUCCESS,
    /** Cloudflare returned {@code success:false} or the token exceeded its attempt cap. */
    REJECTED,
    /** Network, HTTP, or DB error reaching/processing the verification. Not the player's fault. */
    ERROR;

    public boolean isSuccess() { return this == SUCCESS; }
}
