package org.phoebus.olog.entity;

import java.util.List;

/** Compact UTF-8 JSON source bytes, not compressed Lucene disk usage. */
public record StorageUsage(long usedBytes, List<LogbookUsage> logbooks) {
    public record LogbookUsage(String logbook, long usedBytes, long logCount, long archivedLogCount) {}
}
