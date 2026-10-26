package org.phoebus.olog;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.phoebus.olog.entity.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageResourceTest {
    StorageQuotaRepository quotas;
    StorageUsageRepository usage;
    StorageService service;
    MockMvc mvc;
    Principal owner = () -> "owner";
    UsernamePasswordAuthenticationToken admin;
    StorageQuota original;
    org.phoebus.olog.identity.api.IdentityApi identity;

    @BeforeEach void setup() {
        quotas = mock(StorageQuotaRepository.class);
        usage = mock(StorageUsageRepository.class);
        var factory = mock(org.phoebus.olog.identity.InfnIdentityClientFactory.class);
        identity = mock(org.phoebus.olog.identity.api.IdentityApi.class);
        when(factory.create("header.payload.signature")).thenReturn(identity);
        var auth = new OlogAdministratorService(new org.phoebus.olog.identity.ExperimentMembershipService(factory));
        when(identity.getAuthorizationForUUID("olog-admin")).thenReturn(
                new org.phoebus.olog.identity.model.ApiResultResponseAuthorization().errorCode(0).payload(
                        new org.phoebus.olog.identity.model.Authorization().isMemberOf(List.of(OlogAdministratorService.ADMIN_ENTITLEMENT))));
        when(identity.getAuthorizationForUUID("owner")).thenReturn(
                new org.phoebus.olog.identity.model.ApiResultResponseAuthorization().errorCode(0).payload(
                        new org.phoebus.olog.identity.model.Authorization()));
        service = new StorageService(usage, quotas, auth, mock(LogbookAccessService.class));
        original = new StorageQuota("ATLAS", "owner", 1_000_000_000L, null);
        when(quotas.find("ATLAS")).thenReturn(new StorageQuotaRepository.Snapshot(original, null, null));
        when(quotas.save(any(), any())).thenAnswer(i -> i.getArgument(1));
        when(usage.forOwner("owner")).thenReturn(new StorageUsage(123,
                List.of(new StorageUsage.LogbookUsage("ATLAS", 123, 2, 1))));
        admin = new UsernamePasswordAuthenticationToken("olog-admin", "header.payload.signature", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc = MockMvcBuilders.standaloneSetup(new StorageResource(service)).build();
    }

    @Test void ownUsageIncludesOneGbAndLogbookBreakdown() throws Exception {
        mvc.perform(get("/Olog/storage/me").principal(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.logbooks[0].quotaBytes").value(1_000_000_000))
                .andExpect(jsonPath("$.usedBytes").value(123))
                .andExpect(jsonPath("$.measurement").value("JSON_SOURCE_UTF8"))
                .andExpect(jsonPath("$.logbooks[0].logbook").value("ATLAS"))
                .andExpect(jsonPath("$.administrator").value(false));
    }

    @Test void permissionLookupUsesInfnEntitlementWithoutScanningStorage() throws Exception {
        mvc.perform(get("/Olog/storage/permissions/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/Olog/storage/permissions/me").principal(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.administrator").value(false));
        for (String role : List.of("ROLE_USER", "i:infn:lnf:csn1:atlas::aff:tng", "ADMIN", "ROLE_ADMIN", "ROLE_OLOG-ADMINS", OlogAdministratorService.ADMIN_ENTITLEMENT)) {
            var user = new UsernamePasswordAuthenticationToken("owner", "header.payload.signature", List.of(new SimpleGrantedAuthority(role)));
            mvc.perform(get("/Olog/storage/permissions/me").principal(user)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.administrator").value(false));
        }
        mvc.perform(get("/Olog/storage/permissions/me").principal(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.administrator").value(true));
        verifyNoInteractions(usage, quotas);
    }

    @Test void anonymousAccessIsUnauthorized() throws Exception {
        mvc.perform(get("/Olog/storage/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/Olog/storage/requests").contentType("application/json")
                .content("{\"logbook\":\"ATLAS\",\"requestedQuotaBytes\":2000000000,\"reason\":\"More logs\"}"))
                .andExpect(status().isUnauthorized());
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> service.pending(anonymous)).getStatus());
    }

    @Test void requestDoesNotChangeQuotaUntilApprovedAndCannotBeDuplicated() throws Exception {
        mvc.perform(post("/Olog/storage/requests").principal(owner).contentType("application/json")
                .content("{\"logbook\":\"ATLAS\",\"infnUuid\":\"victim\",\"requestedQuotaBytes\":2000000000,\"reason\":\"More history\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.infnUuid").value("owner"))
                .andExpect(jsonPath("$.quotaBytes").value(1_000_000_000))
                .andExpect(jsonPath("$.request.status").value("PENDING"));
        var requested = service.requestIncrease(owner, "ATLAS", 2_000_000_000L, "More history");
        when(quotas.find("ATLAS")).thenReturn(new StorageQuotaRepository.Snapshot(requested, 1L, 1L));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.requestIncrease(owner, "ATLAS", 3_000_000_000L, "More history")).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.review(owner, "ATLAS", requested.request().id(), true, "self approval")).getStatus());
        var approved = service.review(admin, "ATLAS", requested.request().id(), true, "Approved");
        assertEquals(2_000_000_000L, approved.quotaBytes());
        assertEquals("APPROVED", approved.request().status());
        assertEquals("olog-admin", approved.request().reviewedBy());
    }

    @Test void logbookAdministratorAndExperimentAffiliationDoNotGrantQuotaAdministration() throws Exception {
        for (String role : List.of("ROLE_USER", "i:infn:lnf:csn1:atlas::aff:tng", "ADMIN", "ROLE_ADMIN", "ROLE_OLOG-ADMINS", OlogAdministratorService.ADMIN_ENTITLEMENT)) {
            var user = new UsernamePasswordAuthenticationToken("owner", "header.payload.signature", List.of(new SimpleGrantedAuthority(role)));
            mvc.perform(get("/Olog/storage/requests").principal(user)).andExpect(status().isForbidden());
        }
        when(quotas.pending()).thenReturn(List.of());
        mvc.perform(get("/Olog/storage/requests").principal(admin)).andExpect(status().isOk());
    }

    @Test void rejectionKeepsTheQuotaAndStaleDecisionsAreRejected() {
        var requested = service.requestIncrease(owner, "ATLAS", 2_000_000_000L, "More history");
        when(quotas.find("ATLAS")).thenReturn(new StorageQuotaRepository.Snapshot(requested, 1L, 1L));
        var rejected = service.review(admin, "ATLAS", requested.request().id(), false, "Not needed");
        assertEquals(1_000_000_000L, rejected.quotaBytes());
        assertEquals("REJECTED", rejected.request().status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.review(admin, "ATLAS", "stale-request-id", true, "")).getStatus());
        when(quotas.find("ATLAS")).thenReturn(new StorageQuotaRepository.Snapshot(rejected, 2L, 1L));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.review(admin, "ATLAS", requested.request().id(), true, "")).getStatus());
    }

    @Test void invalidRequestsAndMissingReviewDecisionAreRejected() throws Exception {
        for (String body : List.of("{\"logbook\":\"ATLAS\",\"requestedQuotaBytes\":1000000000,\"reason\":\"More\"}",
                "{\"logbook\":\"ATLAS\",\"requestedQuotaBytes\":2000000000,\"reason\":\" \"}", "{}")) {
            mvc.perform(post("/Olog/storage/requests").principal(owner).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/Olog/storage/requests/ATLAS/request/review").principal(admin)
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
