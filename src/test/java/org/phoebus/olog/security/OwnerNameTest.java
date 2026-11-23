package org.phoebus.olog.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OwnerNameTest {

    private static final String UUID = "f6a87c4d-9a4d-40f8-bcc2-472cd8dcf167";

    private static UsernamePasswordAuthenticationToken jwtUser(Object details) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(UUID, "jwt", List.of());
        token.setDetails(details);
        return token;
    }

    @Test
    public void displayNameIsUsedWhenPresent() {
        assertEquals("Mario Rossi", OwnerName.of(jwtUser(new OwnerName.DisplayName("Mario Rossi"))));
    }

    @Test
    public void fallsBackToPrincipalNameWithoutDisplayName() {
        assertEquals(UUID, OwnerName.of(jwtUser(new OwnerName.DisplayName(null))));
        assertEquals(UUID, OwnerName.of(jwtUser(new OwnerName.DisplayName("  "))));
        assertEquals(UUID, OwnerName.of(jwtUser(null)));
    }

    @Test
    public void nonJwtPrincipalKeepsItsName() {
        Principal basic = () -> "epics";
        assertEquals("epics", OwnerName.of(basic));
    }
}
