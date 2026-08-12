package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.MailProviderRepository;
import org.voxrox.mailbackend.feature.account.service.MailProviderCatalog.SystemProviderTemplate;

/**
 * Covers the reconciler that replaced the {@code INSERT} seed in
 * {@code V1__init.sql}, against a real SQLite built by the real migration.
 *
 * <p>
 * The interesting cases are not "does it insert" but the two that decide
 * whether the catalog can stay in code: that a repeated boot writes nothing,
 * and that a drifted row is repaired field by field — the latter is what makes
 * "follow a provider that moved its SMTP port" a code change rather than a
 * Flyway migration.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
class MailProviderCatalogReconcilerIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "MailProviderCatalogReconcilerIT", UUID.randomUUID().toString()).toAbsolutePath()
            .normalize();

    @DynamicPropertySource
    static void configureSqliteDatasource(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(DB_DIR);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create directory for SQLite test DB: " + DB_DIR, e);
        }
        Path dbFile = DB_DIR.resolve("test.db");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dbFile.toAbsolutePath() + "?foreign_keys=ON&busy_timeout=5000");
    }

    @Autowired
    private MailProviderRepository providerRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    private MailProviderCatalogReconciler reconciler() {
        return new MailProviderCatalogReconciler(providerRepository, transactionTemplate, new StartupTimingService());
    }

    /** Re-reads the row from the DB rather than the first-level cache. */
    private MailProviderEntity reload(String name) {
        entityManager.flush();
        entityManager.clear();
        return providerRepository.findByName(name).orElseThrow();
    }

    @Test
    @DisplayName("V1 no longer seeds providers — the table starts empty")
    void migrationDoesNotSeedProviders() {
        assertThat(providerRepository.count())
                .as("V1__init.sql must not INSERT providers; the catalog is the single source of truth").isZero();
    }

    @Test
    @DisplayName("first pass inserts the whole catalog, in declaration order")
    void firstPassInsertsCatalog() {
        int changed = reconciler().reconcile();

        assertThat(changed).isEqualTo(MailProviderCatalog.SYSTEM_TEMPLATES.size());
        assertThat(providerRepository.findAll()).extracting(MailProviderEntity::getName).containsExactlyElementsOf(
                MailProviderCatalog.SYSTEM_TEMPLATES.stream().map(SystemProviderTemplate::name).toList());
    }

    @Test
    @DisplayName("a second pass writes nothing")
    void secondPassIsANoOp() {
        reconciler().reconcile();

        assertThat(reconciler().reconcile())
                .as("a boot on an up-to-date catalog must not touch the single-writer SQLite").isZero();
    }

    @Nested
    @DisplayName("drift repair")
    class DriftRepair {

        @Test
        @DisplayName("every catalog-managed field is restored after being scrambled")
        void repairsEveryManagedField() {
            reconciler().reconcile();

            SystemProviderTemplate template = MailProviderCatalog.SYSTEM_TEMPLATES.getFirst();
            MailProviderEntity drifted = providerRepository.findByName(template.name()).orElseThrow();
            drifted.setDomains(",wrong.example,");
            drifted.setImapConfig(new MailServerConfig("wrong.imap.example", 1143, !template.imapSsl()));
            drifted.setSmtpConfig(new MailServerConfig("wrong.smtp.example", 1587, !template.smtpSsl()));
            drifted.setSystemTemplate(false);
            drifted.setSupportsOauth2(!template.supportsOauth2());
            drifted.setOauth2RegistrationId("wrong-registration");
            providerRepository.save(drifted);
            entityManager.flush();

            assertThat(reconciler().reconcile()).isEqualTo(1);

            MailProviderEntity repaired = reload(template.name());
            assertThat(repaired.getDomains()).isEqualTo(template.domains());
            assertThat(repaired.getImapConfig().getHost()).isEqualTo(template.imapHost());
            assertThat(repaired.getImapConfig().getPort()).isEqualTo(template.imapPort());
            assertThat(repaired.getImapConfig().isUseSsl()).isEqualTo(template.imapSsl());
            assertThat(repaired.getSmtpConfig().getHost()).isEqualTo(template.smtpHost());
            assertThat(repaired.getSmtpConfig().getPort()).isEqualTo(template.smtpPort());
            assertThat(repaired.getSmtpConfig().isUseSsl()).isEqualTo(template.smtpSsl());
            assertThat(repaired.isSystemTemplate()).isTrue();
            assertThat(repaired.isSupportsOauth2()).isEqualTo(template.supportsOauth2());
            assertThat(repaired.getOauth2RegistrationId()).isEqualTo(template.oauth2RegistrationId());
        }

        @Test
        @DisplayName("a row outside the catalog is left alone, never deleted")
        void leavesUnknownProvidersAlone() {
            MailProviderEntity retired = new MailProviderEntity();
            retired.setName("Retired provider");
            retired.setDomains(",retired.example,");
            retired.setImapConfig(new MailServerConfig("imap.retired.example", 993, true));
            retired.setSmtpConfig(new MailServerConfig("smtp.retired.example", 465, true));
            providerRepository.save(retired);
            entityManager.flush();

            reconciler().reconcile();

            assertThat(providerRepository.findByName("Retired provider"))
                    .as("accounts.provider_id may still reference it — dropping the row would unlabel the account")
                    .isPresent();
        }
    }

    @Nested
    @DisplayName("catalog invariants")
    class CatalogInvariants {

        @Test
        @DisplayName("OAuth support and the registration id are declared together")
        void oauthFieldsAgree() {
            for (SystemProviderTemplate template : MailProviderCatalog.SYSTEM_TEMPLATES) {
                assertThat(template.supportsOauth2())
                        .as("%s: supportsOauth2 must match having a registration id", template.name())
                        .isEqualTo(template.oauth2RegistrationId() != null);
            }
        }

        @Test
        @DisplayName("domains are comma-anchored so LIKE matching cannot hit a substring")
        void domainsAreCommaAnchored() {
            for (SystemProviderTemplate template : MailProviderCatalog.SYSTEM_TEMPLATES) {
                assertThat(template.domains()).as("%s domains", template.name()).startsWith(",").endsWith(",");
            }
        }
    }
}
