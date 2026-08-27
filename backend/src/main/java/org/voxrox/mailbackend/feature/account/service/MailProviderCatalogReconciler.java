package org.voxrox.mailbackend.feature.account.service;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.MailProviderRepository;
import org.voxrox.mailbackend.feature.account.service.MailProviderCatalog.SystemProviderTemplate;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * Projects {@link MailProviderCatalog} into the {@code mail_providers} table at
 * startup, so that changing the built-in provider catalog never requires a
 * Flyway migration against an installed user's database.
 *
 * <p>
 * <b>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}:</b> Boot
 * starts the web server at the end of context refresh, before runners and
 * {@code ApplicationReadyEvent} listeners fire — an early
 * {@code GET /providers} from the frontend bootstrap could then observe a
 * half-populated catalog. Bean initialisation is ordered strictly before that.
 * Flyway is already guaranteed to have run: this bean depends on a repository,
 * hence on the {@code EntityManagerFactory}, which Boot makes depend on the
 * Flyway initializer.
 *
 * <p>
 * The pass is <b>idempotent and write-avoiding</b>: a row is only written when
 * a catalog-managed field actually differs, so the usual boot touches the
 * single-writer SQLite not at all.
 *
 * <p>
 * <b>It never deletes.</b> A row that is no longer in the catalog stays: it may
 * still be referenced by {@code accounts.provider_id}, and dropping it would
 * silently strip the provider label off a working account (the FK is
 * {@code ON DELETE SET NULL}). Retiring a provider is therefore a deliberate
 * migration, not a side effect of editing the catalog.
 */
@Component
public class MailProviderCatalogReconciler {

    private static final Logger log = LoggerFactory.getLogger(MailProviderCatalogReconciler.class);

    private final MailProviderRepository providerRepository;
    private final TransactionTemplate transactionTemplate;
    private final StartupTimingService startupTimingService;

    public MailProviderCatalogReconciler(MailProviderRepository providerRepository,
            TransactionTemplate transactionTemplate, StartupTimingService startupTimingService) {
        this.providerRepository = providerRepository;
        this.transactionTemplate = transactionTemplate;
        this.startupTimingService = startupTimingService;
    }

    /**
     * {@code @Transactional} is deliberately not used here — Spring's transaction
     * proxy is not in play for a bean's own lifecycle callback, so the annotation
     * would silently do nothing. The explicit {@link TransactionTemplate} keeps the
     * whole catalog pass in one transaction.
     */
    @PostConstruct
    void reconcileOnStartup() {
        long started = startupTimingService.start();
        int changed = Objects.requireNonNullElse(transactionTemplate.execute(status -> reconcile()), 0);
        startupTimingService.record("providers.catalog-reconcile", started);

        if (changed > 0) {
            log.info("{} Provider catalog reconciled: {} of {} templates written.", LogCategory.ACCOUNT, changed,
                    MailProviderCatalog.SYSTEM_TEMPLATES.size());
        } else {
            log.debug("{} Provider catalog already matches the {} built-in templates.", LogCategory.ACCOUNT,
                    MailProviderCatalog.SYSTEM_TEMPLATES.size());
        }
    }

    /**
     * Upserts the built-in provider templates into the catalog table.
     *
     * @return how many rows were inserted or updated
     */
    int reconcile() {
        int changed = 0;
        for (SystemProviderTemplate template : MailProviderCatalog.SYSTEM_TEMPLATES) {
            Optional<MailProviderEntity> existing = providerRepository.findByName(template.name());
            boolean isNew = existing.isEmpty();
            MailProviderEntity entity = existing.orElseGet(MailProviderEntity::new);
            if (isNew) {
                entity.setName(template.name());
            }

            if (applyTemplate(template, entity)) {
                providerRepository.save(entity);
                changed++;
                log.info("{} Provider template {}: {}.", LogCategory.ACCOUNT, template.name(),
                        isNew ? "inserted" : "updated from the catalog");
            }
        }
        return changed;
    }

    /**
     * Copies every catalog-managed field onto the entity.
     *
     * @return {@code true} when at least one field differed, i.e. the row needs to
     *         be written
     */
    private boolean applyTemplate(SystemProviderTemplate template, MailProviderEntity entity) {
        boolean changed = !template.domains().equals(entity.getDomains());
        entity.setDomains(template.domains());

        changed |= applyServerConfig(entity.getImapConfig(), template.imapHost(), template.imapPort(),
                template.imapSsl());
        changed |= applyServerConfig(entity.getSmtpConfig(), template.smtpHost(), template.smtpPort(),
                template.smtpSsl());

        changed |= !entity.isSystemTemplate();
        entity.setSystemTemplate(true);

        changed |= entity.isSupportsOauth2() != template.supportsOauth2();
        entity.setSupportsOauth2(template.supportsOauth2());

        changed |= !Objects.equals(entity.getOauth2RegistrationId(), template.oauth2RegistrationId());
        entity.setOauth2RegistrationId(template.oauth2RegistrationId());

        return changed;
    }

    private boolean applyServerConfig(MailServerConfig config, String host, int port, boolean useSsl) {
        boolean changed = !host.equals(config.getHost()) || !Integer.valueOf(port).equals(config.getPort())
                || config.isUseSsl() != useSsl;
        config.setHost(host);
        config.setPort(port);
        config.setUseSsl(useSsl);
        return changed;
    }
}
