package org.voxrox.mailbackend.feature.contact.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactPatchRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;

class ContactMapperTest {

    private ContactMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ContactMapper();
    }

    private ContactEntity entityWithEmails(String... emails) {
        ContactEntity e = new ContactEntity();
        e.setId(42L);
        e.setName("Alice");
        e.setSurname("Liddell");
        e.setNote("VIP");
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        for (int i = 0; i < emails.length; i++) {
            ContactEmailEntity em = new ContactEmailEntity();
            em.setId((long) (i + 1));
            em.setEmail(emails[i]);
            em.setLabel(i == 0 ? EmailLabel.WORK : EmailLabel.HOME);
            em.setPrimary(i == 0);
            em.setContact(e);
            e.getEmails().add(em);
        }
        return e;
    }

    @Test
    @DisplayName("toEntity — all fields transferred, the first email is primary")
    void toEntityMapsAllFields() {
        AccountEntity account = new AccountEntity();
        account.setId(1L);

        var req = new ContactCreateRequest(List.of(new ContactEmailRequest("work@b.cz", EmailLabel.WORK),
                new ContactEmailRequest("home@b.cz", EmailLabel.HOME)), "Alice", "Liddell", "VIP");

        ContactEntity e = mapper.toEntity(req, account);

        assertThat(e.getName()).isEqualTo("Alice");
        assertThat(e.getSurname()).isEqualTo("Liddell");
        assertThat(e.getNote()).isEqualTo("VIP");
        assertThat(e.getAccount()).isSameAs(account);
        assertThat(e.getEmails()).hasSize(2);
        assertThat(e.getEmails().get(0).getEmail()).isEqualTo("work@b.cz");
        assertThat(e.getEmails().get(0).getLabel()).isEqualTo(EmailLabel.WORK);
        assertThat(e.getEmails().get(0).isPrimary()).isTrue();
        assertThat(e.getEmails().get(1).isPrimary()).isFalse();
    }

    @Test
    @DisplayName("toResponse — emails list mapped correctly including the primary flag")
    void toResponseCopiesAllFields() {
        ContactEntity e = entityWithEmails("a@b.cz", "c@d.cz");

        ContactResponse r = mapper.toResponse(e);

        assertThat(r.id()).isEqualTo(42L);
        assertThat(r.emails()).hasSize(2);
        assertThat(r.emails().get(0).email()).isEqualTo("a@b.cz");
        assertThat(r.emails().get(0).primary()).isTrue();
        assertThat(r.emails().get(1).email()).isEqualTo("c@d.cz");
        assertThat(r.emails().get(1).primary()).isFalse();
        assertThat(r.name()).isEqualTo("Alice");
        assertThat(r.surname()).isEqualTo("Liddell");
        assertThat(r.note()).isEqualTo("VIP");
    }

    @Test
    @DisplayName("applyUpdate — overwrites all fields including the entire email list")
    void applyUpdateOverwritesAll() {
        ContactEntity e = entityWithEmails("old@x.cz");
        e.setName("Old");
        e.setSurname("Name");
        e.setNote("Old note");

        mapper.applyUpdate(e, new ContactUpdateRequest(List.of(new ContactEmailRequest("new@x.cz", EmailLabel.WORK)),
                null, null, null));

        assertThat(e.getEmails()).hasSize(1);
        assertThat(e.getEmails().get(0).getEmail()).isEqualTo("new@x.cz");
        assertThat(e.getName()).isNull();
        assertThat(e.getSurname()).isNull();
        assertThat(e.getNote()).isNull();
    }

    @Test
    @DisplayName("applyPatch — non-null fields overwrite, null fields keep the original value")
    void applyPatchOnlyNonNull() {
        ContactEntity e = entityWithEmails("old@x.cz");
        e.setName("Old");
        e.setSurname("Name");
        e.setNote("Old note");

        mapper.applyPatch(e, new ContactPatchRequest(null, "NewName", null, null));

        assertThat(e.getEmails()).hasSize(1);
        assertThat(e.getEmails().get(0).getEmail()).isEqualTo("old@x.cz");
        assertThat(e.getName()).isEqualTo("NewName");
        assertThat(e.getSurname()).isEqualTo("Name");
        assertThat(e.getNote()).isEqualTo("Old note");
    }

    @Test
    @DisplayName("applyPatch — all fields null -> no change")
    void applyPatchAllNull() {
        ContactEntity e = entityWithEmails("old@x.cz");
        e.setName("Old");
        e.setSurname("Name");

        mapper.applyPatch(e, new ContactPatchRequest(null, null, null, null));

        assertThat(e.getEmails()).hasSize(1);
        assertThat(e.getEmails().get(0).getEmail()).isEqualTo("old@x.cz");
        assertThat(e.getName()).isEqualTo("Old");
    }

    @Test
    @DisplayName("applyPatch — non-null emails replace the entire list (replace semantics)")
    void applyPatchEmailsReplaces() {
        ContactEntity e = entityWithEmails("old@x.cz");

        mapper.applyPatch(e, new ContactPatchRequest(List.of(new ContactEmailRequest("new1@x.cz", EmailLabel.WORK),
                new ContactEmailRequest("new2@x.cz", EmailLabel.HOME)), null, null, null));

        assertThat(e.getEmails()).hasSize(2);
        assertThat(e.getEmails().get(0).getEmail()).isEqualTo("new1@x.cz");
        assertThat(e.getEmails().get(0).isPrimary()).isTrue();
        assertThat(e.getEmails().get(1).getEmail()).isEqualTo("new2@x.cz");
        assertThat(e.getEmails().get(1).isPrimary()).isFalse();
    }

    @Test
    @DisplayName("normalizeEmail — trim + lowercase, null propaguje")
    void normalizeEmailBehavior() {
        assertThat(mapper.normalizeEmail("  Alice@Example.COM  ")).isEqualTo("alice@example.com");
        assertThat(mapper.normalizeEmail("a@b.cz")).isEqualTo("a@b.cz");
        assertThat(mapper.normalizeEmail(null)).isNull();
    }

    @Test
    @DisplayName("toEntity — emails stored normalized (lowercase + trim)")
    void toEntityNormalizesEmails() {
        AccountEntity account = new AccountEntity();
        account.setId(1L);

        ContactEntity e = mapper.toEntity(new ContactCreateRequest(
                List.of(new ContactEmailRequest("  Alice@Example.COM  ", null)), "Alice", null, null), account);

        assertThat(e.getEmails().get(0).getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("applyPatch — surname can be changed independently")
    void applyPatchSurnameOnly() {
        ContactEntity e = entityWithEmails("a@b.cz");
        e.setName("Alice");
        e.setSurname("Old");

        mapper.applyPatch(e, new ContactPatchRequest(null, null, "Liddell", null));

        assertThat(e.getName()).isEqualTo("Alice");
        assertThat(e.getSurname()).isEqualTo("Liddell");
    }
}
