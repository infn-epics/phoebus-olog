package org.phoebus.olog;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.List;
import co.elastic.clients.elasticsearch._types.SortOptions;

/** Scrolls every matching document without the search result-size limit. */
@Component
public class ElasticsearchDocumentScanner {
    private final ElasticsearchClient client;

    public ElasticsearchDocumentScanner(ElasticsearchClient client) {
        this.client = client;
    }

    public <T> void scan(String index, Query query, Class<T> type, Consumer<T> consumer) throws IOException {
        scan(index, query, List.of(SortOptions.of(s -> s.field(f -> f.field("_doc")))), true, type, consumer);
    }

    public <T> void scan(String index, Query query, List<SortOptions> sorts, boolean ignoreUnavailable,
                         Class<T> type, Consumer<T> consumer) throws IOException {
        String scrollId = null;
        try {
            SearchResponse<T> response = client.search(s -> s.index(index).ignoreUnavailable(ignoreUnavailable)
                    .query(query).size(500).scroll(t -> t.time("1m")).sort(sorts), type);
            while (true) {
                if (response.scrollId() != null) scrollId = response.scrollId();
                if (response.timedOut() || response.shards().failed().intValue() > 0) {
                    throw new IOException("Incomplete Elasticsearch storage scan");
                }
                if (response.hits().hits().isEmpty()) break;
                for (var hit : response.hits().hits()) {
                    if (hit.source() == null) throw new IOException("Elasticsearch source is unavailable");
                    consumer.accept(hit.source());
                }
                if (scrollId == null) throw new IOException("Elasticsearch scroll is unavailable");
                String id = scrollId;
                var next = client.scroll(s -> s.scrollId(id).scroll(t -> t.time("1m")), type);
                response = new SearchResponse.Builder<T>().took(next.took()).timedOut(next.timedOut())
                        .shards(next.shards()).hits(next.hits()).scrollId(next.scrollId()).build();
            }
        } finally {
            if (scrollId != null) {
                String id = scrollId;
                try {
                    client.clearScroll(c -> c.scrollId(id));
                } catch (IOException exception) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unable to release storage scan; scroll will expire");
                }
            }
        }
    }

}
