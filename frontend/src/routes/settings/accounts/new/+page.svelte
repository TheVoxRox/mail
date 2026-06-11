<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import AccountForm from '$lib/components/AccountForm.svelte';
	import { createAccount, listAccounts } from '$lib/api/accounts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { triggerAccountSync } from '$lib/api/mailAction.js';
	import { resolveProviderByEmail } from '$lib/api/providers.js';
	import { loadAccounts, setActiveAccount } from '$lib/stores/accounts.js';
	import { startOAuthLogin } from '$lib/api/googleAuth.js';
	import { isValidEmailAddress } from '$lib/compose/addresses.js';
	import {
		KNOWN_PRESETS,
		presetForProvider,
		type ProviderPreset
	} from '$lib/accounts/providerPresets.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import ProviderLogo from '$lib/components/ProviderLogo.svelte';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { onDestroy } from 'svelte';
	import type { AccountCreateRequest, AccountUpdateRequest } from '$lib/types.js';

	const customPreset = KNOWN_PRESETS.find((p) => p.key === 'custom')!;

	type FormServerMode = 'provider' | 'custom';
	type Step =
		| { kind: 'email' }
		| { kind: 'detecting' }
		| { kind: 'oauth'; preset: ProviderPreset; email: string }
		| { kind: 'oauth-waiting'; preset: ProviderPreset; email: string }
		| {
				kind: 'form';
				preset: ProviderPreset;
				email: string;
				serverMode: FormServerMode;
		  };

	let step = $state<Step>({ kind: 'email' });
	let email = $state('');
	let detectError = $state<string | null>(null);
	let googleError = $state<string | null>(null);
	let showAdvanced = $state(false);

	let emailInputEl = $state<HTMLInputElement | null>(null);
	let googleButtonEl = $state<HTMLButtonElement | null>(null);

	let canSubmitEmail = $derived(isValidEmailAddress(email));

	let useCompactForm = $derived(
		step.kind === 'form' && step.preset.backendName != null && !showAdvanced
	);

	let pageDescription = $derived.by(() => {
		if (step.kind === 'email' || step.kind === 'detecting') {
			return $_('accounts.newPageDescription');
		}
		if (step.kind === 'oauth') {
			return $_('accounts.wizard.oauthPageDescription');
		}
		if (step.kind === 'oauth-waiting') {
			return $_('accounts.wizard.oauthWaitingPageDescription');
		}
		return $_('accounts.wizard.formPageDescription');
	});

	$effect(() => {
		if (step.kind === 'email') {
			emailInputEl?.focus();
		} else if (step.kind === 'oauth') {
			googleButtonEl?.focus();
		}
	});

	async function handleEmailSubmit(event?: SubmitEvent) {
		event?.preventDefault();
		if (!canSubmitEmail) return;
		const trimmed = email.trim();
		detectError = null;
		step = { kind: 'detecting' };
		try {
			const provider = await resolveProviderByEmail(trimmed);
			const preset = presetForProvider(provider);
			if (preset?.flow === 'oauth') {
				step = { kind: 'oauth', preset, email: trimmed };
			} else {
				step = {
					kind: 'form',
					preset: preset ?? customPreset,
					email: trimmed,
					serverMode: 'provider'
				};
			}
		} catch {
			// Unknown domain → custom flow with the e-mail prefilled.
			step = { kind: 'form', preset: customPreset, email: trimmed, serverMode: 'custom' };
		}
	}

	function chooseCustomDirectly() {
		detectError = null;
		step = { kind: 'form', preset: customPreset, email: email.trim(), serverMode: 'custom' };
	}

	function configureImapInstead(preset: ProviderPreset, emailAddress: string) {
		googleError = null;
		showAdvanced = true;
		step = { kind: 'form', preset, email: emailAddress, serverMode: 'provider' };
	}

	function handleUseImapInstead() {
		if (step.kind !== 'oauth') return;
		configureImapInstead(step.preset, step.email);
	}

	/**
	 * The user picked an OAuth-capable provider inside the form (e.g. a custom
	 * domain hosted at Microsoft that domain detection missed) and chose to sign
	 * in rather than enter a password. Hand off to the dedicated OAuth step,
	 * using the address currently typed in the form.
	 */
	function handleUseOAuth(preset: ProviderPreset, oauthEmail: string) {
		googleError = null;
		detectError = null;
		step = { kind: 'oauth', preset, email: oauthEmail };
	}

	function backToEmail() {
		stopOauthPolling();
		googleError = null;
		detectError = null;
		showAdvanced = false;
		step = { kind: 'email' };
	}

	async function handleSubmit(payload: AccountCreateRequest | AccountUpdateRequest) {
		const created = await createAccount(payload as AccountCreateRequest);
		await loadAccounts();
		setActiveAccount(created.id);
		void triggerInitialSync(created.id);
		await goto(resolve('/settings/accounts'));
	}

	async function triggerInitialSync(accountId: number) {
		try {
			await triggerAccountSync(accountId);
		} catch (err) {
			console.warn('Initial account sync could not be started.', err);
		}
	}

	let oauthPollTimer: ReturnType<typeof setTimeout> | null = null;
	let oauthPollAttempts = 0;
	// 5 minutes total: first 15 attempts at 2s = 30s, then 5s interval × 54 ≈ 4.5 min.
	const OAUTH_POLL_FAST_INTERVAL_MS = 2000;
	const OAUTH_POLL_SLOW_INTERVAL_MS = 5000;
	const OAUTH_POLL_FAST_ATTEMPTS = 15;
	const OAUTH_POLL_MAX_ATTEMPTS = 69;

	function stopOauthPolling() {
		if (oauthPollTimer != null) {
			clearTimeout(oauthPollTimer);
			oauthPollTimer = null;
		}
		oauthPollAttempts = 0;
	}

	async function pollForOauthAccount(email: string): Promise<boolean> {
		try {
			const accounts = await listAccounts();
			const target = email.toLowerCase();
			const match = accounts.find((a) => a.email.trim().toLowerCase() === target);
			if (match) {
				stopOauthPolling();
				await loadAccounts();
				setActiveAccount(match.id);
				void triggerInitialSync(match.id);
				await goto(resolve('/settings/accounts'));
				return true;
			}
		} catch {
			// Network error — keep polling, the user can Cancel.
		}
		return false;
	}

	function scheduleNextPoll(email: string) {
		const interval =
			oauthPollAttempts < OAUTH_POLL_FAST_ATTEMPTS
				? OAUTH_POLL_FAST_INTERVAL_MS
				: OAUTH_POLL_SLOW_INTERVAL_MS;
		oauthPollTimer = setTimeout(async () => {
			oauthPollAttempts++;
			const done = await pollForOauthAccount(email);
			if (done) return;
			if (oauthPollAttempts >= OAUTH_POLL_MAX_ATTEMPTS) {
				stopOauthPolling();
				if (step.kind === 'oauth-waiting') {
					googleError = $_('accounts.wizard.oauthWaitingTimeout');
					step = { kind: 'oauth', preset: step.preset, email: step.email };
				}
				return;
			}
			if (step.kind === 'oauth-waiting') scheduleNextPoll(email);
		}, interval);
	}

	function cancelOauthWaiting() {
		stopOauthPolling();
		if (step.kind === 'oauth-waiting') {
			step = { kind: 'oauth', preset: step.preset, email: step.email };
		}
	}

	async function handleOAuthLogin() {
		if (step.kind !== 'oauth') return;
		const { preset, email: oauthEmail } = step;
		const oauthProvider = preset.oauth2RegistrationId;
		if (!oauthProvider) {
			googleError = $_('accounts.oauthProviderMissing');
			return;
		}
		googleError = null;
		try {
			await startOAuthLogin(oauthProvider);
			step = { kind: 'oauth-waiting', preset, email: oauthEmail };
			stopOauthPolling();
			scheduleNextPoll(oauthEmail);
		} catch (err) {
			googleError = toErrorMessage(err);
		}
	}

	onDestroy(stopOauthPolling);
</script>

<svelte:head>
	<title>{$_('accounts.newPageTitle')}</title>
</svelte:head>

<PageShell
	title={$_('accounts.headingNew')}
	description={pageDescription}
	contentClass="max-w-4xl space-y-4"
>
	{#snippet actions()}
		<a
			href={resolve('/settings/accounts')}
			class="text-sm text-muted-foreground hover:text-foreground hover:underline"
		>
			{$_('accounts.backToList')}
		</a>
	{/snippet}

	{#if step.kind === 'email' || step.kind === 'detecting'}
		<Surface as="section" class="max-w-2xl">
			<h2 class="mb-4 text-sm font-semibold">{$_('accounts.wizard.emailHeading')}</h2>

			<form
				onsubmit={handleEmailSubmit}
				class="space-y-3"
				aria-label={$_('accounts.wizard.emailFormLabel')}
			>
				<Field for="wizard-email" label={$_('accounts.form.email')}>
					<Input
						id="wizard-email"
						type="email"
						bind:value={email}
						bind:ref={emailInputEl}
						placeholder="name@example.com"
						required
						autocomplete="email"
					/>
				</Field>

				<div class="flex flex-wrap items-center gap-2">
					<Button type="submit" size="sm" disabled={!canSubmitEmail || step.kind === 'detecting'}>
						{step.kind === 'detecting'
							? $_('accounts.wizard.detecting')
							: $_('accounts.wizard.continue')}
					</Button>
					<button
						type="button"
						class="text-xs text-muted-foreground hover:text-foreground hover:underline"
						onclick={chooseCustomDirectly}
					>
						{$_('accounts.wizard.manualSetup')}
					</button>
				</div>

				{#if detectError}
					<p class="text-xs text-destructive" role="alert">{detectError}</p>
				{/if}
			</form>
		</Surface>
	{:else if step.kind === 'oauth'}
		<Surface as="section" class="max-w-2xl">
			<div class="mb-2 flex flex-wrap items-start justify-between gap-2">
				<div class="flex min-w-0 items-start gap-3">
					<ProviderLogo keyName={step.preset.key} />
					<h2 class="min-w-0 text-sm font-semibold">
						{$_('accounts.wizard.oauthConfirmHeading', { values: { email: step.email } })}
					</h2>
				</div>
				<button
					type="button"
					onclick={backToEmail}
					class="text-xs text-muted-foreground hover:text-foreground hover:underline"
				>
					{$_('accounts.wizard.backToEmail')}
				</button>
			</div>
			<p class="mb-3 text-xs text-muted-foreground">
				{$_(`accounts.wizard.${step.preset.key}.description`)}
			</p>
			<Button variant="outline" size="sm" onclick={handleOAuthLogin} bind:ref={googleButtonEl}>
				{$_(`accounts.wizard.${step.preset.key}.loginButton`)}
			</Button>
			<button
				type="button"
				class="ml-3 text-xs text-muted-foreground hover:text-foreground hover:underline"
				onclick={handleUseImapInstead}
			>
				{$_('accounts.wizard.useImapInstead')}
			</button>
			{#if googleError}
				<p class="mt-2 text-xs text-destructive" role="alert">{googleError}</p>
			{/if}
		</Surface>
	{:else if step.kind === 'oauth-waiting'}
		<Surface as="section" class="max-w-2xl">
			<div class="flex flex-wrap items-start justify-between gap-3">
				<div class="flex min-w-0 items-start gap-3" role="status" aria-live="polite">
					<span
						aria-hidden="true"
						class="mt-0.5 inline-block size-4 shrink-0 animate-spin rounded-full border-2 border-current border-t-transparent text-primary"
					></span>
					<div class="min-w-0 space-y-1">
						<p class="text-sm font-semibold">{$_('accounts.wizard.oauthWaitingHeading')}</p>
						<p class="text-xs text-muted-foreground">
							{$_('accounts.wizard.oauthWaitingHint', { values: { email: step.email } })}
						</p>
					</div>
				</div>
				<button
					type="button"
					onclick={backToEmail}
					class="text-xs text-muted-foreground hover:text-foreground hover:underline"
				>
					{$_('accounts.wizard.backToEmail')}
				</button>
			</div>
			<div class="mt-4">
				<Button variant="outline" size="sm" onclick={cancelOauthWaiting}>
					{$_('accounts.wizard.cancel')}
				</Button>
			</div>
		</Surface>
	{:else if step.kind === 'form'}
		<Surface as="section" class="max-w-2xl">
			<div class="mb-4 flex flex-wrap items-start justify-between gap-2">
				<div class="flex min-w-0 items-start gap-3">
					<ProviderLogo keyName={step.preset.key} />
					<h2 class="min-w-0 text-sm font-semibold">
						{step.preset.backendName
							? $_('accounts.wizard.detectedHeading', {
									values: { name: $_(`accounts.wizard.presets.${step.preset.key}`) }
								})
							: $_('accounts.wizard.presets.custom')}
					</h2>
				</div>
				<button
					type="button"
					onclick={backToEmail}
					class="text-xs text-muted-foreground hover:text-foreground hover:underline"
				>
					{$_('accounts.wizard.backToEmail')}
				</button>
			</div>

			<AccountForm
				mode="create"
				preset={step.preset}
				prefillEmail={step.email}
				initialServerMode={step.serverMode}
				compact={useCompactForm}
				onSwitchToAdvanced={() => (showAdvanced = true)}
				onUseOAuth={handleUseOAuth}
				onSubmit={handleSubmit}
				submitLabel={$_('accounts.createSubmitLabel')}
			/>
		</Surface>
	{/if}
</PageShell>
