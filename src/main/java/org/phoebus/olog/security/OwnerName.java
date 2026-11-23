package org.phoebus.olog.security;

import org.springframework.security.core.Authentication;

import java.security.Principal;

/**
 * Human readable name recorded as owner of log entries and templates.
 * <p>
 * With OAuth2 the principal name is the claim configured by {@code oauth2.claimsName}
 * (e.g. the INFN UUID in {@code preferred_username}), which logbook owners and members
 * are matched against. That id is not readable, so the JWT provider also stores the
 * display name claim ({@code oauth2.displayNameClaim}) in the authentication details as a
 * {@link DisplayName}; entries and templates are owned by that name. Logbook ownership and
 * permission checks keep using {@link Principal#getName()}.
 */
public final class OwnerName {

    /** Display name of an authenticated JWT user, kept in the authentication details. */
    public record DisplayName(String value) {
    }

    private OwnerName() {
    }

    /**
     * @param principal authenticated user
     * @return the display name from the JWT, or the principal name when there is none
     */
    public static String of(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getDetails() instanceof DisplayName displayName
                && displayName.value() != null
                && !displayName.value().isBlank()) {
            return displayName.value();
        }
        return principal.getName();
    }
}
