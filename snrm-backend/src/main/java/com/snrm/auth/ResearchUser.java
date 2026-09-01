package com.snrm.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * The one account the Phase 1 prototype authenticates.
 *
 * <p>No {@code UserDetailsService} and no user table: the schema has no user entity, and a
 * {@code UserDetailsService} would only wrap a single configured credential in indirection. This
 * class is the whole user store — {@link #matches(String, String)} is what
 * {@link AuthController} calls before a token is issued.
 *
 * <p><strong>Startup-generated password.</strong> When {@code snrm.auth.password-hash} is blank the
 * constructor invents a password, hashes it, and logs it — the same affordance Spring Boot's
 * default user provides. It means a fresh clone can be exercised in Swagger before any environment
 * variable is set, and it means no BCrypt hash of a known password has to be committed to this
 * repository. The password changes on every restart, which is why the log entry is a warning.
 *
 * <p>The hash is verified to look like BCrypt at startup rather than at the first login: a typo in
 * {@code SNRM_AUTH_PASSWORD_HASH} should stop the application, not silently reject every password.
 */
@Component
public class ResearchUser {

    private static final Logger log = LoggerFactory.getLogger(ResearchUser.class);

    /** Prefixes emitted by BCrypt; anything else in the property is a configuration mistake. */
    private static final String[] BCRYPT_PREFIXES = {"$2a$", "$2b$", "$2y$"};

    private final String username;
    private final String passwordHash;
    private final PasswordEncoder passwordEncoder;

    ResearchUser(AuthProperties properties, PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.username = properties.username();
        this.passwordHash = StringUtils.hasText(properties.passwordHash())
                ? validated(properties.passwordHash().trim())
                : generateThrowawayPassword();
    }

    /** Login name of the research user; the {@code sub} claim of the tokens issued to them. */
    public String username() {
        return username;
    }

    /**
     * Whether these credentials are the research user's.
     *
     * <p>The BCrypt comparison runs even when the username is already wrong, so a caller cannot
     * learn the username from how long the request took.
     */
    public boolean matches(String candidateUsername, String candidatePassword) {
        boolean passwordOk = passwordEncoder.matches(candidatePassword, passwordHash);
        return username.equals(candidateUsername) && passwordOk;
    }

    private static String validated(String hash) {
        for (String prefix : BCRYPT_PREFIXES) {
            if (hash.startsWith(prefix)) {
                return hash;
            }
        }
        throw new IllegalStateException("""
                snrm.auth.password-hash (environment variable SNRM_AUTH_PASSWORD_HASH) is not a \
                BCrypt hash — it must start with $2a$, $2b$ or $2y$, and must not carry Spring \
                Security's {bcrypt} prefix. Generate one with:
                  mvnw.cmd test -Dtest=BcryptHashToolTest -Dsnrm.password=your-password -DfailIfNoTests=false""");
    }

    private String generateThrowawayPassword() {
        String generated = UUID.randomUUID().toString();
        log.warn("""

                        ================================================================
                        No snrm.auth.password-hash configured — using a generated password
                        for the research user '{}':

                            {}

                        It changes on every restart. Set SNRM_AUTH_PASSWORD_HASH to a
                        BCrypt hash for a stable credential:
                          mvnw.cmd test -Dtest=BcryptHashToolTest -Dsnrm.password=... -DfailIfNoTests=false
                        ================================================================""",
                username, generated);
        return passwordEncoder.encode(generated);
    }
}
