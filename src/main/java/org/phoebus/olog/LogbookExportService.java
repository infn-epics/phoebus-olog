package org.phoebus.olog;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.phoebus.olog.entity.Logbook;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.*;
import java.security.Principal;
import java.time.Instant;
import java.util.Locale;

@Service
public class LogbookExportService {
    private final LogbookAccessService access;
    private final LogbookRepository logbooks;
    private final LogbookExportRepository logs;
    private final ObjectMapper mapper;
    private final Path directory;
    public record ExportFile(Path path, String filename, MediaType mediaType) implements AutoCloseable {
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }

    public LogbookExportService(LogbookAccessService access, LogbookRepository logbooks,
                                LogbookExportRepository logs, ObjectMapper mapper,
                                @Value("${olog.export.temp-directory:${java.io.tmpdir}}") String directory) {
        this.access = access; this.logbooks = logbooks; this.logs = logs; this.mapper = mapper;
        this.directory = Path.of(directory);
    }

    public ExportFile export(String name, String format, Principal principal) {
        access.requireAdmin(name, principal);
        String extension = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (!extension.equals("json") && !extension.equals("pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export format must be json or pdf");
        }
        Logbook book = logbooks.findById(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Path path = null;
        boolean complete = false;
        try {
            path = Files.createTempFile(directory, "olog-export-", "." + extension);
            String exportedAt = Instant.now().toString();
            if (extension.equals("json")) json(book, exportedAt, path);
            else pdf(name, exportedAt, path);
            String safeName = name.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
            safeName = safeName.codePoints().limit(80).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            ExportFile result = new ExportFile(path, "olog-" + safeName + "." + extension,
                    extension.equals("pdf") ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_JSON);
            complete = true;
            return result;
        } catch (IOException | RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to generate the complete logbook export", exception);
        } finally {
            if (!complete && path != null) {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unable to delete temporary export"); }
            }
        }
    }

    private void json(Logbook book, String exportedAt, Path path) throws IOException {
        try (var generator = mapper.getFactory().createGenerator(path.toFile(), JsonEncoding.UTF8)) {
            generator.writeStartObject();
            generator.writeNumberField("formatVersion", 1);
            generator.writeStringField("exportedAt", exportedAt);
            generator.writeObjectField("logbook", book);
            generator.writeArrayFieldStart("logs");
            long[] count = {0};
            logs.scan(book.getName(), source -> {
                try { generator.writeRawValue(source.toJson().toString()); count[0]++; }
                catch (IOException exception) { throw new UncheckedIOException(exception); }
            });
            generator.writeEndArray();
            generator.writeNumberField("logCount", count[0]);
            generator.writeEndObject();
        } catch (UncheckedIOException exception) { throw exception.getCause(); }
    }

    private void pdf(String name, String exportedAt, Path path) throws IOException {
        try (var writer = new LogbookPdfWriter(path, name, exportedAt)) {
            logs.scan(name, source -> {
                try { writer.entry(source.toJson().asJsonObject()); }
                catch (IOException exception) { throw new UncheckedIOException(exception); }
            });
        } catch (UncheckedIOException exception) { throw exception.getCause(); }
    }
}
