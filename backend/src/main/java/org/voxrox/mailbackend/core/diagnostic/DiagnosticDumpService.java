package org.voxrox.mailbackend.core.diagnostic;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.MailBackendApplication;
import org.voxrox.mailbackend.core.config.ApplicationVersion;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.core.init.HandshakeService;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;
import tools.jackson.databind.ObjectMapper;

@Service
public class DiagnosticDumpService {

    /** RFC 9051 §5.1: the one mailbox name the protocol defines, in any case. */
    private static final String INBOX = "INBOX";

    private final AccountRepository accountRepository;
    private final FolderSyncStateRepository folderSyncStateRepository;
    private final MessageRepository messageRepository;
    private final ImapConnectionManager imapConnectionManager;
    private final OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    private final StorageProperties storageProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final ApplicationVersion applicationVersion;
    private final ClientBootDiagnosticsService clientBootDiagnosticsService;
    private final StartupTimingService startupTimingService;

    public DiagnosticDumpService(AccountRepository accountRepository,
            FolderSyncStateRepository folderSyncStateRepository, MessageRepository messageRepository,
            ImapConnectionManager imapConnectionManager, OAuth2TokenServiceRegistry oauth2TokenServiceRegistry,
            StorageProperties storageProperties, Environment environment, ObjectMapper objectMapper,
            ApplicationVersion applicationVersion, ClientBootDiagnosticsService clientBootDiagnosticsService,
            StartupTimingService startupTimingService) {
        this.accountRepository = accountRepository;
        this.folderSyncStateRepository = folderSyncStateRepository;
        this.messageRepository = messageRepository;
        this.imapConnectionManager = imapConnectionManager;
        this.oauth2TokenServiceRegistry = oauth2TokenServiceRegistry;
        this.storageProperties = storageProperties;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.applicationVersion = applicationVersion;
        this.clientBootDiagnosticsService = clientBootDiagnosticsService;
        this.startupTimingService = startupTimingService;
    }

    /**
     * Builds the support bundle. It leaves the user's machine, so it carries only
     * what support needs to tell states apart: an address is masked, a folder the
     * user named is a stable pseudonym ({@code folder-3}) while a folder with a
     * provider role keeps its name, and a path under the user's home directory
     * starts with {@code ~} instead of the Windows account name.
     *
     * <p>
     * The rule is this bundle's, not the log's. {@code mail.log} writes folder
     * names in full and is meant to: it stays on the machine, this bundle records
     * the log directory's path but carries no log file, and a report about one
     * folder is only readable under the name the user gave it. Leaving is what
     * earns the pseudonym, so a change that ever sends a log somewhere has to
     * revisit it rather than copy this rule over.
     */
    @Transactional(readOnly = true)
    public byte[] createDump() {
        List<AccountEntity> accounts = accountRepository.findAllWithDetails();
        List<FolderSyncStateEntity> folderStates = folderSyncStateRepository.findAll();
        Map<FolderKey, String> folderLabels = folderLabels(folderStates);

        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            addJson(zip, "summary.json", summary(accounts, folderStates));
            addJson(zip, "accounts.json", accounts.stream().map(this::toAccountDump).toList());
            addJson(zip, "folder-sync-states.json",
                    folderStates.stream().map(state -> toFolderStateDump(state, folderLabels)).toList());
            addJson(zip, "message-counts.json",
                    folderStates.stream().map(state -> toMessageCountDump(state, folderLabels)).toList());
            addJson(zip, "runtime.json", runtime());
            addJson(zip, "client-boot.json", clientBootDiagnosticsService.latest());
            addJson(zip, "startup-timings.json", startupTimings());
            zip.finish();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create diagnostic dump.", e);
        }
    }

    private SummaryDump summary(List<AccountEntity> accounts, List<FolderSyncStateEntity> folderStates) {
        long activeAccounts = accounts.stream().filter(AccountEntity::isActive).count();
        long requiresReauthAccounts = accounts.stream().filter(AccountEntity::isRequiresReauth).count();
        long syncableAccounts = accounts.stream().filter(account -> account.isActive() && !account.isRequiresReauth())
                .count();

        return new SummaryDump(now(), MailBackendApplication.APP_NAME, appVersion(), HandshakeService.API_VERSION,
                HandshakeService.MIN_CLIENT_VERSION,
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
                format(account.getLastSyncAt()), lastError != null && !lastError.isBlank());
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
     * The name each folder goes into the dump under. A folder the provider marks
     * with a role keeps its name, which the provider chose, and so does INBOX,
     * whose name the IMAP protocol fixes whatever role was stored for it; a folder
     * the user created and named becomes {@code folder-<n>}, numbered in a stable
     * order so the two files that list folders agree and two dumps of the same
     * state match.
     */
    private static Map<FolderKey, String> folderLabels(List<FolderSyncStateEntity> states) {
        Map<FolderKey, String> labels = new HashMap<>();
        int next = 1;
        List<FolderSyncStateEntity> ordered = states.stream()
                .sorted(Comparator
                        .comparing((FolderSyncStateEntity state) -> accountIdOf(state),
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(FolderSyncStateEntity::getFolderName))
                .toList();
        for (FolderSyncStateEntity state : ordered) {
            FolderRole role = state.getRole();
            boolean named = (role == null || role == FolderRole.USER) && !INBOX.equalsIgnoreCase(state.getFolderName());
            labels.put(FolderKey.of(state), named ? "folder-" + next++ : state.getFolderName());
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
        if (home == null || home.isBlank() || path.length() < home.length()
                || !path.regionMatches(true, 0, home, 0, home.length())) {
            return path;
        }
        String rest = path.substring(home.length());
        return rest.isEmpty() || rest.charAt(0) == '/' || rest.charAt(0) == '\\' ? "~" + rest : path;
    }

    private record FolderKey(@Nullable Long accountId, String folderName) {
        static FolderKey of(FolderSyncStateEntity state) {
            return new FolderKey(accountIdOf(state), state.getFolderName());
        }
    }

    private RuntimeDump runtime() {
        Runtime runtime = Runtime.getRuntime();
        String home = System.getProperty("user.home");
        return new RuntimeDump(now(), withoutHome(storageProperties.getDataPath().toString(), home),
                withoutHome(storageProperties.getDbPath().toString(), home),
                withoutHome(storageProperties.getLogsPath().toString(), home),
                environment.getProperty("server.address", "127.0.0.1"),
                environment.getProperty("local.server.port", environment.getProperty("server.port", "0")),
                List.of(environment.getActiveProfiles()), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.version"), runtime.availableProcessors(),
                runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory());
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

    private String appVersion() {
        return applicationVersion.value();
    }

    private String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private @Nullable String format(@Nullable LocalDateTime value) {
        return value != null ? value.toString() : null;
    }

    private record SummaryDump(String generatedAt, String appName, String appVersion, String apiVersion,
            String minClientVersion, CountDump counts, ImapConnectionManager.PoolStats imapPool,
            int oauthCachedTokens) {
    }

    private record CountDump(long accounts, long activeAccounts, long requiresReauthAccounts, long syncableAccounts,
            long folderSyncStates, long messages) {
    }

    private record AccountDump(Long id, String maskedEmail, boolean active, boolean requiresReauth, String providerName,
            @Nullable String authType, @Nullable ServerDump imap, @Nullable ServerDump smtp,
            @Nullable String lastSyncAt, boolean lastErrorPresent) {
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
            int availableProcessors, long maxMemoryBytes, long totalMemoryBytes, long freeMemoryBytes) {
    }

    private record StartupTimingsDump(String processStartedAt,
            List<StartupTimingService.StartupTimingSnapshot> timings) {
    }
}
