package org.phoebus.olog;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import org.phoebus.olog.entity.StorageQuota;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.phoebus.olog.ElasticConfig.ES_STORAGE_QUOTA_INDEX;

@Repository
public class StorageQuotaRepository {
    public static final long DEFAULT_QUOTA_BYTES = 1_000_000_000L;
    private final ElasticsearchClient client;
    private final ElasticsearchDocumentScanner scanner;
    public record Snapshot(StorageQuota quota, Long seqNo, Long primaryTerm) {}

    public StorageQuotaRepository(ElasticsearchClient client, ElasticsearchDocumentScanner scanner) {
        this.client = client;
        this.scanner = scanner;
    }

    public Snapshot find(String logbook) {
        try {
            var result = client.get(g -> g.index(ES_STORAGE_QUOTA_INDEX).id("logbook:" + logbook), StorageQuota.class);
            return result.found() ? new Snapshot(result.source(), result.seqNo(), result.primaryTerm())
                    : new Snapshot(new StorageQuota(logbook, null, DEFAULT_QUOTA_BYTES, null), null, null);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    public StorageQuota save(Snapshot previous, StorageQuota quota) {
        try {
            var builder = new IndexRequest.Builder<StorageQuota>().index(ES_STORAGE_QUOTA_INDEX)
                    .id("logbook:" + quota.logbook()).document(quota).refresh(Refresh.True);
            if (previous.seqNo() == null) builder.opType(OpType.Create);
            else builder.ifSeqNo(previous.seqNo()).ifPrimaryTerm(previous.primaryTerm());
            client.index(builder.build());
            return quota;
        } catch (ElasticsearchException exception) {
            if (exception.status() == 409) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Storage quota changed; refresh and retry");
            throw exception;
        } catch (org.elasticsearch.client.ResponseException exception) {
            if (exception.getResponse().getStatusLine().getStatusCode() == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Storage quota changed; refresh and retry");
            }
            throw unavailable(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    public List<StorageQuota> pending() {
        List<StorageQuota> result = new ArrayList<>();
        try {
            scanner.scan(ES_STORAGE_QUOTA_INDEX,
                    co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q ->
                            q.bool(b -> b.filter(f -> f.term(t -> t.field("request.status").value("PENDING")))
                                    .filter(f -> f.exists(e -> e.field("logbook"))))), StorageQuota.class, result::add);
            return result;
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private ResponseStatusException unavailable(IOException exception) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Storage quota service unavailable", exception);
    }
}
