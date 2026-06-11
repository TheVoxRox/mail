package org.voxrox.mailbackend.feature.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Persists newly downloaded message content into the local cache. Extracted
 * from {@link MailContentService} so the {@code REQUIRES_NEW} transactional
 * boundary is created through plain DI (a Spring proxy over this component),
 * not through {@code ObjectProvider<MailContentService>} self-injection.
 * <p>
 * Self-injection is a valid pattern but the CGLib proxy generated at runtime
 * blocks <a href="https://openjdk.org/jeps/483">JEP 483 AOT class cache</a>
 * restore — the proxy class cannot be materialized from the snapshot.
 * Extracting into a separate component eliminates runtime proxy generation and
 * opens the path to the AOT cache.
 */
@Component
public class MessageContentPersister {

    private static final Logger log = LoggerFactory.getLogger(MessageContentPersister.class);

    private final MessageRepository messageRepository;

    public MessageContentPersister(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Persists the fetched content into the database in a separate new transaction.
     * {@code REQUIRES_NEW} guarantees the content save commits even if the outer
     * (sync) operation later fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String updateLocalCache(Long id, String content) {
        return messageRepository.findById(id).map(entity -> {
            entity.setContent(content);
            messageRepository.save(entity);
            log.debug("{} Cache updated for id={}", LogCategory.SYNC, id);
            return content;
        }).orElse(content);
    }
}
