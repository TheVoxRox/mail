package org.voxrox.mailbackend.feature.account.service;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;

import module java.base;

/**
 * The catalog of built-in mail provider templates — the single source of truth
 * for the {@code mail_providers} table.
 *
 * <p>
 * The rows used to be an {@code INSERT} inside {@code V1__init.sql}. That made
 * every catalog change a schema migration: adding a provider, or following a
 * provider that moved a hostname or port, meant shipping a {@code V2+} Flyway
 * script that mutates an installed user's database. Since the table is
 * reference data — {@code MailProviderController} exposes no write endpoint and
 * nothing else writes to it — the catalog belongs in code, and
 * {@link MailProviderCatalogReconciler} projects it into the table on every
 * boot. A catalog change is then an ordinary code change.
 *
 * <p>
 * <b>Ordering is part of the contract.</b> Entries are inserted in declaration
 * order on a fresh install, so a template's row id stays what the original V1
 * seed produced. Append new providers at the end rather than inserting in the
 * middle.
 */
final class MailProviderCatalog {

    /**
     * One built-in provider template.
     *
     * <p>
     * {@code domains} is comma-anchored ({@code ",a.cz,b.cz,"}) so
     * {@code MailProviderRepository.findByDomainKey} can match a whole domain with
     * {@code LIKE '%,<domain>,%'} instead of hitting substring false positives
     * ({@code seznam.cz} inside {@code post.seznam.cz}).
     *
     * <p>
     * Ports 993/465 are implicit SSL/TLS ({@code ssl = true}); the STARTTLS
     * variants (143/587) use {@code ssl = false}. Office 365 submission is
     * STARTTLS-only on 587 — no implicit-SSL endpoint exists — so Microsoft is
     * declared with SMTP 587/{@code false} while its IMAP keeps implicit SSL on
     * 993.
     *
     * <p>
     * {@code oauth2RegistrationId} must match the Spring Security
     * ClientRegistration id in {@code application.properties} and the key in
     * {@code OAuth2TokenServiceRegistry} — backend and frontend route the OAuth
     * flow on that same identifier. It is {@code null} exactly when
     * {@code supportsOauth2} is {@code false}.
     *
     * <p>
     * {@code passwordSignIn} says whether the provider's servers take a password at
     * all; see {@link #requirePasswordSignIn}. It is not projected into the table,
     * which would take a migration for a fact only this class reads. A template
     * without it must support OAuth2, or nothing could sign in.
     */
    record SystemProviderTemplate(String name, String domains, String imapHost, int imapPort, boolean imapSsl,
            String smtpHost, int smtpPort, boolean smtpSsl, boolean supportsOauth2,
            @Nullable String oauth2RegistrationId, boolean passwordSignIn) {

        SystemProviderTemplate {
            if (supportsOauth2 == (oauth2RegistrationId == null)) {
                throw new IllegalArgumentException("Provider " + name + " must declare an oauth2RegistrationId if and "
                        + "only if it supports OAuth2 (supportsOauth2=" + supportsOauth2 + ", oauth2RegistrationId="
                        + oauth2RegistrationId + ").");
            }
            if (!passwordSignIn && !supportsOauth2) {
                throw new IllegalArgumentException(
                        "Provider " + name + " takes neither a password nor an OAuth2 sign-in.");
            }
        }
    }

    /**
     * Seznam stays PASSWORD-only — it publishes no OAuth2 API. Microsoft
     * (Outlook/Office 365) is OAuth-only in the opposite direction: basic auth for
     * personal accounts was switched off on 2024-09-16, and for IMAP in every
     * Microsoft 365 tenant by the end of 2022, past any admin's reach, so the
     * password form must never be offered for it (docs/DECISIONS.md). The
     * frontend's preset says the same as {@code imapPassword: 'none'}.
     */
    static final List<SystemProviderTemplate> SYSTEM_TEMPLATES = List.of(
            new SystemProviderTemplate("Google", ",gmail.com,googlemail.com,", "imap.gmail.com", 993, true,
                    "smtp.gmail.com", 465, true, true, "google", true),
            new SystemProviderTemplate("Seznam", ",seznam.cz,email.cz,post.cz,spoluzaci.cz,", "imap.seznam.cz", 993,
                    true, "smtp.seznam.cz", 465, true, false, null, true),
            new SystemProviderTemplate("Microsoft", ",outlook.com,hotmail.com,live.com,msn.com,",
                    "outlook.office365.com", 993, true, "smtp.office365.com", 587, false, true, "microsoft", false));

    /**
     * Refuses a password sign-in on a provider whose servers take none: creating a
     * password account on it, testing a password against it, or moving a password
     * account onto it. The IMAP login would be refused anyway, and reported as a
     * wrong password, after the password had gone to the provider for nothing. Rows
     * are matched by name, the key {@link MailProviderCatalogReconciler} writes
     * them under; a row outside the catalog takes a password, as every server the
     * catalog does not list does.
     */
    static void requirePasswordSignIn(MailProviderEntity provider) {
        String name = provider.getName();
        boolean takesPassword = SYSTEM_TEMPLATES.stream().filter(template -> template.name().equals(name)).findFirst()
                .map(SystemProviderTemplate::passwordSignIn).orElse(true);
        if (!takesPassword) {
            throw new ValidationException("Provider " + name + " takes no password; its accounts sign in with OAuth2.",
                    "validation.account.passwordNotAccepted", name);
        }
    }

    private MailProviderCatalog() {
    }
}
