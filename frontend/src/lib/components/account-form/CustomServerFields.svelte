<script lang="ts">
	/**
	 * The "custom server" fieldset (IMAP + SMTP host/port/SSL) of the account
	 * form, shown when the user picks the custom-settings mode (i18n key
	 * `serverModeCustom`) instead of a provider template. Extracted from AccountForm.svelte to keep that file focused on
	 * orchestration; the repeated endpoint block lives in
	 * {@link ServerEndpointFields}.
	 */
	import ServerEndpointFields from './ServerEndpointFields.svelte';
	import { _ } from '$lib/i18n/index.js';
	import type { CustomFieldKey } from '$lib/accounts/accountFormValidation.js';

	interface Props {
		imapHost: string;
		imapPort: number | null;
		imapUseSsl: boolean;
		smtpHost: string;
		smtpPort: number | null;
		smtpUseSsl: boolean;
		fieldErrors: Partial<Record<CustomFieldKey, string>>;
	}

	let {
		imapHost = $bindable(),
		imapPort = $bindable(),
		imapUseSsl = $bindable(),
		smtpHost = $bindable(),
		smtpPort = $bindable(),
		smtpUseSsl = $bindable(),
		fieldErrors
	}: Props = $props();
</script>

<fieldset class="space-y-4 rounded-md border border-border bg-muted/30 p-4">
	<legend class="px-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
		{$_('accounts.form.customServerLegend')}
	</legend>
	<p class="text-xs text-muted-foreground">{$_('accounts.form.customServerHint')}</p>

	<ServerEndpointFields
		idPrefix="acc-imap"
		heading={$_('accounts.form.imapHeading')}
		placeholder="imap.example.com"
		bind:host={imapHost}
		bind:port={imapPort}
		bind:useSsl={imapUseSsl}
		hostError={fieldErrors.imapHost ?? null}
		portError={fieldErrors.imapPort ?? null}
	/>

	<ServerEndpointFields
		idPrefix="acc-smtp"
		heading={$_('accounts.form.smtpHeading')}
		placeholder="smtp.example.com"
		bind:host={smtpHost}
		bind:port={smtpPort}
		bind:useSsl={smtpUseSsl}
		hostError={fieldErrors.smtpHost ?? null}
		portError={fieldErrors.smtpPort ?? null}
		separated
	/>

	<p class="text-xs text-muted-foreground">{$_('accounts.form.starttlsNote')}</p>
</fieldset>
