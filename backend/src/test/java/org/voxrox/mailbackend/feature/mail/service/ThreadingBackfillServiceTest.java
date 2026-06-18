package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link ThreadingBackfillService}.
 *
 * <p>
 * Mocks: {@link AccountRepository}, {@link MessageRepository},
 * {@link ThreadingService}; the {@link TransactionTemplate} is an inline
 * subclass that runs the callback synchronously (mirrors
 * {@code FlagSyncServiceTest}).
 *
 * <p>
 * Covers the batch cursor contract — the loop is driven purely by batch size,
 * not the initial count: a short batch terminates it, an exact multiple of
 * {@code BATCH_SIZE} costs one extra empty query (the {@code thread_id IS NULL}
 * WHERE clause is the only termination guard) — plus the no-op fast path, the
 * startup account walk, and per-account error isolation.
 */
@ExtendWith(MockitoExtension.class)
class ThreadingBackfillServiceTest {

    /** Mirror of the private {@code ThreadingBackfillService.BATCH_SIZE}. */
    private static final int BATCH_SIZE = 200;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ThreadingService threadingService;

    private ThreadingBackfillService service;

    @BeforeEach
    void setUp() {
        service = new ThreadingBackfillService(accountRepository, messageRepository, threadingService,
                inlineTransactionTemplate());
    }

    /** Inline TransactionTemplate — the callback runs synchronously, no real tx. */
    private static TransactionTemplate inlineTransactionTemplate() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private static AccountEntity account(Long id, String email) {
        AccountEntity account = new AccountEntity();
        account.setId(id);
        account.setEmail(email);
        return account;
    }

    private static List<MessageEntity> messages(int n) {
        List<MessageEntity> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new MessageEntity());
        }
        return list;
    }

    @Nested
    @DisplayName("backfillAccount — per-account batch loop")
    class BackfillAccount {

        @Test
        @DisplayName("No unthreaded rows is a fast no-op — never queries a batch or touches ThreadingService")
        void nothingToDoWhenNoUnthreadedMessages() {
            AccountEntity account = account(1L, "a@example.com");
            when(messageRepository.countUnthreadedByAccount(1L)).thenReturn(0L);

            int assigned = service.backfillAccount(account);

            assertThat(assigned).isZero();
            verify(messageRepository, never()).findUnthreadedByAccountOrderByReceivedAt(anyLong(), any());
            verifyNoInteractions(threadingService);
        }

        @Test
        @DisplayName("A single short batch assigns every row and stops after one query")
        void singleBatchAssignsAllAndStops() {
            AccountEntity account = account(1L, "a@example.com");
            when(messageRepository.countUnthreadedByAccount(1L)).thenReturn(3L);
            when(messageRepository.findUnthreadedByAccountOrderByReceivedAt(eq(1L), any(Pageable.class)))
                    .thenReturn(messages(3));

            int assigned = service.backfillAccount(account);

            assertThat(assigned).isEqualTo(3);
            verify(threadingService, times(3)).assignThread(any(MessageEntity.class), eq(account));
            verify(messageRepository, times(1)).findUnthreadedByAccountOrderByReceivedAt(eq(1L), any(Pageable.class));
        }

        @Test
        @DisplayName("A full batch loops; the re-query advances the cursor until a short batch ends it")
        void multipleBatchesLoopUntilShortBatch() {
            AccountEntity account = account(1L, "a@example.com");
            when(messageRepository.countUnthreadedByAccount(1L)).thenReturn(250L);
            when(messageRepository.findUnthreadedByAccountOrderByReceivedAt(eq(1L), any(Pageable.class)))
                    .thenReturn(messages(BATCH_SIZE)).thenReturn(messages(50));

            int assigned = service.backfillAccount(account);

            assertThat(assigned).isEqualTo(250);
            verify(threadingService, times(250)).assignThread(any(MessageEntity.class), eq(account));
            verify(messageRepository, times(2)).findUnthreadedByAccountOrderByReceivedAt(eq(1L), any(Pageable.class));
        }

        @Test
        @DisplayName("An exact multiple of BATCH_SIZE costs one extra empty query before stopping")
        void exactlyBatchSizeRunsOneTrailingEmptyQuery() {
            AccountEntity account = account(1L, "a@example.com");
            when(messageRepository.countUnthreadedByAccount(1L)).thenReturn((long) BATCH_SIZE);
            when(messageRepository.findUnthreadedByAccountOrderByReceivedAt(eq(1L), any(Pageable.class)))
                    .thenReturn(messages(BATCH_SIZE)).thenReturn(List.of());

            int assigned = service.backfillAccount(account);

            assertThat(assigned).isEqualTo(BATCH_SIZE);
            verify(threadingService, times(BATCH_SIZE)).assignThread(any(MessageEntity.class), eq(account));
            verify(messageRepository, times(2)).findUnthreadedByAccountOrderByReceivedAt(eq(1L), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("backfillThreadsOnStartup — account walk")
    class StartupWalk {

        @Test
        @DisplayName("No eligible accounts is a no-op")
        void noAccountsIsNoOp() {
            when(accountRepository.findByActiveTrueAndRequiresReauthFalse()).thenReturn(List.of());

            service.backfillThreadsOnStartup();

            verifyNoInteractions(messageRepository, threadingService);
        }

        @Test
        @DisplayName("Walks every eligible account and backfills each")
        void iteratesEligibleAccounts() {
            AccountEntity a1 = account(1L, "a@example.com");
            AccountEntity a2 = account(2L, "b@example.com");
            when(accountRepository.findByActiveTrueAndRequiresReauthFalse()).thenReturn(List.of(a1, a2));
            when(messageRepository.countUnthreadedByAccount(1L)).thenReturn(2L);
            when(messageRepository.findUnthreadedByAccountOrderByReceivedAt(eq(1L), any(Pageable.class)))
                    .thenReturn(messages(2));
            when(messageRepository.countUnthreadedByAccount(2L)).thenReturn(3L);
            when(messageRepository.findUnthreadedByAccountOrderByReceivedAt(eq(2L), any(Pageable.class)))
                    .thenReturn(messages(3));

            service.backfillThreadsOnStartup();

            verify(threadingService, times(2)).assignThread(any(MessageEntity.class), eq(a1));
            verify(threadingService, times(3)).assignThread(any(MessageEntity.class), eq(a2));
        }

        @Test
        @DisplayName("A failing account is isolated — the remaining accounts still get backfilled")
        void oneAccountFailureDoesNotAbortOthers() {
            AccountEntity failing = account(1L, "boom@example.com");
            AccountEntity healthy = account(2L, "ok@example.com");
            when(accountRepository.findByActiveTrueAndRequiresReauthFalse()).thenReturn(List.of(failing, healthy));
            when(messageRepository.countUnthreadedByAccount(1L)).thenThrow(new RuntimeException("DB down"));
            when(messageRepository.countUnthreadedByAccount(2L)).thenReturn(1L);
            when(messageRepository.findUnthreadedByAccountOrderByReceivedAt(eq(2L), any(Pageable.class)))
                    .thenReturn(messages(1));

            service.backfillThreadsOnStartup();

            verify(threadingService, never()).assignThread(any(MessageEntity.class), eq(failing));
            verify(threadingService, times(1)).assignThread(any(MessageEntity.class), eq(healthy));
        }
    }
}
