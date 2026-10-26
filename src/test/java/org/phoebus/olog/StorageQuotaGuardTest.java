package org.phoebus.olog;

import java.util.*;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import org.junit.jupiter.api.*;
import org.phoebus.olog.entity.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageQuotaGuardTest {
    ElasticsearchClient client;
    StorageUsageRepository usage = mock(StorageUsageRepository.class);
    StorageQuotaRepository quotas = mock(StorageQuotaRepository.class);
    StorageQuotaGuard guard;
    String previousBookIndex;
    long GB = StorageQuotaRepository.DEFAULT_QUOTA_BYTES;

    @BeforeEach void setup() throws Exception {
        previousBookIndex = ElasticConfig.ES_LOGBOOK_INDEX;
        ElasticConfig.ES_LOGBOOK_INDEX = "books";
        client = mock(ElasticsearchClient.class, RETURNS_DEEP_STUBS);
        when(client._transport().jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(client.get(any(GetRequest.class), eq(JsonData.class)))
                .thenReturn(new GetResponse.Builder<JsonData>().index("logs").id("1").found(false).build());
        when(quotas.find(anyString())).thenAnswer(i -> new StorageQuotaRepository.Snapshot(
                new StorageQuota(i.getArgument(0), "owner", GB, null), null, null));
        when(usage.forLogbook("A")).thenReturn(new StorageUsage.LogbookUsage("A", 700_000_000, 1, 0));
        when(usage.forLogbook("B")).thenReturn(new StorageUsage.LogbookUsage("B", 600_000_000, 1, 0));
        guard = new StorageQuotaGuard(client, usage, quotas);
    }

    @AfterEach void restore() { ElasticConfig.ES_LOGBOOK_INDEX = previousBookIndex; }

    IndexRequest<Map<String, Object>> entry(String... books) {
        Map<String, Object> document = Map.of("id", 1, "logbooks", Arrays.stream(books).map(b -> Map.of("name", b)).toList());
        return IndexRequest.of(i -> i.index("logs").id("1").document(document));
    }

    @Test void totalAboveOneGbDoesNotBlockSeparateLogbooks() throws Exception {
        guard.index(entry("A"));
        guard.index(entry("B"));
        verify(usage, never()).forOwner(anyString());
        verify(client, times(2)).index(any(IndexRequest.class));
    }

    @Test void sharedEntryChecksEachBookAndFailsIfEitherIsFull() throws Exception {
        guard.index(entry("A", "B"));
        when(usage.forLogbook("B")).thenReturn(new StorageUsage.LogbookUsage("B", GB, 1, 0));
        var error = assertThrows(StorageQuotaGuard.QuotaExceeded.class, () -> guard.index(entry("A", "B")));
        assertTrue(error.getReason().contains("'B'"));
        assertDoesNotThrow(() -> guard.index(entry("A")));
        verify(client, times(2)).index(any(IndexRequest.class));
    }

    @Test void increasedQuotaAppliesOnlyToItsOwnLogbook() throws Exception {
        when(usage.forLogbook("A")).thenReturn(new StorageUsage.LogbookUsage("A", GB, 1, 0));
        when(usage.forLogbook("B")).thenReturn(new StorageUsage.LogbookUsage("B", GB, 1, 0));
        when(quotas.find("A")).thenReturn(new StorageQuotaRepository.Snapshot(new StorageQuota("A", "owner", 2 * GB, null), 1L, 1L));
        assertDoesNotThrow(() -> guard.index(entry("A")));
        assertThrows(StorageQuotaGuard.QuotaExceeded.class, () -> guard.index(entry("B")));
    }

    @Test void aNewLogbookHasItsOwnBudgetRegardlessOfCreatorUsage() throws Exception {
        when(usage.forLogbook("C")).thenReturn(new StorageUsage.LogbookUsage("C", 0, 0, 0));
        Map<String, Object> book = Map.of("name", "C", "owner", "owner");
        assertDoesNotThrow(() -> guard.index(IndexRequest.of(i -> i.index("books").id("C").document(book))));
        verify(quotas).find("C");
        verify(quotas, never()).find("owner");
    }
}
