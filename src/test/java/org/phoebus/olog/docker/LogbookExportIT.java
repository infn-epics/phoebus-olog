package org.phoebus.olog.docker;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.olog.*;
import org.phoebus.olog.entity.Logbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogbookExportIT {
    @TempDir Path directory;

    @Test void exportsAllScrollPagesInOrderAndOnlyTheRequestedLogbook() throws Exception {
        String previous = ElasticConfig.ES_LOG_INDEX;
        try (var elastic = new GenericContainer<>("elasticsearch:8.2.3")
                .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/").withStartupTimeout(Duration.ofMinutes(2)))) {
            elastic.start();
            try (var transport = new RestClientTransport(RestClient.builder(new HttpHost(elastic.getHost(), elastic.getMappedPort(9200))).build(), new JacksonJsonpMapper())) {
                var client = new ElasticsearchClient(transport);
                ElasticConfig.ES_LOG_INDEX = "export-logs";
                try (var mapping = getClass().getResourceAsStream("/log_entry_mapping.json")) {
                    client.indices().create(c -> c.index("export-logs").withJson(mapping));
                }
                List<BulkOperation> operations = new ArrayList<>();
                for (int id = 1100; id >= 0; id--) {
                    int number = id;
                    var source = Map.of("id", id, "title", "Entry-" + id, "createdDate", 1700000000000L + id,
                            "state", id == 1 ? "Inactive" : "Active", "logbooks", List.of(Map.of("name", "ATLAS")));
                    operations.add(BulkOperation.of(o -> o.index(i -> i.index("export-logs").id("" + number).document(source))));
                }
                operations.add(BulkOperation.of(o -> o.index(i -> i.index("export-logs").id("foreign")
                        .document(Map.of("id", 9999, "createdDate", 0, "title", "Foreign book", "logbooks", List.of(Map.of("name", "ATLAS-other")))))));
                assertFalse(client.bulk(b -> b.operations(operations).refresh(Refresh.True)).errors());
                var books = mock(LogbookRepository.class);
                when(books.findById("ATLAS")).thenReturn(Optional.of(new Logbook("ATLAS", "owner")));
                var mapper = new ObjectMapper();
                var service = new LogbookExportService(mock(LogbookAccessService.class), books,
                        new LogbookExportRepository(new ElasticsearchDocumentScanner(client)), mapper, directory.toString());
                try (var file = service.export("ATLAS", "json", () -> "owner")) {
                    var json = mapper.readTree(file.path().toFile());
                    assertEquals(1101, json.path("logCount").asInt());
                    assertEquals(1101, json.path("logs").size());
                    for (int id = 0; id <= 1100; id++) assertEquals(id, json.path("logs").get(id).path("id").asInt());
                    assertEquals("Inactive", json.path("logs").get(1).path("state").asText());
                }
                try (var file = service.export("ATLAS", "pdf", () -> "owner"); var pdf = PDDocument.load(file.path().toFile())) {
                    String text = new PDFTextStripper().getText(pdf);
                    assertTrue(text.contains("Entry-0"));
                    assertTrue(text.contains("Entry-1100"));
                    assertFalse(text.contains("Foreign book"));
                    assertTrue(pdf.getNumberOfPages() > 1);
                }
                try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            }
        } finally { ElasticConfig.ES_LOG_INDEX = previous; }
    }
}
