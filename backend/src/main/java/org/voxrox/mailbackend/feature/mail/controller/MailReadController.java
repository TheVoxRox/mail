package org.voxrox.mailbackend.feature.mail.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.dto.PagedResponse;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.mail.dto.MailContentResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.dto.ThreadResponse;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import module java.base;

/**
 * Read-only REST API for reading messages: paginated folder listing, search,
 * message detail, the body content itself and streaming attachments. Write-side
 * operations (send, drafts) live in {@link MailWriteController}; message-level
 * actions (flags, delete, sync) live in {@link MailActionController}.
 */
@Tag(name = "Messages — Read", description = "Paginated folder listing, search, message detail, attachment download.")
@RestController
@RequestMapping("/api/v1/messages")
@Validated
public class MailReadController {

    private static final Logger log = LoggerFactory.getLogger(MailReadController.class);
    private static final String DEFAULT_FILENAME = "unnamed";

    private final MailFacade mailFacade;
    private final MailClientProperties mailProps;

    public MailReadController(MailFacade mailFacade, MailClientProperties mailProps) {
        this.mailFacade = mailFacade;
        this.mailProps = mailProps;
    }

    @Operation(summary = "List messages in folder", description = "Returns a paginated list of messages (summary DTO) in the given folder by folderRef. The default size is taken from MailClientProperties; the cap is apiMaxPageSize.")
    @GetMapping("/account/{accountId}/folder")
    public PagedResponse<MailSummaryResponse> getMessagesByFolder(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @RequestParam @NotBlank(message = "{validation.notBlank}") @Size(max = 255, message = "{validation.size.max}") String folderRef,
            @RequestParam(required = false) @Min(value = 0, message = "{validation.min}") Integer page,
            @RequestParam(required = false) @Min(value = 1, message = "{validation.min}") Integer size) {
        return getMessages(accountId, folderRef, page, size);
    }

    private PagedResponse<MailSummaryResponse> getMessages(Long accountId, String folderRef, Integer page,
            Integer size) {
        int finalPage = Objects.requireNonNullElse(page, 0);
        int finalSize = Objects.requireNonNullElse(size, mailProps.sync().defaultPageSize());
        ensurePageSizeWithinLimit(finalSize);

        log.debug("{} Loading messages: account {}, folder {}, page {}, size {}", LogCategory.API, accountId, folderRef,
                finalPage, finalSize);

        return PagedResponse.from(mailFacade.getEmails(accountId, folderRef, finalPage, finalSize));
    }

    @Operation(summary = "Fulltext message search", description = "Searches messages of an account across folders by the given query (subject/from/body). The query must not be empty and is capped at searchQueryMaxLength characters.")
    @GetMapping("/account/{accountId}/search")
    public PagedResponse<MailSummaryResponse> searchMessages(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId, @RequestParam("q") String query,
            @RequestParam(required = false) @Min(value = 0, message = "{validation.min}") Integer page,
            @RequestParam(required = false) @Min(value = 1, message = "{validation.min}") Integer size) {

        String trimmedQuery = query == null ? "" : query.strip();
        if (trimmedQuery.isEmpty()) {
            throw new ValidationException("Search query must not be empty.", "validation.searchQueryRequired");
        }
        int maxLen = mailProps.sync().searchQueryMaxLength();
        if (trimmedQuery.length() > maxLen) {
            throw new ValidationException(String.format("Search query is too long (max. %d characters).", maxLen),
                    "validation.searchQueryTooLong", maxLen);
        }

        int finalPage = Objects.requireNonNullElse(page, 0);
        int finalSize = Objects.requireNonNullElse(size, mailProps.sync().defaultPageSize());
        ensurePageSizeWithinLimit(finalSize);

        log.info("{} Search (account {}): {} query chars, page {}, size {}", LogCategory.SEARCH, accountId,
                trimmedQuery.length(), finalPage, finalSize);

        return PagedResponse.from(mailFacade.searchEmails(accountId, trimmedQuery, finalPage, finalSize));
    }

    @Operation(summary = "Message detail", description = "Returns the full message detail including headers, recipients and attachment list (metadata, not content). For the message body see /content.")
    @GetMapping("/{stableId}")
    public MailDetailResponse getMessageDetail(
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId) {
        log.debug("{} Message detail: {}", LogCategory.API, stableId);
        return mailFacade.getEmailDetailByStableId(stableId);
    }

    @Operation(summary = "Conversation (thread) detail", description = "Returns every message in the given conversation as a single ordered list. "
            + "Ownership is enforced by the {accountId} path component — only threads owned by "
            + "that account are reachable. The list is ordered by threadPosition ascending "
            + "(matching receivedAt ascending). For per-message bodies fetch each member's "
            + "/content endpoint separately.")
    @ApiResponse(responseCode = "200", description = "Thread members in ascending order.")
    @ApiResponse(responseCode = "404", description = "No thread with that id is owned by the account.")
    @GetMapping("/account/{accountId}/threads/{threadId}")
    public ThreadResponse getThread(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 36, message = "{validation.size.max}") String threadId) {
        log.debug("{} Thread detail: account {}, thread {}", LogCategory.API, accountId, threadId);
        return mailFacade.getThread(accountId, threadId);
    }

    @Operation(summary = "Message content (body)", description = "Returns only the message body (HTML / plain text) without headers and metadata. Use after /detail to load the body lazily.")
    @GetMapping("/{stableId}/content")
    public MailContentResponse getMessageContent(
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId) {
        log.debug("{} Message content: {}", LogCategory.API, stableId);
        return mailFacade.getMessageContentOnly(stableId);
    }

    /**
     * Streams an attachment to the client. Content-Type is guessed from the file
     * name (fallback {@code application/octet-stream}); the name in the
     * Content-Disposition header is sent in both ASCII and RFC 5987 (UTF-8) forms.
     *
     * @param stableId
     *            stable message identifier for the public API
     * @param partPath
     *            path of the MIME part inside the message
     * @param fileName
     *            optional file name for the client; if missing, {@code "unnamed"}
     *            is used
     */
    @Operation(summary = "Download attachment (stream)", description = "Streams the binary attachment content to the client. Content-Type is derived from the file name (fallback application/octet-stream); "
            + "Content-Disposition contains both the ASCII and the UTF-8 (RFC 5987) form of the name.")
    @ApiResponse(responseCode = "200", description = "Binary attachment payload streamed to the client.", content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{stableId}/attachments/{partPath:.+}")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId,
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 64, message = "{validation.size.max}") String partPath,
            @RequestParam(required = false) @Size(max = 255, message = "{validation.size.max}") String fileName) {

        final String finalFileName = (fileName != null && !fileName.isBlank()) ? fileName : DEFAULT_FILENAME;
        String contentType = Objects.requireNonNullElse(URLConnection.guessContentTypeFromName(finalFileName),
                MediaType.APPLICATION_OCTET_STREAM_VALUE);

        String contentDisposition = buildAttachmentDisposition(finalFileName);

        StreamingResponseBody responseBody = outputStream -> {
            try (InputStream is = mailFacade.getAttachment(stableId, partPath)) {
                if (is != null) {
                    is.transferTo(outputStream);
                    outputStream.flush();
                }
            }
        };

        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition).body(responseBody);
    }

    /**
     * Page size cap — without it the client could exhaust memory via size=1000000.
     * Validate after applying the default so that `size=null` (= let the server
     * pick the default) passes through.
     */
    private void ensurePageSizeWithinLimit(int size) {
        int max = mailProps.sync().apiMaxPageSize();
        if (size > max) {
            throw new ValidationException(
                    String.format("Page size exceeds the maximum of %d (requested: %d).", max, size),
                    "validation.pageSizeTooLarge", max, size);
        }
    }

    private String buildAttachmentDisposition(String fileName) {
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ContentDisposition.attachment().filename(sanitizeAsciiFileName(fileName)).build() + "; filename*=UTF-8''"
                + encodedFileName;
    }

    private String sanitizeAsciiFileName(String fileName) {
        StringBuilder safe = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c >= 0x20 && c <= 0x7e && c != '"' && c != '\\' && c != '/' && c != ';') {
                safe.append(c);
            } else {
                safe.append('_');
            }
        }
        String cleaned = safe.toString().strip();
        return cleaned.isEmpty() ? DEFAULT_FILENAME : cleaned;
    }
}
