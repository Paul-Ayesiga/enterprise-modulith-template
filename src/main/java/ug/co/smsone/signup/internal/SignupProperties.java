package ug.co.smsone.signup.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signup knobs. {@code enabled} is the master switch (off = the enterprise, admin-provisioned
 * baseline; both endpoints answer 403 naming it). {@code verifyUrl} is the link the verification
 * email carries — point it at a front-end page in production; the API's own GET works out of the box.
 * {@code resendCooldown} is the anti-abuse knob: how long a pending request suppresses another
 * verification email to the same address.
 */
@ConfigurationProperties(prefix = "app.signup")
record SignupProperties(Boolean enabled, Duration tokenTtl, String verifyUrl, Duration resendCooldown) {

    SignupProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            tokenTtl = Duration.ofHours(24);
        }
        // Zero is a legitimate choice (disable the cooldown); only null/negative fall back.
        if (resendCooldown == null || resendCooldown.isNegative()) {
            resendCooldown = Duration.ofMinutes(5);
        }
        if (verifyUrl == null || verifyUrl.isBlank()) {
            verifyUrl = "http://localhost:28080/api/v1/signup/verify";
        }
    }
}
