package org.voxrox.mailbackend.feature.contact.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.exception.AppException;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateResponse;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateResponse.BulkContactCreateResult;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteRequest;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteResponse.BulkContactDeleteResult;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * Best-effort bulk operations over contacts. Iterates the request and delegates
 * single-item operations to {@link ContactService}. Each delegated call must
 * run in its own transaction so a single item failure (duplicate, validation)
 * does not roll back the whole batch.
 * <p>
 * <strong>Do not merge this class back into {@link ContactService}.</strong>
 * The natural inline approach uses {@code ObjectProvider<ContactService>}
 * self-injection so each single-item call routes through a Spring proxy and
 * gets its own transaction. That breaks the
 * <a href="https://openjdk.org/jeps/483">JEP 483 AOT class cache</a> because
 * CGLib proxies generated at runtime cannot be materialized from the snapshot.
 * Keeping bulk logic in a separate component eliminates self-injection: Spring
 * proxies {@link ContactService} normally, this class receives the proxy
 * through standard DI, and transactions work per call without breaking AOT.
 */
@Service
public class ContactBulkService {

    private static final Logger log = LoggerFactory.getLogger(ContactBulkService.class);

    private final AccountService accountService;
    private final ContactService contactService;

    public ContactBulkService(AccountService accountService, ContactService contactService) {
        this.accountService = accountService;
        this.contactService = contactService;
    }

    /**
     * Best-effort bulk create. Each item runs in its own transaction — a failure of
     * one (duplicate, validation) does not affect the others. Account existence is
     * verified once up front (fail-fast) so the whole request does not waste effort
     * on per-item 404s.
     */
    public BulkContactCreateResponse bulkCreate(Long accountId, BulkContactCreateRequest request) {
        accountService.getAccountOrThrow(accountId);

        List<ContactCreateRequest> items = request.contacts();
        List<BulkContactCreateResult> results = new ArrayList<>(items.size());
        int created = 0;
        int failed = 0;

        for (int i = 0; i < items.size(); i++) {
            try {
                ContactResponse contact = contactService.createContact(accountId, items.get(i));
                results.add(BulkContactCreateResult.success(i, contact));
                created++;
            } catch (AppException e) {
                results.add(BulkContactCreateResult.failure(i, e.getCode().name(),
                        e.getMessage() != null ? e.getMessage() : e.getCode().name()));
                failed++;
            }
        }

        log.info("{} Bulk contact create account={}: total={} created={} failed={}", LogCategory.ACCOUNT, accountId,
                items.size(), created, failed);
        return new BulkContactCreateResponse(items.size(), created, failed, results);
    }

    /**
     * Best-effort bulk delete. Duplicate IDs in the request show up as NOT_FOUND on
     * the second attempt (meaningful — the client learns the ID has already been
     * deleted).
     */
    public BulkContactDeleteResponse bulkDelete(Long accountId, BulkContactDeleteRequest request) {
        accountService.getAccountOrThrow(accountId);

        List<Long> ids = request.ids();
        List<BulkContactDeleteResult> results = new ArrayList<>(ids.size());
        int deleted = 0;
        int failed = 0;

        for (Long id : ids) {
            try {
                contactService.deleteContact(accountId, id);
                results.add(BulkContactDeleteResult.success(id));
                deleted++;
            } catch (AppException e) {
                results.add(BulkContactDeleteResult.failure(id, e.getCode().name(),
                        e.getMessage() != null ? e.getMessage() : e.getCode().name()));
                failed++;
            }
        }

        log.info("{} Bulk contact delete account={}: total={} deleted={} failed={}", LogCategory.ACCOUNT, accountId,
                ids.size(), deleted, failed);
        return new BulkContactDeleteResponse(ids.size(), deleted, failed, results);
    }
}
