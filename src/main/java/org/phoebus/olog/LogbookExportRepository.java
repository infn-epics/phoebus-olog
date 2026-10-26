package org.phoebus.olog;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

@Repository
public class LogbookExportRepository {
    private final ElasticsearchDocumentScanner scanner;

    public LogbookExportRepository(ElasticsearchDocumentScanner scanner) { this.scanner = scanner; }

    public void scan(String logbook, Consumer<JsonData> consumer) throws IOException {
        Query query = Query.of(q -> q.nested(n -> n.path("logbooks")
                .query(b -> b.term(t -> t.field("logbooks.name").value(logbook)))));
        var sorts = List.of(
                SortOptions.of(s -> s.field(f -> f.field("createdDate").order(SortOrder.Asc))),
                SortOptions.of(s -> s.field(f -> f.field("id").order(SortOrder.Asc))));
        scanner.scan(ElasticConfig.ES_LOG_INDEX, query, sorts, false, JsonData.class, consumer);
    }
}
