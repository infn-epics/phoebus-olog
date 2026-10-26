package org.phoebus.olog;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import org.phoebus.olog.entity.StorageUsage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.phoebus.olog.ElasticConfig.*;

@Repository
public class StorageUsageRepository {
    private final ElasticsearchDocumentScanner scanner;

    public StorageUsageRepository(ElasticsearchDocumentScanner scanner) {
        this.scanner = scanner;
    }

    public StorageUsage forOwner(String owner) {
        return measure(Query.of(q -> q.term(t -> t.field("owner").value(owner))));
    }

    public StorageUsage.LogbookUsage forLogbook(String logbook) {
        return measure(Query.of(q -> q.term(t -> t.field("name").value(logbook))))
                .logbooks().stream().findFirst().orElse(new StorageUsage.LogbookUsage(logbook, 0, 0, 0));
    }

    private StorageUsage measure(Query bookQuery) {
        Map<String, long[]> books = new TreeMap<>();
        long[] total = {0};
        try {
            scanner.scan(ES_LOGBOOK_INDEX, bookQuery, JsonData.class, source -> {
                String name = source.toJson().asJsonObject().getString("name");
                long bytes = bytes(source);
                books.put(name, new long[]{bytes, 0, 0});
                total[0] = Math.addExact(total[0], bytes);
            });
            if (books.isEmpty()) return new StorageUsage(0, List.of());
            List<FieldValue> names = books.keySet().stream().map(FieldValue::of).toList();
            Query logs = Query.of(q -> q.nested(n -> n.path("logbooks").query(
                    b -> b.terms(t -> t.field("logbooks.name").terms(v -> v.value(names))))));
            for (String index : List.of(ES_LOG_INDEX, ES_LOG_ARCHIVE_INDEX)) {
                boolean archived = index.equals(ES_LOG_ARCHIVE_INDEX);
                scanner.scan(index, logs, JsonData.class, source -> {
                    long bytes = bytes(source);
                    total[0] = Math.addExact(total[0], bytes);
                    Set<String> counted = new HashSet<>();
                    for (var value : source.toJson().asJsonObject().getJsonArray("logbooks")) {
                        String name = value.asJsonObject().getString("name");
                        if (books.containsKey(name) && counted.add(name)) {
                            long[] usage = books.get(name);
                            usage[0] = Math.addExact(usage[0], bytes);
                            usage[archived ? 2 : 1]++;
                        }
                    }
                });
            }
            scanner.scan(ES_LOGBOOK_MEMBER_INDEX,
                    Query.of(q -> q.terms(t -> t.field("logbook").terms(v -> v.value(names)))), JsonData.class, source -> {
                String name = source.toJson().asJsonObject().getString("logbook");
                long bytes = bytes(source);
                books.get(name)[0] = Math.addExact(books.get(name)[0], bytes);
                total[0] = Math.addExact(total[0], bytes);
            });
            return new StorageUsage(total[0], books.entrySet().stream().map(e ->
                    new StorageUsage.LogbookUsage(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to calculate storage usage", exception);
        }
    }

    static long bytes(JsonData source) {
        return source.toJson().toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
