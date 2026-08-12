package org.voxrox.mailbackend.feature.mail.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Block;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * Turns the composed message body — plain text that may contain Markdown — into
 * the {@code text/html} alternative that {@link MimeMessageBuilder} sends
 * alongside the unmodified source as {@code text/plain}.
 *
 * <p>
 * The composer is a plain {@code textarea} and stays one: Markdown is
 * interpreted on the way out, never on the way in. That keeps the editing
 * surface a plain-text control (the one a screen reader handles best) and keeps
 * the user's literal keystrokes as the {@code text/plain} part, so a recipient
 * with a text-only client reads exactly what was typed.
 *
 * <p>
 * <b>Nothing is rendered unless the body actually uses Markdown.</b>
 * {@link #renderAlternative} returns empty for a body whose parse tree carries
 * no formatting, and the message then goes out single-part {@code text/plain},
 * byte for byte as before this class existed. Plain-text correspondents and
 * mailing lists that dislike HTML mail are unaffected by anyone who does not
 * type Markdown.
 *
 * <p>
 * Rendering rules that deviate from stock CommonMark, all for the same reason —
 * an e-mail body is prose that people also indent and line-break by hand:
 * <ul>
 * <li><b>Soft line breaks become <code>&lt;br&gt;</code>.</b> CommonMark folds
 * a single newline into a space; in a message that would silently reflow
 * addresses, lists of times and signatures into one paragraph. Same choice
 * GitHub comments make.</li>
 * <li><b>Indented code blocks are off.</b> Four leading spaces are ordinary
 * indentation in a mail, not a request for <code>&lt;pre&gt;</code>. Fenced
 * blocks ({@code ```}) remain the explicit gesture.</li>
 * <li><b>HTML blocks are off and inline HTML is escaped.</b> The two parts of
 * the message must say the same thing: whatever the {@code text/plain} part
 * shows literally, the HTML part shows literally too. This also means the
 * renderer cannot emit markup the user did not ask for, so its output needs no
 * sanitizer pass — unlike inbound content, which
 * {@link org.voxrox.mailbackend.util.HtmlSanitizer} handles.</li>
 * </ul>
 */
@Component
public class MarkdownBodyRenderer {

    /**
     * Bodies above this stay plain. The request cap is 10 MB
     * ({@code MailRequest#body}), which is far past what any hand-composed message
     * is and well into the range where a pathological input could make the parser
     * the slowest thing in the send path. A message that long is pasted machine
     * output, not prose someone formatted.
     */
    private static final int MAX_RENDERED_CHARS = 512 * 1024;

    /**
     * Block types the parser recognizes — the CommonMark default set minus
     * {@code IndentedCodeBlock} and {@code HtmlBlock}. See the class javadoc for
     * why those two are dropped rather than merely escaped.
     */
    private static final Set<Class<? extends Block>> ENABLED_BLOCKS = Set.of(Heading.class, FencedCodeBlock.class,
            BlockQuote.class, ListBlock.class, ThematicBreak.class);

    /**
     * Node types whose HTML rendering says exactly what the {@code text/plain} part
     * already says. A document built from nothing else gained no formatting from
     * being parsed as Markdown, so it does not need an HTML alternative.
     *
     * <p>
     * {@link HtmlInline} belongs here because {@code escapeHtml} renders it as
     * literal text — a body that merely mentions <code>&lt;b&gt;</code> reads
     * identically either way. {@link SoftLineBreak} and {@link HardLineBreak} both
     * render as <code>&lt;br&gt;</code>, which is what the newline in the source
     * part means.
     */
    private static final Set<Class<? extends Node>> INERT_NODES = Set.of(Document.class, Paragraph.class, Text.class,
            SoftLineBreak.class, HardLineBreak.class, HtmlInline.class);

    /*
     * Both are documented as thread-safe and immutable after build(), and neither
     * holds per-document state, so one shared instance serves every send.
     *
     * Source spans are what demoteSetextHeadings uses to tell a setext heading from
     * an ATX one; BLOCKS is the cheaper of the two levels and all that is needed,
     * since the distinction is per block. They do not affect parsing itself —
     * verified by comparing trees with and without them.
     */
    private static final Parser PARSER = Parser.builder().enabledBlockTypes(ENABLED_BLOCKS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS).build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true)
            .softbreak("<br />\n").build();

    /**
     * Renders the HTML alternative for a composed body, if it needs one.
     *
     * @param markdown
     *            the composed body exactly as typed
     * @return the HTML document to send as the {@code text/html} alternative, or
     *         empty when the body uses no Markdown and should go out as a single
     *         {@code text/plain} part
     */
    public Optional<String> renderAlternative(String markdown) {
        if (markdown == null || markdown.isBlank() || markdown.length() > MAX_RENDERED_CHARS) {
            return Optional.empty();
        }

        Node document = PARSER.parse(markdown);
        demoteSetextHeadings(document, markdown);
        if (isInert(document)) {
            return Optional.empty();
        }

        return Optional.of(wrapAsDocument(RENDERER.render(document)));
    }

    /**
     * Turns setext headings ({@code Title} with {@code ---} or {@code ===} under
     * it) back into paragraphs, keeping the underline as literal text.
     *
     * <p>
     * A horizontal rule is ordinary punctuation in a signature or a mail, and
     * CommonMark reads the line above one as a heading. An ordinary signature — a
     * name with a rule under it — therefore came out as a level-two heading that
     * had swallowed both the RFC 3676 separator and the sender's name, and it
     * switched a message the user wrote as plain text into
     * {@code multipart/alternative}.
     *
     * <p>
     * Same reasoning as indented code blocks being off: formatting in a mail should
     * be an explicit gesture. {@code # Title} stays a heading, because writing it
     * is deliberate; a line of dashes under a name is not. The two cannot be
     * separated through {@code enabledBlockTypes} — CommonMark has one
     * {@code Heading} class for both — but they differ in the source: ATX is always
     * one line, setext spans the text plus its underline, so the block's source
     * span count tells them apart.
     *
     * <p>
     * The underline is re-appended as text rather than dropped. The rule that the
     * {@code text/plain} and {@code text/html} parts must say the same thing holds
     * here too: the dashes are in what the user typed, so they belong in both.
     */
    private static void demoteSetextHeadings(Node document, String markdown) {
        List<Heading> setextHeadings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                // ATX occupies exactly one source line; setext occupies its text
                // lines plus the underline, so anything past one line is setext.
                if (heading.getSourceSpans().size() > 1) {
                    setextHeadings.add(heading);
                }
                visitChildren(heading);
            }
        });

        if (setextHeadings.isEmpty()) {
            // Split the source only when there is something to rewrite: bodies
            // reach 512 kB here and almost none of them contain a setext heading.
            return;
        }

        String[] lines = markdown.split("\n", -1);
        for (Heading heading : setextHeadings) {
            Paragraph paragraph = new Paragraph();
            for (Node child = heading.getFirstChild(); child != null;) {
                Node next = child.getNext();
                paragraph.appendChild(child);
                child = next;
            }

            List<SourceSpan> spans = heading.getSourceSpans();
            int underlineLine = spans.get(spans.size() - 1).getLineIndex();
            if (underlineLine >= 0 && underlineLine < lines.length) {
                paragraph.appendChild(new SoftLineBreak());
                paragraph.appendChild(new Text(lines[underlineLine]));
            }

            heading.insertBefore(paragraph);
            heading.unlink();
        }
    }

    /**
     * True when the parse tree contains only node types from {@link #INERT_NODES},
     * i.e. the source text survived parsing unformatted.
     */
    private static boolean isInert(Node document) {
        InertVisitor visitor = new InertVisitor();
        document.accept(visitor);
        return visitor.inert;
    }

    /**
     * Wraps the rendered fragment in a minimal HTML document. No stylesheet: the
     * markup is semantic (headings, lists, {@code blockquote}, {@code pre}) and
     * receiving clients style it with their own reader defaults, which is both the
     * most portable and the most accessible outcome.
     */
    private static String wrapAsDocument(String bodyHtml) {
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n</head>\n<body>\n" + bodyHtml
                + "</body>\n</html>\n";
    }

    /**
     * Visits every node and records whether any of them carries formatting.
     * {@link AbstractVisitor} has a typed {@code visit} per node class and a
     * generic fallback, so overriding {@link #visitChildren} is the one hook that
     * sees all of them, including types added by a future CommonMark version —
     * which then count as formatting (fail towards rendering, never towards
     * silently dropping it).
     */
    private static final class InertVisitor extends AbstractVisitor {

        private boolean inert = true;

        @Override
        protected void visitChildren(Node parent) {
            if (!INERT_NODES.contains(parent.getClass())) {
                inert = false;
                return;
            }
            super.visitChildren(parent);
        }
    }
}
