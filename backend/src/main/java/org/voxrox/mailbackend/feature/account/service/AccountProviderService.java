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
     * The template an OAuth sign-in creates its account from, found by the
     * registration the sign-in went through rather than by the address it returned.
     * A Microsoft 365 work or school account, or a Google Workspace one, signs in
     * under the organization's own domain, which no template lists — the domain
     * lookup this replaced refused every such sign-in. The registration already
     * says whose servers hold the mailbox, which is also right for an identity
     * whose sign-in name is another provider's address (a Microsoft account named
     * by a Gmail address).
     */
    @Transactional(readOnly = true)
    public MailProviderEntity resolveOAuthProvider(String registrationId) {
        return providerRepository.findByOauth2RegistrationId(registrationId)
                .orElseThrow(() -> ProviderNotFoundException.byOAuth2Registration(registrationId));
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
