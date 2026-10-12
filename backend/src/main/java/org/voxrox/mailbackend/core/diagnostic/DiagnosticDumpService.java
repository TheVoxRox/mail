package org.voxrox.mailbackend.core.diagnostic;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.MailBackendApplication;
import org.voxrox.mailbackend.core.config.ApplicationVersion;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.core.init.HandshakeService;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.AccountLastErrorJson;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;
import tools.jackson.databind.ObjectMapper;

@Service
public class DiagnosticDumpService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticDumpService.class);

    private final AccountRepository accountRepository;
    private final FolderSyncStateRepository folderSyncStateRepository;
    private final MessageRepository messageRepository;
    private final ImapConnectionManager imapConnectionManager;
    private final OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    private final HandshakeService handshakeService;
    private final StorageProperties storageProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final ApplicationVersion applicationVersion;
    private final ClientBootDiagnosticsService clientBootDiagnosticsService;
    private final StartupTimingService startupTimingService;
    private final TransactionTemplate readOnlyTransaction;

    public DiagnosticDumpService(AccountRepository accountRepository,
            FolderSyncStateRepository folderSyncStateRepository, MessageRepository messageRepository,
            ImapConnectionManager imapConnectionManager, OAuth2TokenServiceRegistry oauth2TokenServiceRegistry,
            HandshakeService handshakeService, StorageProperties storageProperties, Environment environment,
            ObjectMapper objectMapper, ApplicationVersion applicationVersion,
            ClientBootDiagnosticsService clientBootDiagnosticsService, StartupTimingService startupTimingService,
            PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.folderSyncStateRepository = folderSyncStateRepository;
        this.messageRepository = messageRepository;
        this.imapConnectionManager = imapConnectionManager;
        this.oauth2TokenServiceRegistry = oauth2TokenServiceRegistry;
        this.handshakeService = handshakeService;
        this.storageProperties = storageProperties;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.applicationVersion = applicationVersion;
        this.clientBootDiagnosticsService = clientBootDiagnosticsService;
        this.startupTimingService = startupTimingService;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * Builds the support bundle the user sends to the developers: the app's state
     * from the database, the runtime, every thread, and the logs as they are on
     * disk. What the user's own log holds may go to the people the user sends it
     * to, folder names and paths included; what no log may hold, an unmasked
     * address, a token or the session API key, the {@link DiagnosticLogRedactor}
     * takes out of the copy. Passwords, tokens and message content are in no part.
     *
     * <p>
     * Each part is made on its own: the bundle is wanted most when something is
     * stuck, and a database that does not answer must not cost the thread dump that
     * shows why, or the logs that say what led there.
     */
    public byte[] createDump() {
        String generatedAt = now();
        // First, before building the rest adds to what the threads are doing.
        String threads = DiagnosticThreadDump.capture();
        List<SectionDump> sections = new ArrayList<>();
        State state = readState(sections);
        DiagnosticLogRedactor redactor = new DiagnosticLogRedactor(handshakeService.getOrCreateApiKey());

        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            if (state != null) {
                addJson(zip, "summary.json", state.summary());
                addJson(zip, "accounts.json", state.accounts());
                addJson(zip, "folder-sync-states.json", state.folderStates());
                addJson(zip, "message-counts.json", state.messageCounts());
            }
            addJson(zip, "runtime.json", runtime());
            addJson(zip, "client-boot.json", clientBootDiagnosticsService.latest());
            addJson(zip, "startup-timings.json", startupTimings());
            addText(zip, "threads.txt", threads, redactor);
            List<DiagnosticLogExport.LogFileDump> logs = DiagnosticLogExport.write(zip, storageProperties.getLogsPath(),
                    redactor, DiagnosticLogExport.DEFAULT_BUDGET_BYTES);
            addJson(zip, "manifest.json", new ManifestDump(generatedAt, appVersion(), sections,
                    DiagnosticLogExport.DEFAULT_BUDGET_BYTES, logs, redactor.counts()));
            zip.finish();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create diagnostic dump.", e);
        }
    }

    /**
     * What the dump reads from the database, in one read-only transaction, or
     * {@code null} when it could not be read. The failure is logged in full, so the
     * bundle's copy of the log says why, and named in the manifest by its class.
     */
    private @Nullable State readState(List<SectionDump> sections) {
        try {
            State state = readOnlyTransaction.execute(status -> buildState());
            sections.add(new SectionDump("database", "read"));
            return state;
        } catch (RuntimeException e) {
            log.warn("{} The diagnostic dump could not read the database; it goes without accounts and folders.",
                    LogCategory.ERROR, e);
            sections.add(new SectionDump("database", "unreadable: " + e.getClass().getSimpleName()));
            return null;
        }
    }

    private State buildState() {
        List<AccountEntity> accounts = accountRepository.findAllWithDetails();
        List<FolderSyncStateEntity> folderStates = folderSyncStateRepository.findAll();
        return new State(summary(accounts, folderStates), accounts.stream().map(this::toAccountDump).toList(),
                folderStates.stream().map(this::toFolderStateDump).toList(),
                folderStates.stream().map(this::toMessageCountDump).toList());
    }

    private SummaryDump summary(List<AccountEntity> accounts, List<FolderSyncStateEntity> folderStates) {
        long activeAccounts = accounts.stream().filter(AccountEntity::isActive).count();
        long requiresReauthAccounts = accounts.stream().filter(AccountEntity::isRequiresReauth).count();
        long syncableAccounts = accounts.stream().filter(account -> account.isActive() && !account.isRequiresReauth())
                .count();

        return new SummaryDump(now(), MailBackendApplication.APP_NAME, appVersion(), HandshakeService.API_VERSION,
                HandshakeService.MIN_CLIENT_VERSION, handshakeService.getHandshake().dbSchemaVersion(),
                new CountDump(accounts.size(), activeAccounts, requiresReauthAccounts, syncableAccounts,
                        folderStates.size(), messageRepository.count()),
                imapConnectionManager.getPoolStats(), oauth2TokenServiceRegistry.totalCachedTokens());
    }

    private AccountDump toAccountDump(AccountEntity account) {
        // Read the nullable getters into locals: a second call inside the same
        // expression is a fresh return value the static analyzer treats as possibly
        // null even after the first != null check.
        var provider = account.getProvider();
        var credentials = account.getCredentials();
        String lastError = account.getLastError();
        return new AccountDump(account.getId(), LogMasker.maskEmail(account.getEmail()), account.isActive(),
                account.isRequiresReauth(), provider != null ? provider.getName() : "Vlastni",
                credentials != null && credentials.getAuthType() != null ? credentials.getAuthType().name() : null,
                toServerDump(account.getImapConfig()), toServerDump(account.getSmtpConfig()),
                format(account.getLastSyncAt()), lastError != null && !lastError.isBlank(), toLastErrorDump(account));
    }

    /**
     * The account's last error by its code and arguments, which say what failed and
     * why ({@code cause} is a {@code MailFailureCause} name, never an exception's
     * text). An argument the code does not name is left out. The stored text is
     * left out too: it is the exception's, and the log line the failure wrote
     * carries it with its stack.
     */
    private @Nullable LastErrorDump toLastErrorDump(AccountEntity account) {
        Optional<AccountLastErrorCode> code = AccountLastErrorCode.fromCode(account.getLastErrorCode());
        if (code.isEmpty()) {
            return null;
        }
        Map<String, String> stored = AccountLastErrorJson.read(account.getLastErrorArgs());
        Map<String, String> args = new TreeMap<>();
        for (String name : code.get().argNames()) {
            String value = stored.get(name);
            if (value != null) {
                args.put(name, value);
            }
        }
        return new LastErrorDump(code.get().name(), args);
    }

    private FolderStateDump toFolderStateDump(FolderSyncStateEntity state) {
        return new FolderStateDump(accountIdOf(state), state.getFolderName(),
                state.getRole() != null ? state.getRole().name() : null, state.getLastKnownUid(),
                state.getUidValidity(), format(state.getLastSyncAt()));
    }

    private MessageCountDump toMessageCountDump(FolderSyncStateEntity state) {
        Long accountId = accountIdOf(state);
        long count = accountId != null
                ? messageRepository.countByAccountIdAndFolderName(accountId, state.getFolderName())
                : 0L;
        return new MessageCountDump(accountId, state.getFolderName(), count);
    }

    private static @Nullable Long accountIdOf(FolderSyncStateEntity state) {
        AccountEntity account = state.getAccount();
        return account != null ? account.getId() : null;
    }

    /**
     * A path with the user's home directory replaced by {@code ~}. On Windows the
     * home directory is {@code C:\Users\<account name>}, and the data directory
     * lives under it; the comparison ignores case because Windows paths do.
     */
    private static String withoutHome(String path, @Nullable String home) {
        String root = withoutTrailingSeparator(home);
        if (root.isEmpty() || path.length() < root.length() || !path.regionMatches(true, 0, root, 0, root.length())) {
            return path;
        }
        String rest = path.substring(root.length());
        return rest.isEmpty() || rest.charAt(0) == '/' || rest.charAt(0) == '\\' ? "~" + rest : path;
    }

    /**
     * {@code user.home} without the separator it may end with, because the check
     * above requires the next character to be one — that is what keeps
     * {@code C:\Users\Lukas2} from matching the home {@code C:\Users\Lukas}. A home
     * that already ends in a separator ({@code -Duser.home}, or a HOME the
     * environment set that way) therefore left {@code rest} starting with a
     * directory name, which read as "a different directory that starts the same
     * way", and the path came back <em>unredacted</em>: the Windows account name in
     * the exported bundle, with nothing saying the redaction had been skipped.
     * Failing open is the one way this must not fail.
     */
    private static String withoutTrailingSeparator(@Nullable String home) {
        if (home == null || home.isBlank()) {
            return "";
        }
        int end = home.length();
        while (end > 0 && (home.charAt(end - 1) == '/' || home.charAt(end - 1) == '\\')) {
            end--;
        }
        return home.substring(0, end);
    }

    private RuntimeDump runtime() {
        Runtime runtime = Runtime.getRuntime();
        String home = System.getProperty("user.home");
        Path dbFile = storageProperties.getDbPath()
                .resolve(environment.getProperty("spring.application.name", MailBackendApplication.APP_NAME) + ".db");
        return new RuntimeDump(now(), withoutHome(storageProperties.getDataPath().toString(), home),
                withoutHome(storageProperties.getDbPath().toString(), home),
                withoutHome(storageProperties.getLogsPath().toString(), home),
                environment.getProperty("server.address", "127.0.0.1"),
                environment.getProperty("local.server.port", environment.getProperty("server.port", "0")),
                List.of(environment.getActiveProfiles()), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.version"), runtime.availableProcessors(),
                runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory(), sizeOf(dbFile),
                sizeOf(dbFile.resolveSibling(dbFile.getFileName() + "-wal")),
                usableSpace(storageProperties.getDataPath()));
    }

    /** {@code -1} when there is no such file or it cannot be read. */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /** {@code -1} when the volume cannot be asked. */
    private static long usableSpace(Path path) {
        try {
            return Files.getFileStore(path).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    private StartupTimingsDump startupTimings() {
        return new StartupTimingsDump(StartupTimingService.processStartedAt().toString(),
                startupTimingService.snapshots());
    }

    private @Nullable ServerDump toServerDump(@Nullable MailServerConfig config) {
        if (config == null) {
            return null;
        }
        return new ServerDump(config.getHost(), config.getPort(), config.isUseSsl());
    }

    // payload may be null (e.g. client-boot.json before the client reports) —
    // Jackson serializes it as a JSON null literal.
    private void addJson(ZipOutputStream zip, String entryName, @Nullable Object payload) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
        zip.closeEntry();
    }

    private static void addText(ZipOutputStream zip, String entryName, String text, DiagnosticLogRedactor redactor)
            throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        for (String line : text.split("\\R", -1)) {
            zip.write((redactor.redact(line) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();
    }

    private String appVersion() {
        return applicationVersion.value();
    }

    private String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private @Nullable String format(@Nullable LocalDateTime value) {
        return value != null ? value.toString() : null;
    }

    private record State(SummaryDump summary, List<AccountDump> accounts, List<FolderStateDump> folderStates,
            List<MessageCountDump> messageCounts) {
    }

    private record SectionDump(String name, String status) {
    }

    private record ManifestDump(String generatedAt, String appVersion, List<SectionDump> sections, long logBudgetBytes,
            List<DiagnosticLogExport.LogFileDump> logs, DiagnosticLogRedactor.Counts redactions) {
    }

    private record SummaryDump(String generatedAt, String appName, String appVersion, String apiVersion,
            String minClientVersion, String dbSchemaVersion, CountDump counts, ImapConnectionManager.PoolStats imapPool,
            int oauthCachedTokens) {
    }

    private record CountDump(long accounts, long activeAccounts, long requiresReauthAccounts, long syncableAccounts,
            long folderSyncStates, long messages) {
    }

    private record AccountDump(Long id, String maskedEmail, boolean active, boolean requiresReauth, String providerName,
            @Nullable String authType, @Nullable ServerDump imap, @Nullable ServerDump smtp,
            @Nullable String lastSyncAt, boolean lastErrorPresent, @Nullable LastErrorDump lastError) {
    }

    private record LastErrorDump(String code, Map<String, String> args) {
    }

    private record ServerDump(String host, Integer port, boolean useSsl) {
    }

    private record FolderStateDump(@Nullable Long accountId, String folderName, @Nullable String role,
            Long lastKnownUid, @Nullable Long uidValidity, @Nullable String lastSyncAt) {
    }

    private record MessageCountDump(@Nullable Long accountId, String folderName, long messages) {
    }

    private record RuntimeDump(String generatedAt, String dataDir, String dbDir, String logsDir, String serverAddress,
            String serverPort, List<String> activeProfiles, String javaVersion, String osName, String osVersion,
            int availableProcessors, long maxMemoryBytes, long totalMemoryBytes, long freeMemoryBytes, long dbFileBytes,
            long dbWalFileBytes, long usableDiskBytes) {
    }

    private record StartupTimingsDump(String processStartedAt,
            List<StartupTimingService.StartupTimingSnapshot> timings) {
    }
}
