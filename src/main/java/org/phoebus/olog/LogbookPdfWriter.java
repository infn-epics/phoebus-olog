package org.phoebus.olog;

import jakarta.json.*;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Paginated text export. No embedded HTML, external URLs or attachment data are loaded. */
final class LogbookPdfWriter implements AutoCloseable {
    private static final float MARGIN = 45;
    private static final Set<String> MAIN_FIELDS = Set.of("id", "title", "createdDate", "modifyDate", "owner", "level", "state", "source", "description");
    private final PDDocument document = new PDDocument(MemoryUsageSetting.setupTempFileOnly());
    private final Path path;
    private final String logbook;
    private final PDFont font;
    private final Map<Integer, String> glyphs = new HashMap<>();
    private final Parser parser = Parser.builder().build();
    private final TextContentRenderer renderer = TextContentRenderer.builder().build();
    private PDPageContentStream stream;
    private float y;
    private long count;

    LogbookPdfWriter(Path path, String logbook, String exportedAt) throws IOException {
        this.path = path;
        this.logbook = logbook;
        try (var input = PDType0Font.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            if (input == null) throw new IOException("Export font unavailable");
            font = PDType0Font.load(document, input, true);
            document.getDocumentInformation().setTitle("Logbook " + logbook);
            document.getDocumentInformation().setCreator("Phoebus Olog");
            page();
            text("Logbook: " + logbook, 16);
            text("Exported at: " + exportedAt, 9);
            text("Current log entries, ordered by creation date (UTC).", 9);
            y -= 10;
        } catch (IOException | RuntimeException exception) {
            document.close();
            throw exception;
        }
    }

    void entry(JsonObject entry) throws IOException {
        count++;
        text("Entry #" + value(entry.get("id")) + " - " + value(entry.get("title")), 12);
        text("Created: " + date(entry.get("createdDate")) + " | Modified: " + date(entry.get("modifyDate")), 9);
        text("Author: " + value(entry.get("owner")) + " | Level: " + value(entry.get("level")) + " | State: " + value(entry.get("state")), 9);
        String description = value(entry.get("description"));
        if (description.isBlank()) description = renderer.render(parser.parse(value(entry.get("source"))));
        text(description, 10);
        for (var field : entry.entrySet()) {
            if (!MAIN_FIELDS.contains(field.getKey())) text(field.getKey() + ": " + value(field.getValue()), 9);
        }
        y -= 12;
    }

    private String value(JsonValue value) {
        return value == null || value == JsonValue.NULL ? "" : value instanceof JsonString ? ((JsonString) value).getString() : value.toString();
    }

    private String date(JsonValue value) {
        return value instanceof JsonNumber ? Instant.ofEpochMilli(((JsonNumber) value).longValue()).toString() : value(value);
    }

    private String safe(String input) {
        StringBuilder result = new StringBuilder();
        input.codePoints().forEach(code -> result.append(glyphs.computeIfAbsent(code, cp -> {
            if (cp == '\t') return "    ";
            String glyph = new String(Character.toChars(cp));
            try {
                font.getStringWidth(glyph);
                return glyph;
            } catch (IOException | IllegalArgumentException exception) {
                return String.format(Locale.ROOT, "[U+%04X]", cp);
            }
        })));
        return result.toString();
    }

    private void text(String input, float size) throws IOException {
        float available = PDRectangle.A4.getWidth() - 2 * MARGIN;
        for (String paragraph : input.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String content = safe(paragraph);
            StringBuilder line = new StringBuilder();
            float width = 0;
            int space = -1;
            for (int offset = 0; offset < content.length();) {
                int code = content.codePointAt(offset);
                String glyph = new String(Character.toChars(code));
                float glyphWidth = font.getStringWidth(glyph) / 1000 * size;
                if (width + glyphWidth > available && line.length() > 0) {
                    if (space > 0) {
                        draw(line.substring(0, space), size);
                        String rest = line.substring(space + 1);
                        line.setLength(0); line.append(rest);
                        width = font.getStringWidth(rest) / 1000 * size;
                    } else {
                        draw(line.toString(), size);
                        line.setLength(0); width = 0;
                    }
                    space = -1;
                    if (width + glyphWidth > available && line.length() > 0) {
                        draw(line.toString(), size);
                        line.setLength(0); width = 0;
                    }
                }
                line.append(glyph); width += glyphWidth;
                if (code == ' ') space = line.length() - 1;
                offset += Character.charCount(code);
            }
            draw(line.toString(), size);
        }
    }

    private void draw(String line, float size) throws IOException {
        if (y < MARGIN + size * 1.4f) page();
        stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(MARGIN, y);
        stream.showText(line); stream.endText();
        y -= size * 1.4f;
    }

    private void footer() throws IOException {
        stream.beginText(); stream.setFont(font, 8); stream.newLineAtOffset(MARGIN, 24);
        stream.showText("Page " + document.getNumberOfPages()); stream.endText();
        stream.close();
    }

    private void page() throws IOException {
        if (stream != null) footer();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        y = page.getMediaBox().getHeight() - MARGIN;
        String heading = safe(logbook.codePoints().limit(80)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString());
        while (font.getStringWidth(heading) / 1000 * 9 > PDRectangle.A4.getWidth() - 2 * MARGIN) {
            heading = heading.substring(0, heading.offsetByCodePoints(heading.length(), -1));
        }
        draw(heading, 9);
        y -= 8;
    }

    @Override public void close() throws IOException {
        try {
            if (count == 0) text("No log entries.", 10);
            footer();
            document.save(path.toFile());
        } finally {
            document.close();
        }
    }
}
