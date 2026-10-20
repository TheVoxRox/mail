<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import AccountForm from '#lib/components/AccountForm.svelte';
	import { createAccount, listAccounts } from '#lib/api/accounts.js';
	import { toErrorMessage } from '#lib/api/errors.js';
	import { triggerAccountSync } from '#lib/api/mailAction.js';
	import { resolveProviderByEmail } from '#lib/api/providers.js';
	import { loadAccounts, setActiveAccount } from '#lib/stores/accounts.js';
	import { pushToast } from '#lib/stores/toasts.js';
	import { startOAuthLogin } from '#lib/api/googleAuth.js';
	import {
		isOtherIdentity,
		pollForOAuthAccount,
		signedInAccountFor
	} from '#lib/accounts/oauthPoll.js';
	import { delayWithAbort } from '#lib/delay.js';
	import { isValidEmailAddress } from '#lib/compose/addresses.js';
	import {
		KNOWN_PRESETS,
		presetForProvider,
		type ProviderPreset
	} from '#lib/accounts/providerPresets.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { Field } from '#lib/components/ui/field/index.js';
	import { Input } from '#lib/components/ui/input/index.js';
	import { PageShell } from '#lib/components/ui/page-shell/index.js';
	import ProviderLogo from '#lib/components/ProviderLogo.svelte';
	import { Surface } from '#lib/components/ui/surface/index.js';
	import { _ } from '#lib/i18n/index.js';
	import { onDestroy } from 'svelte';
	import type { AccountCreateRequest, AccountResponse, AccountUpdateRequest } from '#lib/types.js';

	const customPreset = KNOWN_PRESETS.find((p) => p.key === 'custom')!;
	/**
	 * Offered for an address whose domain no template lists: a work or school
	 * account at Microsoft 365 or Google Workspace signs in under the
	 * organization's own domain. Microsoft first, the case this was added for.
	 */
	const organizationPresets = (['outlook', 'gmail'] as const).map((key) =>
		KNOWN_PRESETS.find((p) => p.key === key)!
	);

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
	let googleError = $state<string | null>(null);
	let oauthStarting = $state(false);
	let showAdvanced = $state(false);

	let emailInputEl = $state<HTMLInputElement | null>(null);
	let googleButtonEl = $state<HTMLButtonElement | null>(null);

	let canSubmitEmail = $derived(isValidEmailAddress(email));
	/**
	 * Set by a Continue the address could not pass. Continue used to be
	 * disabled instead, which said nothing: a disabled button is not a tab
	 * stop, and Enter in the field did nothing either (WCAG 3.3.1).
	 */
	let emailError = $state<string | null>(null);

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
		if (!canSubmitEmail) {
			emailError = $_(
				email.trim() ? 'accounts.form.errorEmailInvalid' : 'accounts.form.errorEmailRequired'
			);
			emailInputEl?.focus();
			return;
		}
		emailError = null;
		const trimmed = email.trim();
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
		step = { kind: 'oauth', preset, email: oauthEmail };
	}

	function backToEmail() {
		oauthAbort?.abort();
		googleError = null;
		showAdvanced = false;
		step = { kind: 'email' };
	}

	async function handleSubmit(payload: AccountCreateRequest | AccountUpdateRequest) {
		const created = await createAccount(payload as AccountCreateRequest);
		await loadAccounts();
		setActiveAccount(created.id);
		void triggerInitialSync(created.id);
		pushToast($_('accounts.createdToast'), { tone: 'success' });
		await goto(resolve('settings/accounts'));
	}

	async function triggerInitialSync(accountId: number) {
		try {
			await triggerAccountSync(accountId);
		} catch (err) {
			console.warn('Initial account sync could not be started.', err);
		}
	}

	// Abort controller for the in-flight OAuth poll: aborting it both wakes the
	// injected delay early and flips shouldContinue, so cancel / leaving the page
	// stops polling immediately.
	let oauthAbort: AbortController | null = null;

	async function waitForOauthAccount(email: string, baseline: AccountResponse[]) {
		oauthAbort?.abort();
		const controller = new AbortController();
		oauthAbort = controller;

		const match = await pollForOAuthAccount({
			email,
			baseline,
			listAccounts,
			sleep: (ms) => delayWithAbort(ms, controller.signal),
			shouldContinue: () => !controller.signal.aborted
		});
		if (controller.signal.aborted) return;

		if (match) {
			await loadAccounts();
			setActiveAccount(match.id);
			void triggerInitialSync(match.id);
			pushToast(
				isOtherIdentity(match, email)
					? $_('accounts.wizard.oauthAddedAsOther', { values: { email: match.email } })
					: $_('accounts.createdToast'),
				{ tone: 'success' }
			);
			await goto(resolve('settings/accounts'));
			return;
		}

		// Budget (plus the final reconcile) exhausted without the account
		// appearing. The login may still have succeeded after we stopped looking,
		// so point the user at the account list rather than implying a hard fail.
		if (step.kind === 'oauth-waiting') {
			googleError = $_('accounts.wizard.oauthWaitingTimeout');
			step = { kind: 'oauth', preset: step.preset, email: step.email };
		}
	}

	function cancelOauthWaiting() {
		oauthAbort?.abort();
		if (step.kind === 'oauth-waiting') {
			step = { kind: 'oauth', preset: step.preset, email: step.email };
		}
	}

	async function handleOAuthLogin() {
		// Guard against a double trigger (e.g. a double-click in the window before the
		// button disables). startOAuthLogin opens a system-browser auth flow; a second
		// flow overwrites the first authorization request in the server session, so the
		// slower callback then fails with `authorization_request_not_found` even though
		// the account was already added. Allow only one start in flight.
		if (step.kind !== 'oauth' || oauthStarting) return;
		const { preset, email: oauthEmail } = step;
		const oauthProvider = preset.oauth2RegistrationId;
		if (!oauthProvider) {
			googleError = $_('accounts.oauthProviderMissing');
			return;
		}
		googleError = null;
		oauthStarting = true;
		try {
			// What the poll measures the sign-in against: an account counts as added
			// only once the sign-in has made it, not because it already had the address.
			const baseline = await listAccounts();
			if (signedInAccountFor(baseline, oauthEmail)) {
				googleError = $_('accounts.wizard.oauthAccountExists', { values: { email: oauthEmail } });
				return;
			}
			await startOAuthLogin(oauthProvider);
			step = { kind: 'oauth-waiting', preset, email: oauthEmail };
			void waitForOauthAccount(oauthEmail, baseline);
		} catch (err) {
			googleError = toErrorMessage(err);
		} finally {
			oauthStarting = false;
		}
	}

	onDestroy(() => oauthAbort?.abort());
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
		<Button variant="link" size="sm" href={resolve('settings/accounts')}
			>{$_('accounts.backToList')}</Button
		>
	{/snippet}

	{#if step.kind === 'email' || step.kind === 'detecting'}
		<Surface as="section" class="max-w-2xl">
			<h2 class="mb-4 text-sm font-semibold">{$_('accounts.wizard.emailHeading')}</h2>

			<form
				novalidate
				onsubmit={handleEmailSubmit}
				class="space-y-3"
				aria-label={$_('accounts.wizard.emailFormLabel')}
			>
				<Field
					for="wizard-email"
					label={$_('accounts.form.email')}
					error={emailError}
					errorId="wizard-email-error"
				>
					{#snippet children(control)}
						<Input
							id="wizard-email"
							type="email"
							bind:value={email}
							bind:ref={emailInputEl}
							placeholder={$_('accounts.wizard.emailPlaceholder')}
							required
							autocomplete="email"
							{...control}
						/>
					{/snippet}
				</Field>

				<div class="flex flex-wrap items-center gap-2">
					<Button type="submit" size="sm" disabled={step.kind === 'detecting'}>
						{step.kind === 'detecting'
							? $_('accounts.wizard.detecting')
							: $_('accounts.wizard.continue')}
					</Button>
					<Button type="button" variant="link" size="sm" onclick={chooseCustomDirectly}>
						{$_('accounts.wizard.manualSetup')}
					</Button>
				</div>
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
				<Button type="button" variant="link" size="sm" onclick={backToEmail}>
					{$_('accounts.wizard.backToEmail')}
				</Button>
			</div>
			<p class="mb-3 text-xs text-muted-foreground">
				{$_(`accounts.wizard.${step.preset.key}.description`)}
			</p>
			<p class="mb-3 text-xs text-muted-foreground">
				{$_(`accounts.wizard.${step.preset.key}.organizationHint`)}
			</p>
			<Button
				variant="outline"
				size="sm"
				onclick={handleOAuthLogin}
				disabled={oauthStarting}
				bind:ref={googleButtonEl}
			>
				{$_(`accounts.wizard.${step.preset.key}.loginButton`)}
			</Button>
			<Button type="button" variant="link" size="sm" class="ml-3" onclick={handleUseImapInstead}>
				{$_('accounts.wizard.useImapInstead')}
			</Button>
			{#if googleError}
				<p class="mt-2 text-xs text-destructive-foreground" role="alert">{googleError}</p>
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
				<Button type="button" variant="link" size="sm" onclick={backToEmail}>
					{$_('accounts.wizard.backToEmail')}
				</Button>
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
				<Button type="button" variant="link" size="sm" onclick={backToEmail}>
					{$_('accounts.wizard.backToEmail')}
				</Button>
			</div>

			{#if step.preset.backendName == null && isValidEmailAddress(step.email)}
				{@const orgEmail = step.email}
				<Surface
					variant="subtle"
					padding="sm"
					class="mb-4 space-y-2"
					role="group"
					aria-labelledby="wizard-organization-heading"
				>
					<h3 id="wizard-organization-heading" class="text-sm font-semibold">
						{$_('accounts.wizard.organization.heading')}
					</h3>
					<p class="text-xs text-muted-foreground">
						{$_('accounts.wizard.organization.description')}
					</p>
					<div class="flex flex-wrap gap-2">
						{#each organizationPresets as orgPreset (orgPreset.key)}
							<Button
								type="button"
								variant="outline"
								size="sm"
								onclick={() => handleUseOAuth(orgPreset, orgEmail)}
							>
								{$_(`accounts.wizard.${orgPreset.key}.loginButton`)}
							</Button>
						{/each}
					</div>
				</Surface>
			{/if}

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
