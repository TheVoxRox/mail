package org.voxrox.mailbackend.feature.account.service;

import org.jspecify.annotations.Nullable;

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
     */
    record SystemProviderTemplate(String name, String domains, String imapHost, int imapPort, boolean imapSsl,
            String smtpHost, int smtpPort, boolean smtpSsl, boolean supportsOauth2,
            @Nullable String oauth2RegistrationId) {

        SystemProviderTemplate {
            if (supportsOauth2 == (oauth2RegistrationId == null)) {
                throw new IllegalArgumentException("Provider " + name + " must declare an oauth2RegistrationId if and "
                        + "only if it supports OAuth2 (supportsOauth2=" + supportsOauth2 + ", oauth2RegistrationId="
                        + oauth2RegistrationId + ").");
            }
        }
    }

    /**
     * Seznam stays PASSWORD-only — it publishes no OAuth2 API. Microsoft
     * (Outlook/Office 365) is OAuth-only in the opposite direction: basic auth for
     * personal accounts was switched off on 2024-09-16, so the password form must
     * never be offered for it.
     */
    static final List<SystemProviderTemplate> SYSTEM_TEMPLATES = List.of(
            new SystemProviderTemplate("Google", ",gmail.com,googlemail.com,", "imap.gmail.com", 993, true,
                    "smtp.gmail.com", 465, true, true, "google"),
            new SystemProviderTemplate("Seznam", ",seznam.cz,email.cz,post.cz,spoluzaci.cz,", "imap.seznam.cz", 993,
                    true, "smtp.seznam.cz", 465, true, false, null),
            new SystemProviderTemplate("Microsoft", ",outlook.com,hotmail.com,live.com,msn.com,",
                    "outlook.office365.com", 993, true, "smtp.office365.com", 587, false, true, "microsoft"));

    private MailProviderCatalog() {
    }
}
