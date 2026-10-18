<script lang="ts">
	import { Surface } from '#lib/components/ui/surface/index.js';
	import { _ } from '#lib/i18n/index.js';
	import type { ProviderPreset } from '#lib/accounts/providerPresets.js';

	interface Props {
		preset: ProviderPreset;
	}

	let { preset }: Props = $props();

	/*
	 * Where the app password is only for accounts with two-factor
	 * authentication, the note leads with the case that needs nothing and is
	 * not a warning: it used to tell every Seznam user their account password
	 * would not work, and for most of them it does.
	 */
	const twoFactorOnly = $derived(preset.appPassword === 'twoFactor');
</script>

<Surface variant={twoFactorOnly ? 'subtle' : 'warning'} padding="sm" role="note">
	<p class="text-sm">
		{$_(
			twoFactorOnly ? 'accounts.form.appPasswordTwoFactorNote' : 'accounts.form.appPasswordWarning',
			{
				values: { provider: $_(`accounts.wizard.presets.${preset.key}`) }
			}
		)}
		{#if preset.appPasswordHelpUrl}
			<a
				href={preset.appPasswordHelpUrl}
				target="_blank"
				rel="noopener noreferrer"
				class="ml-1 underline"
			>
				{$_('accounts.form.appPasswordHelp')}
				<span class="sr-only">{$_('common.opensInNewWindow')}</span>
			</a>
		{/if}
	</p>
</Surface>
