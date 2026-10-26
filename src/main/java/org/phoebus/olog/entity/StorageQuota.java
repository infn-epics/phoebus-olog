package org.phoebus.olog.entity;

public record StorageQuota(String logbook, String infnUuid, long quotaBytes, IncreaseRequest request) {
    public record IncreaseRequest(String id, long requestedQuotaBytes, String reason, String status,
                                  String requestedAt, String reviewedBy, String reviewedAt, String reviewNote) {}
}
