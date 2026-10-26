package org.phoebus.olog.docker;

import java.time.Duration;
import java.util.*;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.phoebus.olog.*;
import org.phoebus.olog.entity.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import static org.junit.jupiter.api.Assertions.*;

class StorageQuotaIT {
    @Test void countsEveryDocumentDeduplicatesSharedEntriesAndEnforcesQuota() throws Exception {
        String[] previous = {ElasticConfig.ES_LOGBOOK_INDEX, ElasticConfig.ES_LOG_INDEX,
                ElasticConfig.ES_LOG_ARCHIVE_INDEX, ElasticConfig.ES_LOGBOOK_MEMBER_INDEX, ElasticConfig.ES_STORAGE_QUOTA_INDEX};
        try (var elastic = new GenericContainer<>("elasticsearch:8.2.3")
                .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/").withStartupTimeout(Duration.ofMinutes(2)))) {
            elastic.start();
            try (var transport = new RestClientTransport(RestClient.builder(
                    new HttpHost(elastic.getHost(), elastic.getMappedPort(9200))).build(), new JacksonJsonpMapper())) {
                var client = new ElasticsearchClient(transport);
                ElasticConfig.ES_LOGBOOK_INDEX = "quota-books";
                ElasticConfig.ES_LOG_INDEX = "quota-logs";
                ElasticConfig.ES_LOG_ARCHIVE_INDEX = "quota-archive";
                ElasticConfig.ES_LOGBOOK_MEMBER_INDEX = "quota-members";
                ElasticConfig.ES_STORAGE_QUOTA_INDEX = "quota-users";
                createIndex(client, "quota-books", "/logbook_mapping.json");
                createIndex(client, "quota-logs", "/log_entry_mapping.json");
                createIndex(client, "quota-archive", "/log_entry_mapping.json");
                createIndex(client, "quota-members", "/logbook_member_mapping.json");
                createIndex(client, "quota-users", "/storage_quota_mapping.json");
                var scanner = new ElasticsearchDocumentScanner(client);
                var usage = new StorageUsageRepository(scanner);
                var quotas = new StorageQuotaRepository(client, scanner);
                var guard = new StorageQuotaGuard(client, usage, quotas);
                var a = Map.of("name", "A", "owner", "owner", "state", "Active");
                var b = Map.of("name", "B", "owner", "owner", "state", "Inactive");
                var other = Map.of("name", "Other", "owner", "someone-else", "state", "Active");
                client.index(i -> i.index("quota-books").id("A").document(a));
                client.index(i -> i.index("quota-books").id("B").document(b));
                client.index(i -> i.index("quota-books").id("Other").document(other));
                List<BulkOperation> operations = new ArrayList<>();
                long expected = bytes(a, transport) + bytes(b, transport);
                long aBytes = bytes(a, transport);
                long bBytes = bytes(b, transport);
                for (int id = 0; id < 1101; id++) {
                    String key = Integer.toString(id);
                    var log = Map.of("id", key, "title", "Misura è μ", "owner", "collaborator",
                            "logbooks", List.of(Map.of("name", "A")));
                    operations.add(BulkOperation.of(o -> o.index(i -> i.index("quota-logs").id(key).document(log))));
                    expected += bytes(log, transport);
                    aBytes += bytes(log, transport);
                }
                client.bulk(bulk -> bulk.operations(operations).refresh(Refresh.True));
                var shared = Map.of("id", "shared", "title", "Shared", "logbooks", List.of(Map.of("name", "A"), Map.of("name", "B")));
                var foreign = Map.of("id", "foreign", "logbooks", List.of(Map.of("name", "Other")));
                var member = Map.of("id", "reader", "logbook", "A", "infnUuid", "reader", "permissions", List.of("READ"));
                client.index(i -> i.index("quota-logs").id("shared").document(shared));
                client.index(i -> i.index("quota-logs").id("foreign").document(foreign));
                client.index(i -> i.index("quota-archive").id("shared_v1").document(shared));
                client.index(i -> i.index("quota-members").id("reader").document(member));
                client.indices().refresh(r -> r.index("quota-books", "quota-logs", "quota-archive", "quota-members"));
                expected += bytes(shared, transport) * 2 + bytes(member, transport);
                aBytes += bytes(shared, transport) * 2 + bytes(member, transport);
                bBytes += bytes(shared, transport) * 2;
                var snapshot = usage.forOwner("owner");
                assertEquals(expected, snapshot.usedBytes());
                assertEquals(aBytes, snapshot.logbooks().get(0).usedBytes());
                assertEquals(bBytes, snapshot.logbooks().get(1).usedBytes());
                assertEquals(1102, snapshot.logbooks().get(0).logCount());
                assertEquals(1, snapshot.logbooks().get(0).archivedLogCount());
                assertEquals(1, snapshot.logbooks().get(1).logCount());
                assertEquals(1_000_000_000L, quotas.find("A").quota().quotaBytes());
                assertEquals(0, usage.forOwner("no-books").usedBytes());
                var incoming = Map.of("id", "incoming", "title", "New entry", "owner", "collaborator", "logbooks", List.of(Map.of("name", "A")));
                long incomingBytes = bytes(incoming, transport);
                quotas.save(quotas.find("A"), new StorageQuota("A", "owner", aBytes + incomingBytes - 1, null));
                var request = IndexRequest.of(i -> i.index("quota-logs").id("incoming").document(incoming).refresh(Refresh.True));
                assertThrows(StorageQuotaGuard.QuotaExceeded.class, () -> guard.index(request));
                assertFalse(client.get(g -> g.index("quota-logs").id("incoming"), JsonData.class).found());
                quotas.save(quotas.find("A"), new StorageQuota("A", "owner", aBytes + incomingBytes, null));
                guard.index(request);
                assertEquals(expected + incomingBytes, usage.forOwner("owner").usedBytes());
                // Replacing a document at the same size does not consume the quota twice.
                guard.index(request);
                assertThrows(StorageQuotaGuard.QuotaExceeded.class, () -> guard.index(IndexRequest.of(i ->
                        i.index("quota-archive").id("incoming_v1").document(incoming).refresh(Refresh.True))));
                // Reducing a document is allowed even after a quota has been lowered.
                quotas.save(quotas.find("A"), new StorageQuota("A", "owner", 1, null));
                var smaller = Map.of("id", "incoming", "logbooks", List.of(Map.of("name", "A")));
                guard.index(IndexRequest.of(i -> i.index("quota-logs").id("incoming").document(smaller).refresh(Refresh.True)));
                // Repository writes use the same guard; exceeding quota remains HTTP 507.
                var books = new LogbookRepository();
                ReflectionTestUtils.setField(books, "client", client);
                ReflectionTestUtils.setField(books, "storageQuotaGuard", guard);
                assertDoesNotThrow(() -> books.save(new Logbook("C", "owner")));
                assertEquals(1_000_000_000L, quotas.find("C").quota().quotaBytes());
                assertEquals(aBytes, usage.forLogbook("A").usedBytes() - bytes(smaller, transport));
                assertEquals(bBytes, usage.forLogbook("B").usedBytes());
                // Two writers cannot both consume the same remaining capacity in this instance.
                var candidate = Map.of("id", "parallelA", "logbooks", List.of(Map.of("name", "A")));
                quotas.save(quotas.find("A"), new StorageQuota("A", "owner",
                        usage.forLogbook("A").usedBytes() + bytes(candidate, transport), null));
                var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
                try {
                    var start = new java.util.concurrent.CountDownLatch(1);
                    var writes = new ArrayList<java.util.concurrent.Future<Boolean>>();
                    for (String id : List.of("parallelA", "parallelB")) {
                        writes.add(executor.submit(() -> {
                            start.await();
                            try {
                                guard.index(IndexRequest.of(i -> i.index("quota-logs").id(id)
                                        .document(Map.of("id", id, "logbooks", List.of(Map.of("name", "A"))))
                                        .refresh(Refresh.True)));
                                return true;
                            } catch (StorageQuotaGuard.QuotaExceeded expectedFailure) {
                                return false;
                            }
                        }));
                    }
                    start.countDown();
                    int admitted = 0;
                    for (var write : writes) if (write.get(20, java.util.concurrent.TimeUnit.SECONDS)) admitted++;
                    assertEquals(1, admitted);
                } finally {
                    executor.shutdownNow();
                }
                // Optimistic concurrency protects requests and decisions from lost updates.
                var first = quotas.find("A");
                var stale = quotas.find("A");
                var increase = new StorageQuota.IncreaseRequest("request-1", 2_000_000_000L, "More history", "PENDING",
                        "2026-10-26T12:00:00Z", null, null, null);
                quotas.save(first, new StorageQuota("A", "owner", 1, increase));
                assertEquals(1, quotas.pending().size());
                var conflict = assertThrows(ResponseStatusException.class,
                        () -> quotas.save(stale, new StorageQuota("A", "owner", 3_000_000_000L, null)));
                assertEquals(HttpStatus.CONFLICT, conflict.getStatus(), () -> String.valueOf(conflict.getCause()));
            }
        } finally {
            ElasticConfig.ES_LOGBOOK_INDEX = previous[0]; ElasticConfig.ES_LOG_INDEX = previous[1];
            ElasticConfig.ES_LOG_ARCHIVE_INDEX = previous[2]; ElasticConfig.ES_LOGBOOK_MEMBER_INDEX = previous[3];
            ElasticConfig.ES_STORAGE_QUOTA_INDEX = previous[4];
        }
    }

    private void createIndex(ElasticsearchClient client, String index, String mapping) throws Exception {
        try (var stream = getClass().getResourceAsStream(mapping)) {
            client.indices().create(c -> c.index(index).withJson(stream));
        }
    }

    private long bytes(Object value, RestClientTransport transport) {
        return JsonData.of(value, transport.jsonpMapper()).toJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
