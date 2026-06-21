<script lang="ts">
	import { listProviders } from '$lib/api/providers.js';
	import { testAccountConnection } from '$lib/api/accounts.js';
	import { presetForProvider, type ProviderPreset } from '$lib/accounts/providerPresets.js';
	import { createProviderResolver, type ProviderResolver } from '$lib/accounts/providerResolver.js';
	import {
		FIELD_INPUT_IDS,
		validateCustomServer,
		type CustomFieldError,
		type CustomFieldKey
	} from '$lib/accounts/accountFormValidation.js';
	import AccountAppPasswordNotice from '$lib/components/AccountAppPasswordNotice.svelte';
	import CustomServerFields from '$lib/components/account-form/CustomServerFields.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import ProviderLogo from '$lib/components/ProviderLogo.svelte';
	import { Select } from '$lib/components/ui/select/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { _ } from '$lib/i18n/index.js';
	import { cn } from '$lib/utils.js';
	import type {
		AccountCreateRequest,
		AccountConnectionTestRequest,
		AccountResponse,
		AccountUpdateRequest,
		MailProviderResponse,
		MailServerSettings
	} from '$lib/types.js';
	import { onDestroy, onMount, tick } from 'svelte';

	type Mode = 'create' | 'edit';
	type ServerMode = 'provider' | 'custom';

	interface Props {
		mode: Mode;
		initial?: AccountResponse | null;
		/**
		 * Wizard preset drives the preselected provider and any app-password
		 * warning. A concrete preset also hides the provider dropdown until
		 * the user expands the advanced section.
		 */
		preset?: ProviderPreset | null;
		/** E-mail from the previous wizard step (prefills the field). Used when `mode='create'`. */
		prefillEmail?: string;
		/**
		 * Optional override of the default server mode; without it the mode
		 * is derived from the edited account or wizard presets.
		 */
		initialServerMode?: ServerMode;
		/**
		 * Compact mode for the wizard: hides accountName/displayName/
		 * server-mode/server-fields/username and leaves just email +
		 * password + (optionally) the "Advanced settings" link.
		 */
		compact?: boolean;
		/** When in compact mode and the parent wants to offer switching to full mode. */
		onSwitchToAdvanced?: () => void;
		/**
		 * Create-mode only: invoked when the user picks an OAuth-capable provider
		 * in the form and chooses to sign in instead of entering a password. The
		 * parent wizard then switches to its dedicated OAuth step. Without this
		 * callback the OAuth prompt is hidden (e.g. the edit page).
		 */
		onUseOAuth?: (preset: ProviderPreset, email: string) => void;
		onSubmit: (payload: AccountCreateRequest | AccountUpdateRequest) => Promise<void>;
		submitLabel?: string;
	}

	let {
		mode,
		initial = null,
		preset = null,
		prefillEmail = '',
		initialServerMode,
		compact = false,
		onSwitchToAdvanced,
		onUseOAuth,
		onSubmit,
		submitLabel
	}: Props = $props();

	function deriveInitialServerMode(): ServerMode {
		if (initialServerMode) return initialServerMode;
		if (mode === 'edit' && initial && initial.providerId == null) return 'custom';
		if (mode === 'create' && preset != null && preset.backendName == null) return 'custom';
		return 'provider';
	}

	/**
	 * Default value of the "account name" field, derived from the e-mail domain:
	 * the label between "@" and the first dot, with the first letter capitalised.
	 * E.g. info@post.cz → "Post", jan@seznam.cz → "Seznam". Returns "" until a
	 * domain is present (e.g. while the user is still typing the address).
	 */
	function defaultAccountName(value: string): string {
		const trimmed = value.trim();
		const at = trimmed.indexOf('@');
		if (at === -1) return '';
		const label = trimmed.slice(at + 1).split('.')[0];
		if (!label) return '';
		return label.charAt(0).toUpperCase() + label.slice(1);
	}

	let providers = $state<MailProviderResponse[]>([]);
	let serverMode = $state<ServerMode>(deriveInitialServerMode());
	/* svelte-ignore state_referenced_locally */
	let accountName = $state(initial?.accountName ?? defaultAccountName(prefillEmail));
	/* svelte-ignore state_referenced_locally */
	let accountNameTouched = $state(mode === 'edit');
	/* svelte-ignore state_referenced_locally */
	let email = $state(initial?.email ?? prefillEmail);
	let emailInputEl = $state<HTMLInputElement | null>(null);
	let passwordInputEl = $state<HTMLInputElement | null>(null);
	/* svelte-ignore state_referenced_locally */
	let displayName = $state(initial?.displayName ?? '');
	/* svelte-ignore state_referenced_locally */
	let signature = $state(initial?.signature ?? '');
	/* svelte-ignore state_referenced_locally */
	let signatureAutoInsert = $state(initial?.signatureAutoInsert ?? true);
	/* svelte-ignore state_referenced_locally */
	let providerId = $state<number | null>(initial?.providerId ?? null);
	/* svelte-ignore state_referenced_locally */
	let username = $state(initial?.username ?? prefillEmail);
	let password = $state('');
	/* svelte-ignore state_referenced_locally */
	let active = $state(initial?.active ?? true);

	/* svelte-ignore state_referenced_locally */
	let imapHost = $state(initial?.imapHost ?? '');
	/* svelte-ignore state_referenced_locally */
	let imapPort = $state<number | null>(initial?.imapPort ?? 993);
	/* svelte-ignore state_referenced_locally */
	let imapUseSsl = $state(initial?.imapUseSsl ?? true);
	/* svelte-ignore state_referenced_locally */
	let smtpHost = $state(initial?.smtpHost ?? '');
	/* svelte-ignore state_referenced_locally */
	let smtpPort = $state<number | null>(initial?.smtpPort ?? 465);
	/* svelte-ignore state_referenced_locally */
	let smtpUseSsl = $state(initial?.smtpUseSsl ?? true);

	let busy = $state(false);
	let testingConnection = $state(false);
	let errorMessage = $state('');
	let fieldErrors = $state<Partial<Record<CustomFieldKey, string>>>({});
	let connectionTestMessage = $state('');
	let resolvingProvider = $state(false);
	let providerResolvedFromEmail = $state(false);
	let showAdvancedProvider = $state(false);

	function clearFieldErrors(): void {
		fieldErrors = {};
	}

	function focusFieldById(id: string): void {
		void tick().then(() => {
			const el = document.getElementById(id);
			if (el && 'focus' in el && typeof (el as HTMLElement).focus === 'function') {
				(el as HTMLElement).focus();
			}
		});
	}

	let selectedProvider = $derived(
		providerId == null ? null : (providers.find((p) => p.id === providerId) ?? null)
	);
	let activePreset = $derived(
		preset?.backendName != null ? preset : (presetForProvider(selectedProvider) ?? preset)
	);
	/**
	 * An OAuth-capable provider was selected in create mode: offer sign-in
	 * (handled by the parent wizard's OAuth step) instead of forcing an IMAP
	 * password. Null when OAuth is not applicable or the parent opted out.
	 */
	let oauthCtaPreset = $derived(
		mode === 'create' &&
			serverMode === 'provider' &&
			onUseOAuth != null &&
			activePreset?.flow === 'oauth' &&
			activePreset.oauth2RegistrationId != null
			? activePreset
			: null
	);
	let showAppPasswordWarning = $derived(
		activePreset?.requiresAppPassword === true &&
			(activePreset.flow !== 'oauth' || mode === 'create')
	);
	let providerDropdownHidden = $derived(
		preset != null && preset.backendName != null && !showAdvancedProvider
	);
	let isCustomAccount = $derived(initial?.providerId == null && initial?.providerName != null);

	onMount(async () => {
		if (mode === 'create') {
			await tick();
			if (compact) {
				passwordInputEl?.focus();
			} else {
				emailInputEl?.focus();
			}
		}
		try {
			providers = await listProviders();
			if (preset?.backendName != null && providerId == null) {
				// Pair the wizard preset to a concrete provider by the stable OAuth
				// registration id; password-only presets fall back to the name.
				const regId = preset.oauth2RegistrationId?.toLowerCase();
				const target = preset.backendName.toLowerCase();
				const match = providers.find((p) =>
					regId
						? p.oauth2RegistrationId?.toLowerCase() === regId
						: p.name.trim().toLowerCase() === target
				);
				if (match) providerId = match.id;
			}
		} catch (err) {
			errorMessage = toErrorMessage(err);
		}
	});

	const resolver: ProviderResolver = createProviderResolver({
		onStart: () => {
			resolvingProvider = true;
		},
		onEnd: () => {
			resolvingProvider = false;
		},
		onResolved: (provider) => {
			providerId = provider.id;
			providerResolvedFromEmail = true;
			if (!username) username = email.trim();
		},
		onCleared: () => {
			providerId = null;
			providerResolvedFromEmail = false;
		}
	});

	function scheduleResolve() {
		if (mode !== 'create') return;
		if (!accountNameTouched) {
			accountName = defaultAccountName(email);
		}
		if (serverMode !== 'provider') return;
		if (preset != null && preset.backendName != null) return;
		// User picked a provider manually — don't override until we explicitly reset.
		if (providerId != null && !providerResolvedFromEmail) return;
		resolver.schedule(email);
	}

	function handleProviderChange() {
		providerResolvedFromEmail = false;
		resolver.reset();
	}

	function handleUseOAuth() {
		if (oauthCtaPreset) onUseOAuth?.(oauthCtaPreset, email.trim());
	}

	function switchServerMode(next: ServerMode) {
		if (next === serverMode) return;
		errorMessage = '';
		resolver.cancel();
		resolvingProvider = false;
		if (next === 'custom') {
			// Switching to custom keeps manual input; empty fields are filled from the current provider.
			if (selectedProvider) {
				if (!imapHost) imapHost = selectedProvider.imapHost;
				imapPort = selectedProvider.imapPort;
				if (!smtpHost) smtpHost = selectedProvider.smtpHost;
				smtpPort = selectedProvider.smtpPort;
			}
		} else {
			// Provider mode re-enables auto-resolve from the e-mail.
			providerResolvedFromEmail = false;
		}
		serverMode = next;
	}

	function handleAccountNameInput() {
		accountNameTouched = true;
	}

	onDestroy(() => {
		resolver.cancel();
	});

	function validateCustom(): CustomFieldError | null {
		return validateCustomServer({ imapHost, imapPort, smtpHost, smtpPort });
	}

	function applyCustomError(error: CustomFieldError): void {
		fieldErrors = { [error.field]: $_(error.messageKey) };
		focusFieldById(FIELD_INPUT_IDS[error.field]);
	}

	function buildServerPayload(): {
		providerId?: number | null;
		imap?: MailServerSettings;
		smtp?: MailServerSettings;
	} {
		if (serverMode === 'provider') {
			return { providerId: providerId ?? undefined };
		}
		return {
			imap: { host: imapHost.trim(), port: imapPort as number, useSsl: imapUseSsl },
			smtp: { host: smtpHost.trim(), port: smtpPort as number, useSsl: smtpUseSsl }
		};
	}

	async function handleSubmit(event: SubmitEvent) {
		event.preventDefault();
		errorMessage = '';
		connectionTestMessage = '';
		clearFieldErrors();

		if (serverMode === 'provider') {
			if (!providerId) {
				errorMessage = $_('accounts.form.errorSelectProvider');
				return;
			}
		} else {
			const customError = validateCustom();
			if (customError) {
				applyCustomError(customError);
				return;
			}
		}

		busy = true;
		try {
			const server = buildServerPayload();
			if (mode === 'create') {
				const payload: AccountCreateRequest = {
					accountName,
					email,
					displayName: displayName || null,
					username,
					password,
					...server
				};
				await onSubmit(payload);
			} else {
				const payload: AccountUpdateRequest = {
					accountName,
					email,
					displayName: displayName || null,
					signature: signature.trim() ? signature : null,
					signatureAutoInsert,
					username,
					password: password || null,
					active,
					...server
				};
				await onSubmit(payload);
			}
		} catch (err) {
			errorMessage = toErrorMessage(err);
		} finally {
			busy = false;
		}
	}

	type ConnectionTestError =
		| { kind: 'message'; messageKey: string }
		| { kind: 'field'; error: CustomFieldError };

	function validateConnectionTest(): ConnectionTestError | null {
		if (serverMode === 'provider') {
			if (!providerId) {
				return { kind: 'message', messageKey: 'accounts.form.errorSelectProvider' };
			}
		} else {
			const customError = validateCustom();
			if (customError) {
				return { kind: 'field', error: customError };
			}
		}
		if (mode === 'create' && !password) {
			return { kind: 'message', messageKey: 'accounts.form.errorPasswordRequiredForTest' };
		}
		return null;
	}

	async function handleTestConnection() {
		errorMessage = '';
		connectionTestMessage = '';
		clearFieldErrors();
		const validationError = validateConnectionTest();
		if (validationError) {
			if (validationError.kind === 'field') {
				applyCustomError(validationError.error);
			} else {
				errorMessage = $_(validationError.messageKey);
			}
			return;
		}

		testingConnection = true;
		try {
			const payload: AccountConnectionTestRequest = {
				accountId: mode === 'edit' ? initial?.id : null,
				email,
				username,
				password: password || null,
				...buildServerPayload()
			};
			const result = await testAccountConnection(payload);
			connectionTestMessage = result.message || $_('accounts.form.testConnectionSuccess');
		} catch (err) {
			errorMessage = toErrorMessage(err);
		} finally {
			testingConnection = false;
		}
	}

	const segmentedClasses = (selected: boolean) =>
		cn(
			'flex-1 cursor-pointer rounded-md border px-3 py-1.5 text-center text-sm transition-colors focus-within:ring-2 focus-within:ring-ring/40',
			selected
				? 'border-primary bg-primary/10 text-primary'
				: 'border-border bg-background text-muted-foreground hover:text-foreground hover:bg-muted/40'
		);
</script>

<form
	novalidate
	onsubmit={handleSubmit}
	class="max-w-2xl space-y-4"
	aria-label={$_('accounts.form.label')}
>
	{#if !compact}
		<Field
			for="acc-email"
			label={$_('accounts.form.email')}
			hint={resolvingProvider ? $_('accounts.form.resolving') : $_('accounts.form.emailHint')}
		>
			<Input
				id="acc-email"
				type="email"
				bind:value={email}
				bind:ref={emailInputEl}
				oninput={scheduleResolve}
				required
			/>
		</Field>
	{:else if email && selectedProvider}
		<div
			class="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs"
			aria-label={$_('accounts.form.providerDetailsLabel')}
		>
			<div class="flex min-w-0 items-center gap-2">
				<ProviderLogo keyName={activePreset?.key} size="sm" />
				<div class="min-w-0">
					<span class="text-muted-foreground">{$_('accounts.form.email')}:</span>
					<span class="ml-1 font-medium text-foreground">{email}</span>
				</div>
			</div>
			<span class="text-muted-foreground">
				{selectedProvider.name}, IMAP {selectedProvider.imapHost}:{selectedProvider.imapPort}
			</span>
		</div>
	{/if}

	{#if !compact && serverMode === 'provider' && providerResolvedFromEmail && selectedProvider && !resolvingProvider}
		<p
			class="-mt-2 inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
			role="status"
		>
			<ProviderLogo keyName={activePreset?.key} size="sm" />
			{$_('accounts.form.providerDetected', { values: { name: selectedProvider.name } })}
		</p>
	{/if}

	{#if !compact}
		<Field
			for="acc-displayName"
			label={$_('accounts.form.displayName')}
			hint={$_('accounts.form.displayNameHint')}
		>
			<Input id="acc-displayName" type="text" bind:value={displayName} />
		</Field>

		<Field
			for="acc-accountName"
			label={$_('accounts.form.accountName')}
			hint={$_('accounts.form.accountNameHint')}
		>
			<Input
				id="acc-accountName"
				type="text"
				bind:value={accountName}
				oninput={handleAccountNameInput}
				required
			/>
		</Field>
	{/if}

	{#if !compact}
		<fieldset class="space-y-1">
			<legend class="block text-sm font-medium text-foreground">
				{$_('accounts.form.serverModeLabel')}
			</legend>
			<div class="flex gap-2">
				<label class={cn('relative', segmentedClasses(serverMode === 'provider'))}>
					<input
						type="radio"
						name="server-mode"
						value="provider"
						checked={serverMode === 'provider'}
						class="absolute inset-0 h-full w-full cursor-pointer opacity-0"
						onchange={() => switchServerMode('provider')}
					/>
					{$_('accounts.form.serverModeProvider')}
				</label>
				<label class={cn('relative', segmentedClasses(serverMode === 'custom'))}>
					<input
						type="radio"
						name="server-mode"
						value="custom"
						checked={serverMode === 'custom'}
						class="absolute inset-0 h-full w-full cursor-pointer opacity-0"
						onchange={() => switchServerMode('custom')}
					/>
					{$_('accounts.form.serverModeCustom')}
				</label>
			</div>
			{#if mode === 'edit' && isCustomAccount && serverMode === 'provider'}
				<p class="text-xs text-chart-4" role="note">
					{$_('accounts.form.serverModeSwitchToProviderWarning')}
				</p>
			{:else if mode === 'edit' && !isCustomAccount && serverMode === 'custom'}
				<p class="text-xs text-chart-4" role="note">
					{$_('accounts.form.serverModeSwitchToCustomWarning')}
				</p>
			{/if}
		</fieldset>

		{#if serverMode === 'provider'}
			{#if providerDropdownHidden && selectedProvider}
				<div
					class="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs"
					aria-label={$_('accounts.form.providerDetailsLabel')}
				>
					<div class="flex min-w-0 items-center gap-2">
						<ProviderLogo keyName={activePreset?.key} size="sm" />
						<div class="min-w-0">
							<span class="font-medium text-foreground">{selectedProvider.name}</span>
							<span class="ml-2 text-muted-foreground">
								IMAP {selectedProvider.imapHost}:{selectedProvider.imapPort}, SMTP
								{selectedProvider.smtpHost}:{selectedProvider.smtpPort}
							</span>
						</div>
					</div>
					<button
						type="button"
						class="text-primary hover:underline"
						onclick={() => (showAdvancedProvider = true)}
					>
						{$_('accounts.form.provider')}…
					</button>
				</div>
			{:else}
				<Field for="acc-provider" label={$_('accounts.form.provider')}>
					<Select
						id="acc-provider"
						bind:value={providerId}
						required
						width="full"
						onchange={handleProviderChange}
					>
						<option value={null} disabled>{$_('accounts.form.providerPlaceholder')}</option>
						{#each providers as p (p.id)}
							<option value={p.id}>{p.name}</option>
						{/each}
					</Select>
					{#if selectedProvider}
						<div class="flex items-center gap-2 text-xs text-muted-foreground">
							<ProviderLogo keyName={activePreset?.key} size="sm" />
							<span>
								IMAP {selectedProvider.imapHost}:{selectedProvider.imapPort}, SMTP
								{selectedProvider.smtpHost}:{selectedProvider.smtpPort}
							</span>
						</div>
					{/if}
				</Field>
			{/if}
			{#if oauthCtaPreset}
				<Surface variant="success" padding="sm">
					<div class="space-y-2">
						<p class="text-sm" role="status">
							{$_('accounts.form.oauthAvailable', {
								values: { provider: $_(`accounts.wizard.presets.${oauthCtaPreset.key}`) }
							})}
						</p>
						<Button type="button" variant="outline" size="sm" onclick={handleUseOAuth}>
							{$_(`accounts.wizard.${oauthCtaPreset.key}.loginButton`)}
						</Button>
					</div>
				</Surface>
			{/if}
		{:else}
			<CustomServerFields
				bind:imapHost
				bind:imapPort
				bind:imapUseSsl
				bind:smtpHost
				bind:smtpPort
				bind:smtpUseSsl
				{fieldErrors}
			/>
		{/if}
	{/if}

	{#if !compact}
		<Field
			for="acc-username"
			label={$_('accounts.form.username')}
			hint={$_('accounts.form.usernameHint')}
		>
			<Input id="acc-username" type="text" bind:value={username} required />
		</Field>
	{/if}

	{#if showAppPasswordWarning && activePreset && serverMode === 'provider'}
		<AccountAppPasswordNotice preset={activePreset} />
	{/if}

	<Field
		for="acc-password"
		label={$_('accounts.form.password')}
		hint={mode === 'edit' ? $_('accounts.form.passwordEditHint') : null}
	>
		<Input
			id="acc-password"
			type="password"
			bind:value={password}
			bind:ref={passwordInputEl}
			required={mode === 'create'}
			autocomplete="new-password"
		/>
	</Field>

	{#if mode === 'edit' && !compact}
		<Field
			for="acc-signature"
			label={$_('accounts.form.signature')}
			hint={$_('accounts.form.signatureHint')}
		>
			<Textarea
				id="acc-signature"
				bind:value={signature}
				rows={4}
				maxlength={10000}
				disabled={busy}
				placeholder={$_('accounts.form.signaturePlaceholder')}
			/>
		</Field>

		<label
			class="flex items-start gap-3 rounded-md border border-border bg-background/70 p-3 text-sm transition-colors hover:bg-muted/60"
		>
			<input
				type="checkbox"
				bind:checked={signatureAutoInsert}
				disabled={busy}
				class="mt-0.5 h-4 w-4 rounded border-input text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
			/>
			<span class="min-w-0 text-sm text-foreground">{$_('accounts.form.signatureAutoInsert')}</span>
		</label>
	{/if}

	{#if mode === 'edit'}
		<label
			class="flex items-start gap-3 rounded-md border border-border bg-background/70 p-3 text-sm transition-colors hover:bg-muted/60"
		>
			<input
				type="checkbox"
				bind:checked={active}
				class="mt-0.5 h-4 w-4 rounded border-input text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
			/>
			<span class="min-w-0 text-sm text-foreground">{$_('accounts.form.activeCheckbox')}</span>
		</label>
	{/if}

	{#if errorMessage}
		<Surface variant="danger" padding="sm">
			<p role="alert" class="text-sm">{errorMessage}</p>
		</Surface>
	{/if}

	{#if connectionTestMessage}
		<Surface variant="success" padding="sm" role="status">
			<p class="text-sm">{connectionTestMessage}</p>
		</Surface>
	{/if}

	<div class="flex flex-wrap items-center gap-2">
		<Button type="submit" size="sm" disabled={busy || testingConnection}>
			{submitLabel ??
				(mode === 'create' ? $_('accounts.form.submitCreate') : $_('accounts.form.submitSave'))}
		</Button>
		<Button
			type="button"
			variant="outline"
			size="sm"
			disabled={busy || testingConnection}
			onclick={handleTestConnection}
		>
			{testingConnection
				? $_('accounts.form.testingConnection')
				: $_('accounts.form.testConnection')}
		</Button>
		{#if compact && onSwitchToAdvanced}
			<button
				type="button"
				class="text-xs text-muted-foreground hover:text-foreground hover:underline"
				onclick={onSwitchToAdvanced}
			>
				{$_('accounts.wizard.quick.showAdvanced')}
			</button>
		{/if}
	</div>
</form>
