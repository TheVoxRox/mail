package org.voxrox.mailbackend.feature.mail.service;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.entity.RemoteImageSenderEntity;
import org.voxrox.mailbackend.feature.mail.repository.RemoteImageSenderRepository;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Manages the per-sender remote-image allow-list (content-rendering audit
 * finding F2). Remote images are blocked by default as a tracking-pixel
 * defense; a sender listed here has its messages' remote https images
 * auto-loaded on open.
 *
 * <p>
 * Sender emails are normalized (trimmed + lowercased) on every write and read
 * so a lookup matches regardless of the casing an IMAP server reports in the
 * From header. The allow decision affects <em>image loading only</em> — never
 * trust or code execution — and is keyed on the (spoofable) From address by
 * design, the same convenience trade-off mature clients make.
 */
@Service
public class RemoteImageAllowlistService {

    private static final Logger log = LoggerFactory.getLogger(RemoteImageAllowlistService.class);

    private final RemoteImageSenderRepository repository;
    private final AccountService accountService;

    public RemoteImageAllowlistService(RemoteImageSenderRepository repository, AccountService accountService) {
        this.repository = repository;
        this.accountService = accountService;
    }

    /**
     * Idempotently allows remote images from {@code senderEmail} for the account.
     */
    @Transactional
    public void allow(Long accountId, String senderEmail) {
        String normalized = normalize(senderEmail);
        if (normalized.isEmpty()) {
            return;
        }
        // Validates the account exists (404 otherwise) and yields the managed entity.
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        if (repository.existsByAccountIdAndSenderEmail(accountId, normalized)) {
            return;
        }
        repository.save(new RemoteImageSenderEntity(account, normalized));
        log.info("{} Remote images allowed for a sender on account {}.", LogCategory.API, accountId);
    }

    @Transactional
    public void disallow(Long accountId, String senderEmail) {
        repository.deleteByAccountIdAndSenderEmail(accountId, normalize(senderEmail));
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(Long accountId, String senderEmail) {
        String normalized = normalize(senderEmail);
        return !normalized.isEmpty() && repository.existsByAccountIdAndSenderEmail(accountId, normalized);
    }

    @Transactional(readOnly = true)
    public List<String> listAllowedSenders(Long accountId) {
        return repository.findSenderEmailsByAccountId(accountId);
    }

    private static String normalize(String senderEmail) {
        return senderEmail == null ? "" : senderEmail.trim().toLowerCase(Locale.ROOT);
    }
}
