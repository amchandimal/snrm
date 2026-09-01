package com.snrm.dataimport;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * One uploaded part, decoupled from Spring's {@link MultipartFile}.
 *
 * <p>The adapters take this rather than the servlet type for two reasons. It lets them be tested
 * against a byte array with no web layer in sight, and it lets {@link CsvAdapter} read the same
 * content twice — once to sniff the delimiter, once to parse with it — which a raw request stream
 * cannot do. The bytes are already buffered by Spring's multipart resolver (to disk past the
 * configured threshold), so reading them here does not add a copy of anything that was not already
 * materialised.
 *
 * @param filename    the client-supplied name, which is the only clue to which sheet a CSV is
 * @param contentType the declared MIME type, used only as a tiebreak — browsers disagree about
 *                    spreadsheet types often enough that the extension is the more reliable signal
 * @param content     the file's bytes
 */
public record UploadedFile(String filename, String contentType, byte[] content) {

    /** @throws IOException if the multipart part cannot be read */
    public static UploadedFile of(MultipartFile file) throws IOException {
        return new UploadedFile(file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    /** A fresh stream over the content; callers may open as many as they need. */
    public InputStream open() {
        return new ByteArrayInputStream(content);
    }

    public int size() {
        return content.length;
    }

    /** The lower-cased extension without the dot, or empty when the name has none. */
    public String extension() {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1
                ? ""
                : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
