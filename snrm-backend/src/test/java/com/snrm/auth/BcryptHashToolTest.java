package com.snrm.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prints a BCrypt hash for {@code SNRM_AUTH_PASSWORD_HASH}.
 *
 * <p>A test rather than a {@code main} class so it needs no extra plugin and no packaging change —
 * {@code spring-security-crypto} is already on the classpath, and the surefire run is a
 * ready-made way to execute one class:
 *
 * <pre>{@code
 * mvnw.cmd test -Dtest=BcryptHashToolTest -Dsnrm.password=your-password -DfailIfNoTests=false
 * }</pre>
 *
 * <p>Without {@code -Dsnrm.password} it skips, so an ordinary {@code mvnw test} is unaffected. The
 * hash goes to stdout; set it as the environment variable and restart the application. Nothing is
 * written to disk, and the password itself is never persisted anywhere.
 */
class BcryptHashToolTest {

    private static final String PASSWORD_PROPERTY = "snrm.password";

    @Test
    void printBcryptHashForConfiguredPassword() {
        String rawPassword = System.getProperty(PASSWORD_PROPERTY);
        assumeTrue(rawPassword != null && !rawPassword.isBlank(),
                "Set -D" + PASSWORD_PROPERTY + "=<password> to generate a hash.");

        String hash = new BCryptPasswordEncoder().encode(rawPassword);

        // Verified before printing: a hash that does not match its own password would be worse
        // than no hash at all, because it fails only at the first login attempt.
        assertTrue(new BCryptPasswordEncoder().matches(rawPassword, hash),
                "Generated hash did not verify against the password it was generated from.");

        System.out.println();
        System.out.println("================================================================");
        System.out.println("SNRM_AUTH_PASSWORD_HASH=" + hash);
        System.out.println();
        System.out.println("PowerShell (this session):");
        System.out.println("  $env:SNRM_AUTH_PASSWORD_HASH = '" + hash + "'");
        System.out.println("================================================================");
        System.out.println();
    }
}
