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
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.feature.mail.service.FolderListCache;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;
import tools.jackson.databind.ObjectMapper;

@Service
public class DiagnosticDumpService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticDumpService.class);

    /** RFC 9051 §5.1: the one mailbox name the protocol defines, in any case. */
    private static final String INBOX = "INBOX";

    /** The argument of a last-error code that names a folder. */
    private static final String FOLDER_ARG = "folder";

    private final AccountRepository accountRepository;
    private final FolderSyncStateRepository folderSyncStateRepository;
    private final MessageRepository messageRepository;
    private final ImapConnectionManager imapConnectionManager;
    private final OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    private final FolderListCache folderListCache;
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
            FolderListCache folderListCache, HandshakeService handshakeService, StorageProperties storageProperties,
            Environment environment, ObjectMapper objectMapper, ApplicationVersion applicationVersion,
            ClientBootDiagnosticsService clientBootDiagnosticsService, StartupTimingService startupTimingService,
            PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.folderSyncStateRepository = folderSyncStateRepository;
        this.messageRepository = messageRepository;
        this.imapConnectionManager = imapConnectionManager;
        this.oauth2TokenServiceRegistry = oauth2TokenServiceRegistry;
        this.folderListCache = folderListCache;
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
     * Builds the support bundle. It leaves the user's machine, so it carries only
     * what support needs to tell states apart: an address is masked, every folder
     * but INBOX is a stable pseudonym ({@code folder-3}) next to its role, and a
     * path under the user's home directory starts with {@code ~} instead of the
     * Windows account name.
     *
     * <p>
     * The rule is this bundle's, not the log's. {@code mail.log} writes folder
     * names in full and is meant to: it stays on the machine, and a report about
     * one folder is only readable there under the name the user gave it. The bundle
     * carries a copy of the logs that a {@link DiagnosticLogRedactor} has rewritten
     * by the bundle's rule, with the same pseudonyms, so a line about
     * {@code folder-3} reads against {@code folder-sync-states.json}. Without the
     * folder names from the database that copy cannot be made safely, so a dump
     * that cannot read the database leaves the logs out and says so in
     * {@code manifest.json}.
     *
     * <p>
     * Each part is made on its own: the bundle is wanted most when something is
     * stuck, and a database that does not answer must not cost the thread dump that
     * shows why.
     */
    public byte[] createDump() {
        String generatedAt = now();
        // First, before building the rest adds to what the threads are doing.
        String threads = DiagnosticThreadDump.capture();
        List<SectionDump> sections = new ArrayList<>();
        State state = readState(sections);
        DiagnosticLogRedactor redactor = redactor(state);

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

            List<DiagnosticLogExport.LogFileDump> logs = List.of();
            String logsLeftOut = null;
            if (state != null) {
                logs = DiagnosticLogExport.write(zip, storageProperties.getLogsPath(), redactor,
                        DiagnosticLogExport.DEFAULT_BUDGET_BYTES);
            } else {
                logsLeftOut = "the folder names to replace in them could not be read from the database";
            }
            addJson(zip, "manifest.json", new ManifestDump(generatedAt, appVersion(), sections,
                    DiagnosticLogExport.DEFAULT_BUDGET_BYTES, logs, logsLeftOut, redactor.counts()));
            zip.finish();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create diagnostic dump.", e);
        }
    }

    /**
     * What the dump reads from the database, in one read-only transaction, or
     * {@code null} when it could not be read. The failure is logged in full and
     * named in the manifest by its class only: its message is the database's, and
     * the manifest is not redacted.
     */
    private @Nullable State readState(List<SectionDump> sections) {
        try {
            State state = readOnlyTransaction.execute(status -> buildState());
            sections.add(new SectionDump("database", "read"));
            return state;
        } catch (RuntimeException e) {
            log.warn("{} The diagnostic dump could not read the database; it goes without accounts, folders and logs.",
                    LogCategory.ERROR, e);
            sections.add(new SectionDump("database", "unreadable: " + e.getClass().getSimpleName()));
            return null;
        }
    }

    private State buildState() {
        List<AccountEntity> accounts = accountRepository.findAllWithDetails();
        List<FolderSyncStateEntity> folderStates = folderSyncStateRepository.findAll();
        Map<FolderKey, String> folderLabels = folderLabels(folderStates, folderListCache.seenFolderRefs());
        return new State(summary(accounts, folderStates),
                accounts.stream().map(account -> toAccountDump(account, folderLabels)).toList(),
                folderStates.stream().map(state -> toFolderStateDump(state, folderLabels)).toList(),
                folderStates.stream().map(state -> toMessageCountDump(state, folderLabels)).toList(), accounts,
                folderLabels);
    }

    /**
     * Everything the logs may name that the bundle names otherwise. INBOX is the
     * one folder that keeps its name, as everywhere in the bundle.
     *
     * <p>
     * A folder name is replaced in the log by name alone, without its account, so a
     * name two accounts share ("Sent", "[Gmail]/Trash") becomes every label it has
     * ({@code folder-2|folder-7}): one of them would send a line about the second
     * account's folder to the first account's row of
     * {@code folder-sync-states.json}.
     */
    private DiagnosticLogRedactor redactor(@Nullable State state) {
        DiagnosticLogRedactor.Builder builder = DiagnosticLogRedactor.builder().home(System.getProperty("user.home"))
                .userName(System.getProperty("user.name")).known(handshakeService.getOrCreateApiKey(), "[api-key]");
        if (state == null) {
            return builder.build();
        }
        Map<String, List<String>> labelsByName = new TreeMap<>();
        state.folderLabels().forEach((key, label) -> {
            if (!INBOX.equalsIgnoreCase(key.folderName())) {
                labelsByName.computeIfAbsent(key.folderName(), name -> new ArrayList<>()).add(label);
            }
        });
        labelsByName.forEach((name, labels) -> builder.known(name, String.join("|", labels)));
        for (AccountEntity account : state.entities()) {
            String id = "account-" + account.getId();
            AccountCredentialEntity credentials = account.getCredentials();
            builder.known(account.getEmail(), LogMasker.maskEmail(account.getEmail())).known(account.getDisplayName(),
                    id + "-name");
            if (!isDefaultAccountName(account)) {
                builder.known(account.getAccountName(), id);
            }
            if (credentials != null) {
                builder.known(credentials.getUsername(), LogMasker.maskEmail(credentials.getUsername()));
            }
        }
        return builder.build();
    }

    /**
     * Whether the account's name is the one the app gives it: the label of the
     * address's domain ("Gmail" for {@code @gmail.com}, as
     * {@code ExternalProviderLoginService} and the account wizard name it) or the
     * provider's name. Such a name says nothing the masked address does not, and it
     * is a word the log uses for the provider, which replacing it would turn into
     * {@code account-1} wherever the provider is meant.
     */
    private static boolean isDefaultAccountName(AccountEntity account) {
        String name = account.getAccountName();
        if (name == null || name.isBlank()) {
            return true;
        }
        var provider = account.getProvider();
        if (provider != null && name.strip().equalsIgnoreCase(provider.getName())) {
            return true;
        }
        String email = account.getEmail();
        int at = email != null ? email.indexOf('@') : -1;
        if (at < 0) {
            return false;
        }
        String domain = email.substring(at + 1);
        int dot = domain.indexOf('.');
        String label = dot >= 0 ? domain.substring(0, dot) : domain;
        return name.strip().equalsIgnoreCase(label.strip());
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

    private AccountDump toAccountDump(AccountEntity account, Map<FolderKey, String> folderLabels) {
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
                format(account.getLastSyncAt()), lastError != null && !lastError.isBlank(),
                toLastErrorDump(account, folderLabels));
    }

    /**
     * The account's last error by its code and arguments, which say what failed and
     * why ({@code cause} is a {@code MailFailureCause} name, never an exception's
     * text). A folder argument goes in under the folder's pseudonym; an argument
     * the code does not name is left out, and so is the stored text, which is the
     * exception's and may quote a server.
     */
    private @Nullable LastErrorDump toLastErrorDump(AccountEntity account, Map<FolderKey, String> folderLabels) {
        Optional<AccountLastErrorCode> code = AccountLastErrorCode.fromCode(account.getLastErrorCode());
        if (code.isEmpty()) {
            return null;
        }
        Map<String, String> stored = AccountLastErrorJson.read(account.getLastErrorArgs());
        Map<String, String> args = new TreeMap<>();
        for (String name : code.get().argNames()) {
            String value = stored.get(name);
            if (value == null) {
                continue;
            }
            args.put(name, FOLDER_ARG.equals(name) ? folderLabel(account.getId(), value, folderLabels) : value);
        }
        return new LastErrorDump(code.get().name(), args);
    }

    private static String folderLabel(@Nullable Long accountId, String folderName,
            Map<FolderKey, String> folderLabels) {
        if (INBOX.equalsIgnoreCase(folderName)) {
            return folderName;
        }
        String label = folderLabels.get(new FolderKey(accountId, folderName));
        return label != null ? label : "folder-?";
    }

    private FolderStateDump toFolderStateDump(FolderSyncStateEntity state, Map<FolderKey, String> folderLabels) {
        return new FolderStateDump(accountIdOf(state), labelOf(state, folderLabels),
                state.getRole() != null ? state.getRole().name() : null, state.getLastKnownUid(),
                state.getUidValidity(), format(state.getLastSyncAt()));
    }

    private MessageCountDump toMessageCountDump(FolderSyncStateEntity state, Map<FolderKey, String> folderLabels) {
        Long accountId = accountIdOf(state);
        long count = accountId != null
                ? messageRepository.countByAccountIdAndFolderName(accountId, state.getFolderName())
                : 0L;
        return new MessageCountDump(accountId, labelOf(state, folderLabels), count);
    }

    /**
     * The name each folder goes into the dump under. INBOX keeps its name, which
     * the IMAP protocol fixes; every other folder becomes {@code folder-<n>},
     * numbered in a stable order so the files that list folders agree and two dumps
     * of the same state match. Which system folder a pseudonym stands for is in its
     * {@code role}.
     *
     * <p>
     * Folders with a sync state are numbered first; after them come the folders the
     * app has only listed in this run ({@code seenFolderRefs}), which no file of
     * the dump lists but the logs may name.
     *
     * <p>
     * A role does not prove the provider chose the name. Without SPECIAL-USE, role
     * detection falls back to the name itself
     * ({@link FolderRole#fromNameFallback}), so a folder the user created as
     * "Archive" is stored as {@code ARCHIVE}; and until that fallback matched whole
     * names only, a folder the user called "Newsletter from Dr Novak" was stored as
     * {@code NEWSLETTERS}. Keeping role folders' names put such names in the
     * bundle.
     */
    private static Map<FolderKey, String> folderLabels(List<FolderSyncStateEntity> states,
            Map<Long, Set<String>> seenFolderRefs) {
        Map<FolderKey, String> labels = new LinkedHashMap<>();
        int next = 1;
        List<FolderKey> synced = states.stream().map(FolderKey::of).sorted().toList();
        for (FolderKey key : synced) {
            labels.put(key, INBOX.equalsIgnoreCase(key.folderName()) ? key.folderName() : "folder-" + next++);
        }
        List<FolderKey> listedOnly = seenFolderRefs.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(name -> new FolderKey(entry.getKey(), name)))
                .filter(key -> !labels.containsKey(key)).sorted().toList();
        for (FolderKey key : listedOnly) {
            labels.put(key, INBOX.equalsIgnoreCase(key.folderName()) ? key.folderName() : "folder-" + next++);
        }
        return labels;
    }

    private static String labelOf(FolderSyncStateEntity state, Map<FolderKey, String> folderLabels) {
        String label = folderLabels.get(FolderKey.of(state));
        return label != null ? label : "folder-?";
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

    private record FolderKey(@Nullable Long accountId, String folderName) implements Comparable<FolderKey> {
        static FolderKey of(FolderSyncStateEntity state) {
            return new FolderKey(accountIdOf(state), state.getFolderName());
        }

        @Override
        public int compareTo(FolderKey other) {
            return Comparator.comparing(FolderKey::accountId, Comparator.nullsFirst(Comparator.<Long>naturalOrder()))
                    .thenComparing(FolderKey::folderName).compare(this, other);
        }
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
            List<MessageCountDump> messageCounts, List<AccountEntity> entities, Map<FolderKey, String> folderLabels) {
    }

    private record SectionDump(String name, String status) {
    }

    private record ManifestDump(String generatedAt, String appVersion, List<SectionDump> sections, long logBudgetBytes,
            List<DiagnosticLogExport.LogFileDump> logs, @Nullable String logsLeftOutBecause,
            DiagnosticLogRedactor.Counts redactions) {
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
