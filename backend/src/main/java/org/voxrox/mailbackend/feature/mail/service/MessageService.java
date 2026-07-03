package org.voxrox.mailbackend.feature.mail.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

import module java.base;

@Service
public class MessageService {

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * FTS5 full-text search returning summary DTOs. Two-step on purpose: the FTS
     * MATCH is native SQL and returns just the page of matching ids; the display
     * columns are then loaded via a JPQL constructor projection. Loading entities
     * directly would hydrate every {@code @Lob} body of the page — with
     * {@code api-max-page-size=200} and large HTML bodies that does not fit the
     * production 384m heap.
     */
    @Transactional(readOnly = true)
    public Page<MailSummaryResponse> search(Long accountId, String query, int page, int size) {
        if (query == null || query.isBlank())
            return Page.empty();

        String sanitized = query.replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim();
        if (sanitized.isEmpty())
            return Page.empty();

        String ftsQuery = sanitized.replaceAll("\\s+", "* ") + "*";
        PageRequest pageable = PageRequest.of(page, size);
        Page<Number> ids = messageRepository.fullTextSearchIds(ftsQuery, accountId, pageable);
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, ids.getTotalElements());
        }

        // Normalize the driver's value-sized boxes (Integer/Long) to Long — see
        // the fullTextSearchIds javadoc — then restore the FTS page order, which
        // the unordered IN load does not preserve.
        List<Long> idList = ids.getContent().stream().map(Number::longValue).toList();
        Map<Long, MailSummaryResponse> byId = messageRepository.findSummariesByIds(idList).stream()
                .collect(Collectors.toMap(MailSummaryResponse::id, Function.identity()));
        List<MailSummaryResponse> ordered = idList.stream().map(byId::get).filter(Objects::nonNull).toList();
        return new PageImpl<>(ordered, pageable, ids.getTotalElements());
    }

    /**
     * Loads a message with the owning account fetch-joined. Callers (MailFacade,
     * SmtpMessageService) use the entity after this transaction has closed — IMAP
     * round-trips deliberately run with no transaction open — so the lazy account
     * association must already be populated here.
     */
    @Transactional(readOnly = true)
    public Optional<MessageEntity> getByStableId(String stableId) {
        return messageRepository.findByStableIdWithAccount(stableId);
    }

    @Transactional
    public void deleteByStableId(String stableId) {
        messageRepository.deleteByStableId(stableId);
    }
}
