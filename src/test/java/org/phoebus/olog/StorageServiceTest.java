package org.phoebus.olog;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.phoebus.olog.entity.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageServiceTest {
    StorageUsageRepository usage = mock(StorageUsageRepository.class);
    StorageQuotaRepository quotas = mock(StorageQuotaRepository.class);
    LogbookAccessService access = mock(LogbookAccessService.class);
    OlogAdministratorService authorization = mock(OlogAdministratorService.class);
    StorageService service = new StorageService(usage, quotas, authorization, access);
    Principal admin = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("superadmin", null, List.of());
    Principal owner = () -> "owner";
    long GB = StorageQuotaRepository.DEFAULT_QUOTA_BYTES;

    @BeforeEach void setup() {
        when(authorization.isAdministrator(admin)).thenReturn(true);
        when(quotas.find(anyString())).thenAnswer(i -> new StorageQuotaRepository.Snapshot(
                new StorageQuota(i.getArgument(0), null, GB, null), null, null));
        when(quotas.save(any(), any())).thenAnswer(i -> i.getArgument(1));
    }

    @Test void summaryExposesIndependentQuotasForTwoBooksWithTheSameOwner() {
        when(usage.forOwner("owner")).thenReturn(new StorageUsage(1_300_000_000L, List.of(
                new StorageUsage.LogbookUsage("atlas", 700_000_000L, 1, 0),
                new StorageUsage.LogbookUsage("padme", 600_000_000L, 1, 0))));
        var summary = service.summary(owner);
        assertEquals(2, summary.logbooks().size());
        assertTrue(summary.logbooks().stream().allMatch(b -> b.quotaBytes() == GB && b.usedBytes() < b.quotaBytes()));
        verify(quotas, never()).find("owner");
    }

    @Test void increaseAndApprovalAffectOnlyTheNamedLogbook() {
        var request = service.requestIncrease(owner, "atlas", 2 * GB, "More history");
        assertEquals("atlas", request.logbook());
        verify(access).requireAdmin("atlas", owner);
        when(quotas.find("atlas")).thenReturn(new StorageQuotaRepository.Snapshot(request, 1L, 1L));
        var approved = service.review(admin, "atlas", request.request().id(), true, "OK");
        assertEquals(2 * GB, approved.quotaBytes());
        assertEquals("atlas", approved.logbook());
        assertEquals(GB, quotas.find("padme").quota().quotaBytes());
        verify(quotas, never()).find("owner");
    }

    @Test void pendingRequestInAnotherBookDoesNotBlockAnIncrease() {
        var first = service.requestIncrease(owner, "atlas", 2 * GB, "More history");
        when(quotas.find("atlas")).thenReturn(new StorageQuotaRepository.Snapshot(first, 1L, 1L));
        assertDoesNotThrow(() -> service.requestIncrease(owner, "padme", 3 * GB, "More data"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.requestIncrease(owner, "atlas", 3 * GB, "Again")).getStatus());
    }

    @Test void anonymousCannotReadRequestOrReviewQuotas() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> service.summary(null)).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.requestIncrease(null, "atlas", 2 * GB, "More")).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> service.pending(null)).getStatus());
    }

    @Test void onlyLogbookAdministratorsCanRequestAndGlobalAdministratorsCanReview() {
        for (String user : List.of("unrelated", "reader", "writer")) {
            Principal principal = () -> user;
            doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireAdmin("atlas", principal);
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                    () -> service.requestIncrease(principal, "atlas", 2 * GB, "More")).getStatus());
        }
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.review(owner, "atlas", "id", true, "OK")).getStatus());
        assertDoesNotThrow(() -> service.requestIncrease(() -> "delegated-admin", "atlas", 2 * GB, "More"));
        assertDoesNotThrow(() -> service.pending(admin));
    }
}
