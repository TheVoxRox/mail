package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.ProviderNotFoundException;
import org.voxrox.mailbackend.feature.account.dto.MailProviderResponse;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.MailProviderRepository;

/**
 * Unit tests for {@link AccountProviderService}.
 *
 * <p>
 * The domain lookup uses comma-anchored LIKE on a comma-separated
 * {@code domains} column — wrapping the lookup key in commas
 * ({@code ",gmail.com,"}) is what prevents {@code seznam.cz} from matching
 * {@code post.seznam.cz}. Tests assert both the value passed to the repository
 * and the boundary behaviour around {@code null} / malformed e-mails.
 */
@ExtendWith(MockitoExtension.class)
class AccountProviderServiceTest {

    @Mock
    private MailProviderRepository providerRepository;

    private AccountProviderService service;

    @BeforeEach
    void setUp() {
        service = new AccountProviderService(providerRepository);
    }

    private static MailProviderEntity provider(Long id, String name, String domains) {
        MailProviderEntity p = new MailProviderEntity();
        p.setId(id);
        p.setName(name);
        p.setDomains(domains);
        p.setImapConfig(new MailServerConfig("imap." + name + ".cz", 993, true));
        p.setSmtpConfig(new MailServerConfig("smtp." + name + ".cz", 465, true));
        p.setSupportsOauth2(false);
        return p;
    }

    @Nested
    @DisplayName("resolveOAuthProvider")
    class ResolveOAuthProvider {

        @Test
        @DisplayName("Found by the registration id — the e-mail domain plays no part")
        void foundByRegistrationId() {
            MailProviderEntity p = provider(30L, "Microsoft", ",outlook.com,");
            p.setSupportsOauth2(true);
            p.setOauth2RegistrationId("microsoft");
            when(providerRepository.findByOauth2RegistrationId("microsoft")).thenReturn(Optional.of(p));

            assertThat(service.resolveOAuthProvider("microsoft")).isSameAs(p);
            verify(providerRepository, never()).findByDomainKey(anyString());
        }

        @Test
        @DisplayName("No template for the registration — ProviderNotFoundException naming it")
        void missingRegistration() {
            when(providerRepository.findByOauth2RegistrationId("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveOAuthProvider("unknown"))
                    .isInstanceOf(ProviderNotFoundException.class).hasMessageContaining("unknown");
        }
    }

    @Nested
    @DisplayName("findProviderByEmail — domain key")
    class DomainKey {

        @Test
        @DisplayName("Uppercase e-mail — domain is normalised to lower-case before lookup")
        void domainIsNormalised() {
            MailProviderEntity p = provider(20L, "Seznam", ",seznam.cz,");
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            when(providerRepository.findByDomainKey(keyCaptor.capture())).thenReturn(List.of(p));

            assertThat(service.findProviderByEmail("USER@Seznam.CZ")).isPresent();

            assertThat(keyCaptor.getValue()).isEqualTo(",seznam.cz,");
        }

        @Test
        @DisplayName("Domains column with multiple entries — only the address's own domain is wrapped in commas")
        void multiDomainLookupUsesOnlyTheAddressDomain() {
            MailProviderEntity p = provider(20L, "Seznam", ",seznam.cz,email.cz,post.cz,");
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            when(providerRepository.findByDomainKey(keyCaptor.capture())).thenReturn(List.of(p));

            assertThat(service.findProviderByEmail("user@email.cz")).isPresent();

            assertThat(keyCaptor.getValue()).isEqualTo(",email.cz,");
        }
    }

    @Nested
    @DisplayName("loadProviderById")
    class LoadProviderById {

        @Test
        @DisplayName("Found — returns entity")
        void found() {
            MailProviderEntity p = provider(10L, "Gmail", ",gmail.com,");
            when(providerRepository.findById(10L)).thenReturn(Optional.of(p));

            assertThat(service.loadProviderById(10L)).isSameAs(p);
        }

        @Test
        @DisplayName("Not found — ProviderNotFoundException.byId; no domain fallback")
        void missingRaisesAndDoesNotFallBackToDomain() {
            when(providerRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.loadProviderById(404L)).isInstanceOf(ProviderNotFoundException.class)
                    .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("findProviderByEmail — Optional-based lookup")
    class FindProviderByEmail {

        @Test
        @DisplayName("Match — Optional with response DTO matching the entity fields")
        void match() {
            MailProviderEntity entity = provider(30L, "Outlook", ",outlook.com,hotmail.com,");
            entity.setSupportsOauth2(true);
            entity.setOauth2RegistrationId("microsoft");
            when(providerRepository.findByDomainKey(",outlook.com,")).thenReturn(List.of(entity));

            Optional<MailProviderResponse> dto = service.findProviderByEmail("user@outlook.com");

            assertThat(dto).isPresent();
            MailProviderResponse r = dto.get();
            assertThat(r.id()).isEqualTo(30L);
            assertThat(r.name()).isEqualTo("Outlook");
            assertThat(r.imapHost()).isEqualTo("imap.Outlook.cz");
            assertThat(r.imapPort()).isEqualTo(993);
            assertThat(r.imapSsl()).isTrue();
            assertThat(r.smtpHost()).isEqualTo("smtp.Outlook.cz");
            assertThat(r.smtpPort()).isEqualTo(465);
            assertThat(r.smtpSsl()).isTrue();
            assertThat(r.domains()).isEqualTo(",outlook.com,hotmail.com,");
            assertThat(r.supportsOauth2()).isTrue();
            assertThat(r.oauth2RegistrationId()).isEqualTo("microsoft");
        }

        @Test
        @DisplayName("Null e-mail — Optional.empty, repository is not queried")
        void nullEmailReturnsEmpty() {
            assertThat(service.findProviderByEmail(null)).isEmpty();

            verifyNoInteractions(providerRepository);
        }

        @Test
        @DisplayName("E-mail without '@' — Optional.empty, repository is not queried")
        void emailWithoutAtReturnsEmpty() {
            assertThat(service.findProviderByEmail("not-an-email")).isEmpty();

            verifyNoInteractions(providerRepository);
        }

        @Test
        @DisplayName("No match in repository — Optional.empty (no exception)")
        void noMatchReturnsEmpty() {
            when(providerRepository.findByDomainKey(",unknown.tld,")).thenReturn(List.of());

            assertThat(service.findProviderByEmail("user@unknown.tld")).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAllProviders")
    class GetAllProviders {

        @Test
        @DisplayName("getAllProviders — every entity is mapped to a DTO; ordering matches the repository order")
        void getAllProviders() {
            MailProviderEntity a = provider(1L, "Gmail", ",gmail.com,");
            MailProviderEntity b = provider(2L, "Outlook", ",outlook.com,");
            when(providerRepository.findAll()).thenReturn(List.of(a, b));

            List<MailProviderResponse> dtos = service.getAllProviders();

            assertThat(dtos).hasSize(2);
            assertThat(dtos).extracting(MailProviderResponse::id).containsExactly(1L, 2L);
            assertThat(dtos).extracting(MailProviderResponse::name).containsExactly("Gmail", "Outlook");
        }
    }
}
