package org.phoebus.olog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Principal;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/Olog/logbooks")
public class LogbookExportResource {
    private final LogbookExportService service;
    public LogbookExportResource(LogbookExportService service) { this.service = service; }

    @GetMapping("/{name}/export")
    public void export(@PathVariable String name, @RequestParam(defaultValue = "json") String format,
                       Principal principal, HttpServletResponse response) throws IOException {
        try (var file = service.export(name, format, principal)) {
            response.setContentType(file.mediaType().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(file.filename(), StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(Files.size(file.path()));
            Files.copy(file.path(), response.getOutputStream());
        }
    }
}
