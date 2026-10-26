package org.phoebus.olog;

import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.olog.entity.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.*;
import java.security.Principal;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LogbookExportTest {
    @TempDir Path directory;
    LogbookRepository books;
    LogbookMemberRepository members;
    LogbookExportRepository logs;
    LogbookExportService service;
    MockMvc mvc;
    ObjectMapper mapper = new ObjectMapper();
    Principal owner = () -> "owner";
    List<JsonData> entries;

    @BeforeEach void setup() throws Exception {
        books = mock(LogbookRepository.class); members = mock(LogbookMemberRepository.class);
        logs = mock(LogbookExportRepository.class);
        var access = new LogbookAccessService(books, members, mock(org.phoebus.olog.identity.ExperimentMembershipService.class));
        when(books.findById("ATLAS")).thenReturn(Optional.of(new Logbook("ATLAS", "owner")));
        entries = List.of(source("{\"id\":1,\"title\":\"Misura è μ\",\"source\":\"**Originale**\",\"description\":\"Testo completo\",\"createdDate\":1790000000000,\"logbooks\":[{\"name\":\"ATLAS\"}],\"customField\":\"retained\",\"attachments\":[{\"id\":\"file1\",\"filename\":\"plot.png\"}]}"),
                source("{\"id\":2,\"title\":\"Reply\",\"description\":\"Seconda entry\",\"logbooks\":[{\"name\":\"ATLAS\"}],\"properties\":[{\"name\":\"reply\",\"attributes\":[{\"name\":\"id\",\"value\":\"1\"}]}]}"));
        doAnswer(invocation -> {
            Consumer<JsonData> consumer = invocation.getArgument(1);
            entries.forEach(consumer);
            return null;
        }).when(logs).scan(eq("ATLAS"), any());
        service = new LogbookExportService(access, books, logs, mapper, directory.toString());
        mvc = MockMvcBuilders.standaloneSetup(new LogbookExportResource(service)).build();
    }

    private JsonData source(String json) {
        try (var reader = Json.createReader(new StringReader(json))) { return JsonData.of(reader.readObject()); }
    }

    private void noTemporaryFiles() throws IOException {
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void ownerDownloadsCompleteJsonIncludingUnknownFieldsAndAttachmentMetadata() throws Exception {
        var result = mvc.perform(get("/Olog/logbooks/ATLAS/export").principal(owner)).andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("olog-ATLAS.json"))).andReturn();
        var tree = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertEquals("ATLAS", tree.path("logbook").path("name").asText());
        assertEquals(2, tree.path("logCount").asInt());
        assertEquals(2, tree.path("logs").size());
        assertEquals("Misura è μ", tree.path("logs").get(0).path("title").asText());
        assertEquals("retained", tree.path("logs").get(0).path("customField").asText());
        assertEquals("plot.png", tree.path("logs").get(0).path("attachments").get(0).path("filename").asText());
        noTemporaryFiles();
    }

    @Test void pdfIsReadablePaginatedAndKeepsUnicodeAndUnsupportedGlyphInformation() throws Exception {
        var longEntry = Json.createObjectBuilder().add("id", 3).add("title", "Unicode μ è 😀")
                .add("description", "Line content\n".repeat(180) + "unbroken".repeat(300)).build();
        entries = List.of(JsonData.of(longEntry));
        var result = mvc.perform(get("/Olog/logbooks/ATLAS/export").param("format", "pdf").principal(owner))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn();
        try (var pdf = PDDocument.load(result.getResponse().getContentAsByteArray())) {
            assertTrue(pdf.getNumberOfPages() > 1);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Unicode μ è"));
            assertTrue(text.contains("[U+1F600]"));
            assertTrue(text.contains("Line content"));
            assertTrue(text.contains("Page 2"));
        }
        noTemporaryFiles();
    }

    @Test void delegatedLogbookAdministratorCanExportOutsideCollaboratorVisibilityPeriod() throws Exception {
        var member = new LogbookMember("ATLAS", "delegate", Set.of(LogbookPermission.ADMIN), "owner", "now", "now");
        member.setVisibleFrom("2099-01-01");
        when(members.find("ATLAS", "delegate")).thenReturn(Optional.of(member));
        mvc.perform(get("/Olog/logbooks/ATLAS/export").principal(() -> "delegate")).andExpect(status().isOk());
    }

    @Test void anonymousReadersWritersExperimentMembersAndGlobalRolesCannotExport() throws Exception {
        mvc.perform(get("/Olog/logbooks/ATLAS/export")).andExpect(status().isUnauthorized());
        for (String name : List.of("reader", "writer")) {
            when(members.find("ATLAS", name)).thenReturn(Optional.of(new LogbookMember("ATLAS", name,
                    Set.of(name.equals("reader") ? LogbookPermission.READ : LogbookPermission.WRITE), "owner", "now", "now")));
            mvc.perform(get("/Olog/logbooks/ATLAS/export").principal(() -> name)).andExpect(status().isForbidden());
        }
        for (String role : List.of("ROLE_ADMIN", "i:infn:lnf:csn1:atlas::aff:tng", OlogAdministratorService.ADMIN_ENTITLEMENT)) {
            var user = new UsernamePasswordAuthenticationToken("other", null, List.of(new SimpleGrantedAuthority(role)));
            mvc.perform(get("/Olog/logbooks/ATLAS/export").param("format", "pdf").principal(user)).andExpect(status().isForbidden());
        }
        var anonymous = new AnonymousAuthenticationToken("key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> service.export("ATLAS", "json", anonymous)).getStatus());
        verifyNoInteractions(logs);
        noTemporaryFiles();
    }

    @Test void unsupportedFormatAndUnknownLogbookDoNotStartAnExport() throws Exception {
        mvc.perform(get("/Olog/logbooks/ATLAS/export").param("format", "csv").principal(owner)).andExpect(status().isBadRequest());
        mvc.perform(get("/Olog/logbooks/missing/export").principal(owner)).andExpect(status().isNotFound());
        verifyNoInteractions(logs);
        noTemporaryFiles();
    }

    @Test void emptyLogbooksExportValidFiles() throws Exception {
        entries = List.of();
        try (var file = service.export("ATLAS", "json", owner)) {
            assertTrue(mapper.readTree(file.path().toFile()).path("logs").isEmpty());
        }
        try (var file = service.export("ATLAS", "pdf", owner); var pdf = PDDocument.load(file.path().toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(pdf).contains("No log entries."));
        }
        noTemporaryFiles();
    }

    @Test void failedScansNeverReturnPartialDownloadsAndCleanTemporaryFiles() throws Exception {
        doAnswer(invocation -> {
            Consumer<JsonData> consumer = invocation.getArgument(1);
            consumer.accept(entries.get(0));
            throw new IOException("Elasticsearch scroll interrupted");
        }).when(logs).scan(eq("ATLAS"), any());
        for (String format : List.of("json", "pdf")) {
            mvc.perform(get("/Olog/logbooks/ATLAS/export").param("format", format).principal(owner)).andExpect(status().isServiceUnavailable());
            noTemporaryFiles();
        }
    }
}
