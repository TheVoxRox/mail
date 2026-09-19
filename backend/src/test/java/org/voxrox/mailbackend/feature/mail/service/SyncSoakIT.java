package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The accelerated soak that replaces the overnight run of RELEASE_CHECKLIST
 * §8.2. It runs only in the {@code soak} Maven profile
 * ({@code mvn -Psoak verify}), which the scheduled {@code soak.yml} workflow
 * uses.
 * <p>
 * The backend is a separate process started from the jar {@code mvn package}
 * built, because two of the faults need a process: a hard kill in the middle of
 * the work, and (on Linux) a pause with {@code SIGSTOP}. It syncs a GreenMail
 * mailbox through {@link TcpFaultProxy} with every interval shortened, while
 * this test keeps delivering, flagging and deleting mail, and on a timeline
 * drops the connections, pauses the process, makes the server unreachable,
 * kills and restarts the backend, and — when {@code soak.small-disk} names a
 * small filesystem the data directory lives on — fills the disk.
 * <p>
 * What has to hold at the end: the database passes {@code quick_check}; the
 * local mirror of the inbox has no message the server does not have and no hole
 * inside its window; flags agree with the server; no Message-ID is stored
 * twice; the heap did not grow without bound and the WAL stayed bounded. The
 * workflow then runs {@code npm run release:scan-logs} over the logs. A report
 * with every sample is written to {@code soak-report.json} either way.
 * <p>
 * Properties: {@code soak.duration} (ISO-8601, default {@code PT12M}),
 * {@code soak.dir} (work directory, default {@code target/soak}),
 * {@code soak.small-disk} (optional; the data directory is placed there).
 */
@Tag("soak")
class SyncSoakIT {

    private static final Duration DURATION = Duration.parse(System.getProperty("soak.duration", "PT12M"));
    private static final Path WORK = Path.of(System.getProperty("soak.dir", "target/soak")).toAbsolutePath()
            .normalize();
    private static final @Nullable String SMALL_DISK = blankToNull(System.getProperty("soak.small-disk"));
    private static final boolean UNIX = !System.getProperty("os.name").startsWith("Windows");

    private static final Duration SYNC_INTERVAL = Duration.ofSeconds(10);
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);
    private static final String EMAIL = "soak@greenmail.local";
    private static final String LOGIN = "soak-user";
    private static final String PASSWORD = "soak-password";
    private static final String INBOX = "INBOX";
    private static final long MAX_WAL_BYTES = 128L * 1024 * 1024;
    private static final long HEAP_SLACK_BYTES = 64L * 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Instant started = Instant.now();

    @Test
    void syncSurvivesADayCompressedIntoMinutes() throws Exception {
        Path dataDir = (SMALL_DISK != null ? Path.of(SMALL_DISK) : WORK).resolve("data");
        deleteRecursively(WORK);
        deleteRecursively(dataDir);
        Files.createDirectories(WORK);
        Files.createDirectories(dataDir);

        GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP));
        greenMail.start();
        GreenMailUser user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        int imapPort = greenMail.getImap().getPort();
        AtomicBoolean generating = new AtomicBoolean(true);
        AtomicInteger delivered = new AtomicInteger();
        Thread generator = null;
        final Backend[] current = {null};
        try (TcpFaultProxy proxy = new TcpFaultProxy("127.0.0.1", imapPort)) {
            current[0] = Backend.start(dataDir, WORK, 1);
            createAccount(current[0], proxy.port());
            event("backend up, account created");

            generator = Thread.ofPlatform().name("soak-mail")
                    .start(() -> generateMail(user, imapPort, generating, delivered));

            Instant end = started.plus(DURATION);
            List<Fault> timeline = new ArrayList<>(List.of(new Fault(0.20, "drop connections", proxy::dropConnections),
                    new Fault(0.50, "server unreachable for 30 s", () -> {
                        proxy.refuse();
                        sleep(Duration.ofSeconds(30));
                        proxy.restore();
                    })));
            timeline.add(new Fault(0.35, "process paused for 45 s", () -> {
                if (!UNIX) {
                    event("pause skipped: SIGSTOP needs a Unix host");
                    return;
                }
                current[0].signal("STOP");
                proxy.dropConnections();
                sleep(Duration.ofSeconds(45));
                current[0].signal("CONT");
            }));
            timeline.add(new Fault(0.65, "hard kill and restart", () -> {
                current[0].kill();
                current[0] = Backend.start(dataDir, WORK, 2);
            }));
            if (SMALL_DISK != null) {
                timeline.add(
                        new Fault(0.80, "disk full for 60 s", () -> fillDisk(Path.of(SMALL_DISK), user, current[0])));
            }
            timeline.sort(Comparator.comparingDouble(Fault::at));

            Instant nextSample = Instant.now();
            int nextFault = 0;
            Instant stopGenerating = started.plus(DURATION.multipliedBy(9).dividedBy(10));
            while (Instant.now().isBefore(end)) {
                if (nextFault < timeline.size()
                        && Instant.now().isAfter(started.plus(fraction(timeline.get(nextFault).at())))) {
                    Fault fault = timeline.get(nextFault++);
                    event("fault: " + fault.name());
                    fault.action().run();
                    event("fault over: " + fault.name());
                }
                if (Instant.now().isAfter(stopGenerating) && generating.getAndSet(false)) {
                    event("generator stopped after " + delivered.get() + " messages");
                }
                if (Instant.now().isAfter(nextSample)) {
                    sample(current[0], dataDir);
                    nextSample = Instant.now().plus(SAMPLE_INTERVAL);
                }
                sleep(Duration.ofSeconds(1));
            }
            generating.set(false);
            generator.join(Duration.ofSeconds(30));
            // Let the backend catch up with the last changes before judging the mirror.
            sleep(SYNC_INTERVAL.multipliedBy(4));
            sample(current[0], dataDir);
            current[0].stop();
            current[0] = null;
            event("backend stopped");

            verifyMirror(dataDir, imapPort);
            verifyResources();
        } finally {
            generating.set(false);
            if (current[0] != null) {
                current[0].kill();
            }
            greenMail.stop();
            writeReport(delivered.get());
        }
    }

    // --- The work the backend is put through ---------------------------------

    private void generateMail(GreenMailUser user, int imapPort, AtomicBoolean generating, AtomicInteger delivered) {
        int round = 0;
        while (generating.get()) {
            int n = delivered.incrementAndGet();
            user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", "Soak message " + n,
                    "Body of soak message " + n, new ServerSetup(imapPort, "127.0.0.1", "imap")));
            if (++round % 10 == 0) {
                try {
                    mutateInbox(imapPort, round);
                } catch (Exception e) {
                    event("generator mutation failed: " + e);
                }
            }
            sleep(Duration.ofSeconds(2));
        }
    }

    /** What another client does meanwhile: reads, stars and deletes. */
    private static void mutateInbox(int imapPort, int round) throws Exception {
        try (Store store = imapStore(imapPort)) {
            Folder inbox = store.getFolder(INBOX);
            inbox.open(Folder.READ_WRITE);
            int count = inbox.getMessageCount();
            if (count >= 3) {
                inbox.getMessage(count - 1).setFlag(Flags.Flag.SEEN, true);
                inbox.getMessage(count - 2).setFlag(Flags.Flag.FLAGGED, round % 20 == 0);
                if (round % 30 == 0) {
                    inbox.getMessage(1).setFlag(Flags.Flag.DELETED, true);
                }
            }
            inbox.close(true);
        }
    }

    private void fillDisk(Path disk, GreenMailUser user, Backend backend) {
        Path filler = disk.resolve("filler.bin");
        long written = 0;
        try (FileChannel channel = FileChannel.open(filler, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer chunk = ByteBuffer.allocate(1024 * 1024);
            while (true) {
                chunk.clear();
                written += channel.write(chunk);
            }
        } catch (IOException full) {
            event("disk filled after " + written / (1024 * 1024) + " MiB: " + full.getMessage());
        }
        String big = "x".repeat(512 * 1024);
        for (int i = 0; i < 5; i++) {
            user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", "Soak large " + i, big,
                    new ServerSetup(0, "127.0.0.1", "imap")));
        }
        sleep(Duration.ofSeconds(60));
        assertThat(backend.process.isAlive()).as("backend alive while the disk is full").isTrue();
        // A full disk may well make the database unreadable; the health check has to
        // say so (503, DOWN) rather than fail itself (500).
        int whileFull = backend.health();
        event("health while the disk is full: " + whileFull);
        assertThat(whileFull).as("health while the disk is full").isIn(200, 503);
        try {
            Files.delete(filler);
        } catch (IOException e) {
            throw new IllegalStateException("Could not free the disk", e);
        }
        Instant deadline = Instant.now().plus(SYNC_INTERVAL.multipliedBy(12));
        int afterwards = backend.health();
        while (afterwards != 200 && Instant.now().isBefore(deadline)) {
            sleep(SYNC_INTERVAL);
            afterwards = backend.health();
        }
        event("health once the disk has room again: " + afterwards);
        assertThat(afterwards).as("health once the disk has room again").isEqualTo(200);
    }

    private static void createAccount(Backend backend, int port) throws Exception {
        Map<String, Object> server = Map.of("host", "127.0.0.1", "port", port, "useSsl", false);
        Map<String, Object> body = Map.of("accountName", "Soak", "email", EMAIL, "username", LOGIN, "password",
                PASSWORD, "imap", server, "smtp", server);
        HttpResponse<String> response = backend.send(HttpRequest.newBuilder(backend.uri("/v1/accounts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build());
        assertThat(response.statusCode()).as("POST /v1/accounts: %s", response.body()).isEqualTo(201);
    }

    // --- Samples and verdicts ------------------------------------------------

    private void sample(Backend backend, Path dataDir) {
        Map<String, Object> sample = new HashMap<>();
        sample.put("atSeconds", Duration.between(started, Instant.now()).toSeconds());
        sample.put("walBytes", sizeOf(dataDir.resolve("db").resolve("mail.db-wal")));
        sample.put("dbBytes", sizeOf(dataDir.resolve("db").resolve("mail.db")));
        try {
            backend.jcmd("GC.run");
            JsonNode runtime = backend.dumpEntry("runtime.json");
            sample.put("heapUsedBytes",
                    runtime.get("totalMemoryBytes").asLong() - runtime.get("freeMemoryBytes").asLong());
        } catch (Exception e) {
            sample.put("heapError", String.valueOf(e.getMessage()));
        }
        samples.add(sample);
    }

    private void verifyMirror(Path dataDir, int imapPort) throws Exception {
        Map<Long, Flags> server = new HashMap<>();
        try (Store store = imapStore(imapPort)) {
            Folder inbox = store.getFolder(INBOX);
            inbox.open(Folder.READ_ONLY);
            UIDFolder uids = (UIDFolder) inbox;
            for (Message message : inbox.getMessages()) {
                server.put(uids.getUID(message), message.getFlags());
            }
            inbox.close(false);
        }

        String url = "jdbc:sqlite:" + dataDir.resolve("db").resolve("mail.db");
        try (Connection db = DriverManager.getConnection(url); Statement statement = db.createStatement()) {
            try (ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
                assertThat(check.next() ? check.getString(1) : "no result").as("quick_check").isEqualTo("ok");
            }
            long accountId;
            try (ResultSet account = statement.executeQuery("SELECT id FROM accounts")) {
                assertThat(account.next()).as("the soak account exists").isTrue();
                accountId = account.getLong(1);
            }

            Map<Long, boolean[]> local = new HashMap<>();
            try (PreparedStatement rows = db.prepareStatement(
                    "SELECT uid, seen, flagged FROM messages WHERE account_id = ? AND folder_name = ?")) {
                rows.setLong(1, accountId);
                rows.setString(2, INBOX);
                try (ResultSet rs = rows.executeQuery()) {
                    while (rs.next()) {
                        local.put(rs.getLong(1), new boolean[]{rs.getBoolean(2), rs.getBoolean(3)});
                    }
                }
            }
            assertThat(local).as("local inbox").isNotEmpty();

            Set<Long> ghosts = new TreeSet<>(local.keySet());
            ghosts.removeAll(server.keySet());
            assertThat(ghosts).as("local rows the server no longer has").isEmpty();

            long floor = local.keySet().stream().min(Long::compare).orElseThrow();
            Set<Long> holes = new TreeSet<>();
            for (Long uid : server.keySet()) {
                if (uid >= floor && !local.containsKey(uid)) {
                    holes.add(uid);
                }
            }
            assertThat(holes).as("server messages missing inside the local window").isEmpty();

            List<String> flagMismatches = new ArrayList<>();
            for (Map.Entry<Long, boolean[]> row : local.entrySet()) {
                Flags flags = server.get(row.getKey());
                boolean seen = flags.contains(Flags.Flag.SEEN);
                boolean flagged = flags.contains(Flags.Flag.FLAGGED);
                if (row.getValue()[0] != seen || row.getValue()[1] != flagged) {
                    flagMismatches.add("uid " + row.getKey());
                }
            }
            assertThat(flagMismatches).as("flags that differ from the server").isEmpty();

            try (PreparedStatement duplicates = db.prepareStatement("SELECT message_id FROM messages "
                    + "WHERE account_id = ? AND folder_name = ? AND message_id IS NOT NULL "
                    + "GROUP BY message_id HAVING COUNT(*) > 1")) {
                duplicates.setLong(1, accountId);
                duplicates.setString(2, INBOX);
                try (ResultSet rs = duplicates.executeQuery()) {
                    assertThat(rs.next()).as("a Message-ID stored twice in the inbox").isFalse();
                }
            }
            event("mirror verified: " + local.size() + " local rows, " + server.size() + " on the server");
        }
    }

    private void verifyResources() {
        long maxWal = samples.stream().mapToLong(s -> (Long) s.get("walBytes")).max().orElse(0);
        assertThat(maxWal).as("largest WAL observed").isLessThanOrEqualTo(MAX_WAL_BYTES);

        List<Long> heap = samples.stream().filter(s -> s.containsKey("heapUsedBytes"))
                .map(s -> (Long) s.get("heapUsedBytes")).toList();
        assertThat(heap).as("heap samples").hasSizeGreaterThan(3);
        // The first samples cover startup and the initial download; the baseline is
        // the smallest heap after them.
        long baseline = heap.subList(2, heap.size()).stream().min(Long::compare).orElseThrow();
        long last = heap.getLast();
        assertThat(last).as("heap after a full GC at the end, against the baseline %d", baseline)
                .isLessThanOrEqualTo(baseline * 2 + HEAP_SLACK_BYTES);
    }

    private void writeReport(int delivered) {
        Map<String, Object> report = new HashMap<>();
        report.put("duration", DURATION.toString());
        report.put("smallDisk", SMALL_DISK);
        report.put("delivered", delivered);
        report.put("events", events);
        report.put("samples", samples);
        try {
            Files.createDirectories(WORK);
            Files.writeString(WORK.resolve("soak-report.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the soak report", e);
        }
    }

    // --- Helpers -------------------------------------------------------------

    private synchronized void event(String what) {
        String line = Duration.between(started, Instant.now()).toSeconds() + "s " + what;
        events.add(line);
        System.out.println("[soak] " + line);
    }

    private static Duration fraction(double at) {
        return Duration.ofMillis((long) (DURATION.toMillis() * at));
    }

    private static Store imapStore(int port) throws Exception {
        Store store = Session.getInstance(new Properties()).getStore("imap");
        store.connect("127.0.0.1", port, LOGIN, PASSWORD);
        return store;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            return -1L;
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private record Fault(double at, String name, Runnable action) {
    }

    /** The backend as a process of its own, started from the packaged jar. */
    private static final class Backend {

        private final Process process;
        private final HttpClient http = HttpClient.newHttpClient();
        private final String baseUrl;
        private final String apiKey;

        private Backend(Process process, String baseUrl, String apiKey) {
            this.process = process;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }

        static Backend start(Path dataDir, Path work, int generation) {
            try {
                Files.deleteIfExists(dataDir.resolve(".ready"));
                Files.deleteIfExists(dataDir.resolve("session.json"));
                Path java = Path.of(System.getProperty("java.home"), "bin", UNIX ? "java" : "java.exe");
                List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx256m", "-jar",
                        packagedJar().toString(), "--app.data-dir=" + dataDir,
                        "--spring.security.oauth2.client.registration.google.client-id=soak",
                        "--spring.security.oauth2.client.registration.google.client-secret=soak",
                        "--spring.security.oauth2.client.registration.microsoft.client-id=soak",
                        "--mail.client.sync.interval=" + SYNC_INTERVAL, "--mail.client.sync.initial-delay=PT2S",
                        "--mail.client.sync.uid-enumeration-interval=PT1M",
                        "--mail.client.db.reclaim-initial-delay=PT1M", "--mail.client.db.reclaim-interval=PT3M",
                        "--mail.client.db.optimize-initial-delay=PT1M", "--mail.client.db.optimize-interval=PT4M",
                        "--mail.client.imap.read-timeout=10s", "--mail.client.imap.connection-timeout=5s"));
                Process process = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(work.resolve("backend-" + generation + ".out").toFile()).start();
                Path ready = dataDir.resolve(".ready");
                Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
                while (!Files.exists(ready)) {
                    if (!process.isAlive() || Instant.now().isAfter(deadline)) {
                        process.destroyForcibly();
                        throw new IllegalStateException("Backend " + generation + " did not become ready; see "
                                + work.resolve("backend-" + generation + ".out"));
                    }
                    sleep(Duration.ofMillis(250));
                }
                JsonNode session = new ObjectMapper().readTree(Files.readString(dataDir.resolve("session.json")));
                return new Backend(process, session.get("baseUrl").asString(), session.get("apiKey").asString());
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the backend", e);
            }
        }

        private static Path packagedJar() throws IOException {
            try (Stream<Path> files = Files.list(Path.of("target"))) {
                return files.filter(p -> p.getFileName().toString().matches("mail-backend-.*\\.jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-plain.jar")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No packaged jar in target/; run the "
                                + "soak through mvn -Psoak verify, which packages it first"))
                        .toAbsolutePath();
            }
        }

        URI uri(String path) {
            return URI.create(baseUrl + path);
        }

        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
            HttpRequest keyed = HttpRequest.newBuilder(request, (name, value) -> true).header("X-API-KEY", apiKey)
                    .build();
            return http.send(keyed, HttpResponse.BodyHandlers.ofString());
        }

        int health() {
            try {
                return send(HttpRequest.newBuilder(uri("/internal/health")).GET().build()).statusCode();
            } catch (IOException e) {
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        JsonNode dumpEntry(String name) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri("/internal/diagnostic-dump")).header("X-API-KEY", apiKey)
                    .GET().build();
            byte[] zip = http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    if (entry.getName().equals(name)) {
                        return new ObjectMapper().readTree(in.readAllBytes());
                    }
                }
            }
            throw new IOException(name + " not in the dump");
        }

        void jcmd(String command) throws IOException, InterruptedException {
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", UNIX ? "jcmd" : "jcmd.exe");
            Process jcmdProcess = new ProcessBuilder(jcmd.toString(), String.valueOf(process.pid()), command)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            jcmdProcess.waitFor(30, TimeUnit.SECONDS);
        }

        void signal(String name) {
            try {
                new ProcessBuilder("kill", "-" + name, String.valueOf(process.pid())).inheritIO().start().waitFor(10,
                        TimeUnit.SECONDS);
            } catch (IOException e) {
                throw new IllegalStateException("kill -" + name + " failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void kill() {
            process.destroyForcibly();
            waitFor();
        }

        void stop() {
            process.destroy();
            if (!waitFor()) {
                process.destroyForcibly();
                waitFor();
            }
        }

        private boolean waitFor() {
            try {
                return process.waitFor(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
