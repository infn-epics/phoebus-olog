package org.phoebus.olog;

import org.phoebus.olog.entity.StorageQuota;
import org.phoebus.olog.entity.StorageQuota.IncreaseRequest;
import org.phoebus.olog.entity.StorageUsage;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.time.Instant;
import java.util.*;

@Service
public class StorageService {
    private final StorageUsageRepository usageRepository;
    private final StorageQuotaRepository quotaRepository;
    private final OlogAdministratorService administratorService;
    private final LogbookAccessService accessService;
    public record LogbookStorage(String logbook, long usedBytes, long logCount, long archivedLogCount,
                                 long quotaBytes, IncreaseRequest request) {}
    public record Summary(String infnUuid, long usedBytes, String measurement,
                          List<LogbookStorage> logbooks, boolean administrator) {}
    public record Permissions(boolean administrator) {}

    public StorageService(StorageUsageRepository usageRepository, StorageQuotaRepository quotaRepository,
                          OlogAdministratorService administratorService, LogbookAccessService accessService) {
        this.usageRepository = usageRepository;
        this.quotaRepository = quotaRepository;
        this.administratorService = administratorService;
        this.accessService = accessService;
    }

    public Permissions permissions(Principal principal) {
        authenticate(principal);
        return new Permissions(isAdministrator(principal));
    }

    public Summary summary(Principal principal) {
        authenticate(principal);
        var usage = usageRepository.forOwner(principal.getName());
        var logbooks = usage.logbooks().stream().map(book -> {
            var quota = quotaRepository.find(book.logbook()).quota();
            return new LogbookStorage(book.logbook(), book.usedBytes(), book.logCount(), book.archivedLogCount(),
                    quota.quotaBytes(), quota.request());
        }).toList();
        return new Summary(principal.getName(), usage.usedBytes(), "JSON_SOURCE_UTF8", logbooks, isAdministrator(principal));
    }

    public StorageQuota requestIncrease(Principal principal, String logbook, long requestedBytes, String reason) {
        authenticate(principal);
        if (logbook == null || logbook.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A logbook is required");
        }
        accessService.requireAdmin(logbook, principal);
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason of 1 to 2000 characters is required");
        }
        var previous = quotaRepository.find(logbook);
        var quota = previous.quota();
        if (requestedBytes <= quota.quotaBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requested quota must exceed the current quota");
        }
        if (quota.request() != null && "PENDING".equals(quota.request().status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A quota increase request is already pending");
        }
        var request = new IncreaseRequest(UUID.randomUUID().toString(), requestedBytes, reason.trim(), "PENDING",
                Instant.now().toString(), null, null, null);
        return quotaRepository.save(previous, new StorageQuota(logbook, principal.getName(), quota.quotaBytes(), request));
    }

    public List<StorageQuota> pending(Principal principal) {
        requireAdministrator(principal);
        return quotaRepository.pending();
    }

    public StorageQuota review(Principal principal, String logbook, String requestId, boolean approved, String note) {
        requireAdministrator(principal);
        if (note != null && note.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review note is too long");
        }
        var previous = quotaRepository.find(logbook);
        var quota = previous.quota();
        var request = quota.request();
        if (request == null || !"PENDING".equals(request.status()) || !request.id().equals(requestId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The quota request is no longer pending");
        }
        var decision = new IncreaseRequest(request.id(), request.requestedQuotaBytes(), request.reason(),
                approved ? "APPROVED" : "REJECTED", request.requestedAt(), principal.getName(), Instant.now().toString(), note);
        return quotaRepository.save(previous, new StorageQuota(logbook, quota.infnUuid(),
                approved ? request.requestedQuotaBytes() : quota.quotaBytes(), decision));
    }

    private boolean isAdministrator(Principal principal) {
        return administratorService.isAdministrator(principal);
    }

    private void requireAdministrator(Principal principal) {
        authenticate(principal);
        if (!isAdministrator(principal)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void authenticate(Principal principal) {
        if (principal == null || principal instanceof AnonymousAuthenticationToken
                || (principal instanceof Authentication && !((Authentication) principal).isAuthenticated())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
