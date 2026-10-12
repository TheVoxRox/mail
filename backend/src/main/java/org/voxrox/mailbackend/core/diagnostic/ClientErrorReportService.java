package org.voxrox.mailbackend.core.diagnostic;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * Writes the errors the frontend reports into {@code mail.log}, which is how
 * they reach the diagnostic bundle: the webview's console is not kept anywhere
 * in a release build.
 *
 * <p>
 * The log is local and keeps 10 MB a file, so what goes in is bounded twice.
 * Each field is cut to a length that holds a real error, and at most
 * {@value #MAX_REPORTS_PER_MINUTE} reports a minute are written: an error
 * thrown on every frame would otherwise rotate the history out of the log
 * within a minute, and that history is what the bundle is for. What was dropped
 * is counted and written once the minute is over.
 *
 * <p>
 * The route is cut to its SvelteKit route id as in {@code client-boot.json},
 * and so is the path of a source that is a page rather than a script: the same
 * error on the same page then reads as the same line, whichever account,
 * folder or message was open; the log lines around it name those.
 */
@Service
public class ClientErrorReportService {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorReportService.class);

    private static final int MAX_REPORTS_PER_MINUTE = 20;

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_STACK_LENGTH = 4000;
    private static final int MAX_SOURCE_LENGTH = 300;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_CONTEXT_VALUE_LENGTH = 100;
    private static final int MAX_CONTEXT_ENTRIES = 5;
    private static final Set<String> KINDS = Set.of("window_error", "unhandled_rejection", "manual");
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern CONTEXT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,39}");
    /** Where the built frontend's scripts live; such a path names no data. */
    private static final String SCRIPT_PATH = "/_app/";

    private final Clock clock;

    private @Nullable Instant windowStart;
    private int writtenInWindow;
    private int droppedInWindow;

    public ClientErrorReportService() {
        this(Clock.systemUTC());
    }

    ClientErrorReportService(Clock clock) {
        this.clock = clock;
    }

    public void report(@Nullable ClientErrorReportRequest request) {
        if (request == null || !admit()) {
            return;
        }
        log.warn("{} {}", LogCategory.CLIENT, describe(request));
    }

    /**
     * Whether this report may be written, and the line for the reports dropped in
     * the minute that just ended. Synchronized: reports arrive on as many request
     * threads as the client has errors in flight.
     */
    private synchronized boolean admit() {
        Instant now = clock.instant();
        if (windowStart == null || !now.isBefore(windowStart.plus(Duration.ofMinutes(1)))) {
            if (droppedInWindow > 0) {
                log.warn("{} {} more client error report(s) dropped in the minute from {}: more than {} a minute.",
                        LogCategory.CLIENT, droppedInWindow, windowStart, MAX_REPORTS_PER_MINUTE);
            }
            windowStart = now;
            writtenInWindow = 0;
            droppedInWindow = 0;
        }
        if (writtenInWindow >= MAX_REPORTS_PER_MINUTE) {
            droppedInWindow++;
            return false;
        }
        writtenInWindow++;
        return true;
    }

    private static String describe(ClientErrorReportRequest request) {
        String reportedKind = request.kind();
        String kind = reportedKind != null && KINDS.contains(reportedKind) ? reportedKind : "unknown";
        StringBuilder text = new StringBuilder(kind).append(':');
        String name = cut(request.name(), MAX_NAME_LENGTH);
        if (name != null) {
            text.append(' ').append(name).append(':');
        }
        text.append(' ').append(Objects.requireNonNullElse(cut(request.message(), MAX_MESSAGE_LENGTH), "(no message)"));
        String route = ClientBootDiagnosticsService.sanitizeRoute(request.route());
        if (route != null) {
            text.append(", route ").append(route);
        }
        String source = source(request.source());
        if (source != null) {
            text.append(", source ").append(source);
            Integer line = request.line();
            Integer column = request.column();
            if (line != null) {
                text.append(':').append(line);
                if (column != null) {
                    text.append(':').append(column);
                }
            }
        }
        String context = context(request.context());
        if (!context.isEmpty()) {
            text.append(", context ").append(context);
        }
        String eventId = request.eventId();
        if (eventId != null && EVENT_ID.matcher(eventId).matches()) {
            text.append(", event ").append(eventId);
        }
        String stack = stack(request.stack());
        if (stack != null) {
            text.append(", stack: ").append(stack);
        }
        return text.toString();
    }

    /**
     * A script's URL as it is, without a query; a page's URL with its path cut to
     * the route id, as {@link ClientBootDiagnosticsService#sanitizeRoute} cuts a
     * route.
     */
    private static @Nullable String source(@Nullable String value) {
        String source = cut(value, MAX_SOURCE_LENGTH);
        if (source == null) {
            return null;
        }
        int end = source.length();
        for (char stop : new char[]{'?', '#'}) {
            int index = source.indexOf(stop);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        source = source.substring(0, end);
        int scheme = source.indexOf("://");
        int pathStart = scheme >= 0 ? source.indexOf('/', scheme + 3) : 0;
        if (pathStart < 0) {
            return source;
        }
        String origin = source.substring(0, pathStart);
        String path = source.substring(pathStart);
        if (path.startsWith(SCRIPT_PATH)) {
            return origin + path;
        }
        String route = ClientBootDiagnosticsService.sanitizeRoute(path);
        return origin + (route != null ? route : "");
    }

    /**
     * The frames on one line, so the CR/LF guard of the log does not run them
     * together with underscores.
     */
    private static @Nullable String stack(@Nullable String value) {
        String stack = cut(value, MAX_STACK_LENGTH);
        if (stack == null) {
            return null;
        }
        return stack.lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining(" | "));
    }

    private static String context(@Nullable Map<String, String> context) {
        if (context == null) {
            return "";
        }
        return context.entrySet().stream()
                .filter(entry -> CONTEXT_KEY.matcher(entry.getKey()).matches() && cut(entry.getValue(), 1) != null)
                .sorted(Map.Entry.comparingByKey()).limit(MAX_CONTEXT_ENTRIES)
                .map(entry -> entry.getKey() + "=" + cut(entry.getValue(), MAX_CONTEXT_VALUE_LENGTH))
                .collect(Collectors.joining(" "));
    }

    private static @Nullable String cut(@Nullable String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() > maxLength ? stripped.substring(0, maxLength) + "..." : stripped;
    }
}
