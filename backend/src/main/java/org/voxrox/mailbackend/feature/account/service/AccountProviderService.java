package org.voxrox.mailbackend.feature.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.exception.ProviderNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.dto.MailProviderResponse;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.MailProviderRepository;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

@Service
public class AccountProviderService {

    private static final Logger log = LoggerFactory.getLogger(AccountProviderService.class);
    private final MailProviderRepository providerRepository;

    public AccountProviderService(MailProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    /**
     * Resolves a provider either by explicit ID or by auto-detecting it from the
     * e-mail domain. Domain match uses comma-anchored LIKE: the stored
     * {@code domains} column is a comma-separated list and we wrap the lookup
     * domain in commas to avoid substring false positives (e.g. {@code seznam.cz}
     * matching {@code post.seznam.cz}).
     * <p>
     * <b>Use:</b> OAuth callback (Google login) — {@code explicitProviderId} may be
     * null here and domain auto-detection is the expected behavior.
     * <p>
     * For CRUD input use {@link #loadProviderById(Long)} — it deliberately has no
     * domain fallback, because a custom flow (provider_id == null) is valid and
     * must not fall through to auto-detection.
     */
    @Transactional(readOnly = true)
    public MailProviderEntity resolveProvider(String email, Long explicitProviderId) {
        if (explicitProviderId != null) {
            return providerRepository.findById(explicitProviderId)
                    .orElseThrow(() -> ProviderNotFoundException.byId(explicitProviderId));
        }

        return findProviderEntityByEmail(email)
                .orElseThrow(() -> ProviderNotFoundException.byDomain(extractDomain(email)));
    }

    /**
     * Loads a provider by ID without any auto-detection.
     * <p>
     * Use: CRUD input ({@code POST/PUT/PATCH /accounts}) — when the client sends
     * {@code providerId == null}, that signals "custom provider" intent, and the
     * service must take the custom branch instead of falling into domain
     * auto-detection.
     */
    @Transactional(readOnly = true)
    public MailProviderEntity loadProviderById(Long id) {
        return providerRepository.findById(id).orElseThrow(() -> ProviderNotFoundException.byId(id));
    }

    @Transactional(readOnly = true)
    public List<MailProviderResponse> getAllProviders() {
        return providerRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Optional<MailProviderResponse> findProviderByEmail(String email) {
        return findProviderEntityByEmail(email).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<MailProviderResponse> getProviderById(Long id) {
        return providerRepository.findById(id).map(this::toDto);
    }

    private Optional<MailProviderEntity> findProviderEntityByEmail(String email) {
        if (email == null || !email.contains("@")) {
            log.warn("{} Invalid e-mail for provider detection: {}", LogCategory.ACCOUNT, LogMasker.maskEmail(email));
            return Optional.empty();
        }

        String domain = extractDomain(email);
        String domainKey = "," + domain + ",";

        log.debug("{} Looking up provider for domain via key: {}", LogCategory.ACCOUNT, domainKey);

        return providerRepository.findByDomainKey(domainKey).stream().findFirst();
    }

    private MailProviderResponse toDto(MailProviderEntity entity) {
        MailServerConfig imap = entity.getImapConfig();
        MailServerConfig smtp = entity.getSmtpConfig();
        return new MailProviderResponse(entity.getId(), entity.getName(), imap != null ? imap.getHost() : null,
                imap != null ? imap.getPort() : null, imap != null && imap.isUseSsl(),
                smtp != null ? smtp.getHost() : null, smtp != null ? smtp.getPort() : null,
                smtp != null && smtp.isUseSsl(), entity.getDomains(), entity.isSupportsOauth2(),
                entity.getOauth2RegistrationId());
    }

    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) {
            throw new ValidationException("Could not extract a domain from the invalid e-mail address.",
                    "validation.email.domainInvalid");
        }
        return email.substring(email.lastIndexOf("@") + 1).toLowerCase(Locale.ROOT).trim();
    }
}
