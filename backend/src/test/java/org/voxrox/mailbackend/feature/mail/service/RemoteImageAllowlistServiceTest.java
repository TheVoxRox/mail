package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.entity.RemoteImageSenderEntity;
import org.voxrox.mailbackend.feature.mail.repository.RemoteImageSenderRepository;

@ExtendWith(MockitoExtension.class)
class RemoteImageAllowlistServiceTest {

    @Mock
    private RemoteImageSenderRepository repository;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private RemoteImageAllowlistService service;

    @Test
    @DisplayName("allow normalizes (trim+lowercase) and persists when the sender is absent")
    void allowNormalizesAndPersistsWhenAbsent() {
        when(accountService.getAccountOrThrow(1L)).thenReturn(new AccountEntity());
        when(repository.existsByAccountIdAndSenderEmail(1L, "news@example.com")).thenReturn(false);

        service.allow(1L, "  News@Example.COM ");

        ArgumentCaptor<RemoteImageSenderEntity> captor = ArgumentCaptor.forClass(RemoteImageSenderEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSenderEmail()).isEqualTo("news@example.com");
    }

    @Test
    @DisplayName("allow is idempotent — no save when the sender is already present")
    void allowIsIdempotentWhenAlreadyPresent() {
        when(accountService.getAccountOrThrow(1L)).thenReturn(new AccountEntity());
        when(repository.existsByAccountIdAndSenderEmail(1L, "news@example.com")).thenReturn(true);

        service.allow(1L, "news@example.com");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("allow ignores a blank sender and never touches the account")
    void allowIgnoresBlankSender() {
        service.allow(1L, "   ");

        verify(accountService, never()).getAccountOrThrow(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("isAllowed normalizes the lookup key")
    void isAllowedNormalizesLookup() {
        when(repository.existsByAccountIdAndSenderEmail(1L, "news@example.com")).thenReturn(true);

        assertThat(service.isAllowed(1L, "NEWS@example.com")).isTrue();
    }

    @Test
    @DisplayName("disallow normalizes before deleting")
    void disallowNormalizesBeforeDelete() {
        service.disallow(1L, "News@Example.com");

        verify(repository).deleteByAccountIdAndSenderEmail(1L, "news@example.com");
    }

    @Test
    @DisplayName("listAllowedSenders delegates to the repository")
    void listDelegatesToRepository() {
        when(repository.findSenderEmailsByAccountId(1L)).thenReturn(List.of("a@x.com", "b@y.com"));

        assertThat(service.listAllowedSenders(1L)).containsExactly("a@x.com", "b@y.com");
    }
}
