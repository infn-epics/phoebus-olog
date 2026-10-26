package org.phoebus.olog;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.json.JsonData;
import jakarta.json.JsonObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;
import static org.phoebus.olog.ElasticConfig.*;

/** Serializes quota admission and indexing within this service instance. */
@Service
public class StorageQuotaGuard {
    public static class QuotaExceeded extends ResponseStatusException {
        public QuotaExceeded(String logbook) {
            super(HttpStatus.INSUFFICIENT_STORAGE,
                    "The storage quota for logbook '" + logbook + "' would be exceeded. Request more space for this logbook before writing.");
        }
    }
    private final ElasticsearchClient client;
    private final StorageUsageRepository usageRepository;
    private final StorageQuotaRepository quotaRepository;

    public StorageQuotaGuard(ElasticsearchClient client, StorageUsageRepository usageRepository,
                             StorageQuotaRepository quotaRepository) {
        this.client = client;
        this.usageRepository = usageRepository;
        this.quotaRepository = quotaRepository;
    }

    public synchronized <T> IndexResponse index(IndexRequest<T> request) throws IOException {
        JsonObject next = JsonData.of(request.document(), client._transport().jsonpMapper()).toJson().asJsonObject();
        var previous = client.get(g -> g.index(request.index()).id(request.id()), JsonData.class);
        JsonObject old = previous.found() ? previous.source().toJson().asJsonObject() : null;
        Set<String> names = logbooks(next, request.index());
        if (old != null) names.addAll(logbooks(old, request.index()));
        for (String name : names) {
            long delta = contribution(next, request.index(), name) - contribution(old, request.index(), name);
            if (delta <= 0) continue;
            long usage = usageRepository.forLogbook(name).usedBytes();
            long limit = quotaRepository.find(name).quota().quotaBytes();
            if (usage > limit || delta > limit - usage) {
                throw new QuotaExceeded(name);
            }
        }
        return client.index(request);
    }

    private long contribution(JsonObject source, String index, String logbook) {
        if (source == null || !logbooks(source, index).contains(logbook)) return 0;
        return StorageUsageRepository.bytes(JsonData.of(source));
    }

    private Set<String> logbooks(JsonObject source, String index) {
        Set<String> names = new HashSet<>();
        if (index.equals(ES_LOGBOOK_INDEX)) names.add(source.getString("name"));
        else {
            if (source.containsKey("logbook")) names.add(source.getString("logbook"));
            if (source.containsKey("logbooks")) {
                for (var book : source.getJsonArray("logbooks")) names.add(book.asJsonObject().getString("name"));
            }
        }
        return names;
    }
}
