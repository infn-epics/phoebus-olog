package org.phoebus.olog;

import java.security.Principal;
import java.util.List;
import org.phoebus.olog.entity.StorageQuota;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/Olog/storage")
public class StorageResource {
    private final StorageService service;
    public record IncreaseBody(String logbook, long requestedQuotaBytes, String reason) {}
    public record ReviewBody(Boolean approved, String note) {}

    public StorageResource(StorageService service) { this.service = service; }

    @GetMapping("/permissions/me")
    public StorageService.Permissions permissions(Principal principal) { return service.permissions(principal); }

    @GetMapping("/me")
    public StorageService.Summary summary(Principal principal) { return service.summary(principal); }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public StorageQuota request(@RequestBody IncreaseBody body, Principal principal) {
        return service.requestIncrease(principal, body.logbook(), body.requestedQuotaBytes(), body.reason());
    }

    @GetMapping("/requests")
    public List<StorageQuota> pending(Principal principal) { return service.pending(principal); }

    @PostMapping("/requests/{logbook}/{requestId}/review")
    public StorageQuota review(@PathVariable String logbook, @PathVariable String requestId,
                               @RequestBody ReviewBody body, Principal principal) {
        if (body.approved() == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "approved is required");
        return service.review(principal, logbook, requestId, body.approved(), body.note());
    }
}
