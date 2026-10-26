package org.phoebus.olog;

import java.security.Principal;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.olog.entity.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperimentLogbookTest {
    static final String PATH = "i:infn:lnf:csn1:atlas";
    LogbookRepository books;
    LogbookMemberRepository members;
    LogbookAccessService access;
    Logbook book;
    org.phoebus.olog.identity.api.IdentityApi identity;

    @BeforeEach void setup() {
        books = mock(LogbookRepository.class);
        members = mock(LogbookMemberRepository.class);
        book = new Logbook("atlas", "owner");
        book.setOfficialExperiment(true);
        book.setExperimentLdapPath(PATH);
        when(books.findById("atlas")).thenReturn(Optional.of(book));
        when(books.findAll()).thenReturn(List.of(book));
        when(members.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var factory = mock(org.phoebus.olog.identity.InfnIdentityClientFactory.class);
        identity = mock(org.phoebus.olog.identity.api.IdentityApi.class);
        when(factory.create("header.payload.signature")).thenReturn(identity);
        access = new LogbookAccessService(books, members, new org.phoebus.olog.identity.ExperimentMembershipService(factory));
    }

    Principal user(String role) {
        when(identity.getAuthorizationForUUID("scientist")).thenReturn(
                new org.phoebus.olog.identity.model.ApiResultResponseAuthorization().errorCode(0).payload(
                        new org.phoebus.olog.identity.model.Authorization().isMemberOf(List.of(role))));
        return new UsernamePasswordAuthenticationToken("scientist", "header.payload.signature", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    Log entry() {
        return Log.LogBuilder.createLog().id(1L).withLogbooks(Set.of(book))
                .createDate(Instant.parse("2000-01-01T00:00:00Z")).build();
    }

    @Test void experimentMembershipGrantsOnlyReadWithoutIndividualAcl() {
        for (String authority : List.of(PATH + "::aff", PATH + "::aff:tng", PATH + "::aff:other:role")) {
            Principal scientist = user(authority);
            assertEquals(Set.of(LogbookPermission.READ), access.permissionsFor("atlas", scientist).permissions());
            assertFalse(access.permissionsFor("atlas", scientist).admin());
            assertEquals(1, access.permissionsForCurrentUser(scientist).size());
            assertDoesNotThrow(() -> access.requireReadable(entry(), scientist));
            assertTrue(access.readFilter(scientist).bool().should().get(0).isNested());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> access.requirePermission(List.of("atlas"), LogbookPermission.WRITE, scientist)).getStatus());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> access.addMember("atlas", "someone", Set.of(LogbookPermission.WRITE), scientist)).getStatus());
        }
    }

    @Test void ownerCanGrantAndRevokeIndividualWriteWhileGroupReadRemains() {
        Principal scientist = user(PATH + "::aff:tng");
        LogbookMember writer = access.addMember("atlas", "scientist", Set.of(LogbookPermission.WRITE), () -> "owner");
        when(members.find("atlas", "scientist")).thenReturn(Optional.of(writer));
        access.requirePermission(List.of("atlas"), LogbookPermission.WRITE, scientist);
        assertEquals(Set.of(LogbookPermission.READ, LogbookPermission.WRITE), access.permissionsFor("atlas", scientist).permissions());
        writer.setVisibleFrom("2026-09-01");
        assertDoesNotThrow(() -> access.requireReadable(entry(), scientist));
        assertTrue(access.readFilter(scientist).bool().should().get(0).isNested());
        access.removeMember("atlas", "scientist", () -> "owner");
        verify(members).delete("atlas", "scientist");
        when(members.find("atlas", "scientist")).thenReturn(Optional.empty());
        assertEquals(Set.of(LogbookPermission.READ), access.permissionsFor("atlas", scientist).permissions());
    }

    @Test void unrelatedGroupsAndNonOfficialBooksDoNotGrantRead() {
        for (String authority : List.of("ROLE_ADMIN", PATH, PATH + "2::aff:tng", PATH + "::affiliation:tng", PATH + "::resp:local", "i:infn:lnf:csn1:cms::aff:tng")) {
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> access.requireReadable(entry(), user(authority))).getStatus());
            assertTrue(access.readFilter(user(authority)).isMatchNone());
        }
        book.setOfficialExperiment(false);
        assertTrue(access.permissionsFor("atlas", user(PATH)).permissions().isEmpty());
    }

    @Test void anonymousAndUnauthenticatedTokensAreRejected() {
        List<Principal> anonymous = Arrays.asList(null,
                new AnonymousAuthenticationToken("key", "anonymousUser", List.of(new SimpleGrantedAuthority(PATH))),
                new UsernamePasswordAuthenticationToken("scientist", ""));
        for (Principal principal : anonymous) {
            assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                    () -> access.requireReadable(entry(), principal)).getStatus());
            assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                    () -> access.requireAdmin("atlas", principal)).getStatus());
        }
    }

    @Test void listingCombinesExplicitAndExperimentGrantsWithoutDuplicates() {
        Principal scientist = user(PATH + "::aff:tng");
        Logbook explicit = new Logbook("personal", "owner");
        Logbook unrelated = new Logbook("other-experiment", "owner");
        unrelated.setOfficialExperiment(true);
        unrelated.setExperimentLdapPath("i:infn:lnf:csn1:cms");
        when(books.findAll()).thenReturn(List.of(book, explicit, unrelated));
        when(books.findById("personal")).thenReturn(Optional.of(explicit));
        when(books.findById("other-experiment")).thenReturn(Optional.of(unrelated));
        when(members.find("personal", "scientist")).thenReturn(Optional.of(new LogbookMember(
                "personal", "scientist", Set.of(LogbookPermission.READ, LogbookPermission.WRITE), "owner", "now", "now")));
        when(members.find("atlas", "scientist")).thenReturn(Optional.of(new LogbookMember(
                "atlas", "scientist", Set.of(LogbookPermission.WRITE), "owner", "now", "now")));
        var result = access.permissionsForCurrentUser(scientist);
        assertEquals(List.of("atlas", "personal"), result.stream().map(LogbookPermissions::logbook).toList());
        assertEquals(Set.of(LogbookPermission.READ, LogbookPermission.WRITE), result.get(0).permissions());
    }

    @Test void identityOutagePreservesExplicitPermissions() {
        Principal scientist = user(PATH + "::aff:tng");
        when(identity.getAuthorizationForUUID("scientist")).thenThrow(new org.springframework.web.client.RestClientException("unavailable"));
        when(members.find("atlas", "scientist")).thenReturn(Optional.of(new LogbookMember(
                "atlas", "scientist", Set.of(LogbookPermission.READ, LogbookPermission.WRITE), "owner", "now", "now")));
        assertEquals(Set.of(LogbookPermission.READ, LogbookPermission.WRITE), access.permissionsFor("atlas", scientist).permissions());
        assertDoesNotThrow(() -> access.requireReadable(entry(), scientist));
    }

    @Test void authenticationAuthoritiesAloneDoNotGrantExperimentRead() {
        Principal scientist = new UsernamePasswordAuthenticationToken("scientist", null,
                List.of(new SimpleGrantedAuthority(PATH + "::aff:tng")));
        assertTrue(access.permissionsFor("atlas", scientist).permissions().isEmpty());
        verifyNoInteractions(identity);
    }

    @Test void membershipAdministratorCanGrantWrite() {
        when(members.find("atlas", "admin")).thenReturn(Optional.of(new LogbookMember("atlas", "admin",
                Set.of(LogbookPermission.ADMIN), "owner", "now", "now")));
        assertDoesNotThrow(() -> access.addMember("atlas", "scientist", Set.of(LogbookPermission.WRITE), () -> "admin"));
    }

    @Test void fieldsRoundTripAndLegacyDocumentsRemainNonOfficial() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Logbook persisted = mapper.readValue(mapper.writeValueAsString(book), Logbook.class);
        assertTrue(persisted.isOfficialExperiment());
        assertEquals(PATH, persisted.getExperimentLdapPath());
        Logbook legacy = mapper.readValue("{\"name\":\"legacy\",\"owner\":\"owner\"}", Logbook.class);
        assertFalse(legacy.isOfficialExperiment());
        assertNull(legacy.getExperimentLdapPath());
    }
}
