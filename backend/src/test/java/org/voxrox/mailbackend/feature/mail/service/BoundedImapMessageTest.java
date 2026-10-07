package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;

import org.eclipse.angus.mail.iap.Protocol;
import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.eclipse.angus.mail.imap.protocol.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BoundedImapMessage} bounding what a fetch merges into the headers a
 * message holds (IMAP/SMTP audit B1-19). Each header item goes to the message's
 * {@code handleFetchItem} the way {@code IMAPFolder.fetch} hands it over, for a
 * fetch that asks for some headers rather than all. Nothing connects: the store
 * and the folder are only what a message needs to exist. What the message holds
 * is read off {@code MimeMessage.headers} itself, since {@code getHeader} would
 * fetch a header the message has not loaded from the server.
 */
class BoundedImapMessageTest {

    private BoundedImapMessage message;

    @BeforeEach
    void setUp() {
        Session session = Session.getInstance(new Properties());
        BoundedImapStore store = new BoundedImapStore(session, new URLName("imap://user@localhost"));
        message = new BoundedImapMessage(new BoundedImapFolder("INBOX", '/', store, null), 1);
    }

    @Test
    @DisplayName("A header item merges into the headers a message holds")
    void aHeaderItemMergesIntoTheHeadersHeld() throws Exception {
        handle(headerItem("Message-ID", "Message-ID: <a@example.test>\r\n"), "Message-ID");

        handle(headerItem("References", "References: <b@example.test>\r\n"), "References");

        assertThat(held().getHeader("Message-ID")).containsExactly("<a@example.test>");
        assertThat(held().getHeader("References")).containsExactly("<b@example.test>");
    }

    /**
     * Found by the pass over 1.56. Each header Angus merges scans the whole list
     * before it inserts, so a server that merged item after item into one message
     * made the cost the square of their headers: 160,000 took 163 s. A message
     * merges {@link BoundedImapMessage#MAX_MERGED_HEADER_LINES} lines over its
     * life, counted across items, and an item past that is not merged.
     */
    @Test
    @DisplayName("A message merges up to its bound, counted across items, and no item past it")
    void mergesStopAtTheBound() throws Exception {
        handle(headerItem("Message-ID", "Message-ID: <a@example.test>\r\n"), "Message-ID");

        handle(headerItem("X", lines("X-Merged-", BoundedImapMessage.MAX_MERGED_HEADER_LINES - 1)), "X");
        handle(headerItem("X", "X-Last: fits\r\n"), "X");
        handle(headerItem("X", "X-Past: does not\r\n"), "X");

        assertThat(held().getHeader("X-Merged-0")).containsExactly("v");
        assertThat(held().getHeader("X-Last")).as("the item that reaches the bound").containsExactly("fits");
        assertThat(held().getHeader("X-Past")).as("the item past the bound").isNull();
    }

    /**
     * The first header item a message gets is loaded, not merged: Angus makes it
     * the message's headers, line by line, at a cost that grows with its size
     * alone, and the open-folder budget has charged every line (B1-16).
     */
    @Test
    @DisplayName("The headers a message first loads are not counted against what it may merge")
    void theFirstLoadIsNotCounted() throws Exception {
        handle(headerItem("X", lines("X-Loaded-", 2 * BoundedImapMessage.MAX_MERGED_HEADER_LINES)), "X");

        handle(headerItem("X", lines("X-Merged-", BoundedImapMessage.MAX_MERGED_HEADER_LINES)), "X");

        assertThat(held().getHeader("X-Loaded-0")).containsExactly("v");
        assertThat(held().getHeader("X-Merged-" + (BoundedImapMessage.MAX_MERGED_HEADER_LINES - 1)))
                .containsExactly("v");
    }

    /** {@code count} header lines, {@code prefix} and a number each. */
    private static String lines(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(n -> prefix + n + ": v\r\n").collect(Collectors.joining());
    }

    /**
     * A header item of a FETCH, parsed the way the protocol parses one: the section
     * the fetch asked for, and these header lines.
     */
    private static Item headerItem(String fields, String headerLines) throws Exception {
        String data = headerLines + "\r\n";
        String line = "* 1 FETCH (BODY[HEADER.FIELDS (" + fields + ")] {"
                + data.getBytes(StandardCharsets.US_ASCII).length + "}\r\n" + data + ")";
        return BoundedImapProtocol.parseFetch(new IMAPResponse(line), null, mock(Protocol.class), "test").getItem(0);
    }

    private void handle(Item item, String... asked) throws Exception {
        message.handleFetchItem(item, asked, false);
    }

    private InternetHeaders held() throws Exception {
        Field headers = MimeMessage.class.getDeclaredField("headers");
        headers.setAccessible(true);
        return (InternetHeaders) headers.get(message);
    }
}
