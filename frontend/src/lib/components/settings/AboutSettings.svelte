<script lang="ts">
	import { downloadDiagnosticDump } from '$lib/api/diagnostics.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { saveBlobAsFile } from '$lib/download.js';
	import { sessionState, initSession } from '$lib/stores/session.js';
	import { loadAccounts } from '$lib/stores/accounts.js';
	import { folders } from '$lib/stores/folders.js';
	import { notificationsStatus, lastSync } from '$lib/stores/notifications.js';
	import { _ } from '$lib/i18n/index.js';
	import { folderLabel } from '$lib/mail/folderLabel.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import ThirdPartyNotices from '$lib/components/settings/ThirdPartyNotices.svelte';
	import { checkForUpdateManually } from '$lib/updates.js';
	import {
		setUpdateChannel,
		UPDATE_CHANNELS,
		updateChannel,
		type UpdateChannel
	} from '$lib/stores/updateChannel.js';
	import { CLIENT_VERSION } from '$lib/version.js';
	import { dev } from '$app/environment';
	import { onMount } from 'svelte';

	function maskKey(key: string): string {
		if (key.length <= 8) return '***';
		return `${key.slice(0, 4)}…${key.slice(-4)}`;
	}

	let showTechnicalDiagnostics = $state(dev);
	let reloadBusy = $state(false);
	let reloadError = $state<string | null>(null);
	let diagnosticBusy = $state(false);
	let diagnosticError = $state<string | null>(null);
	let updateCheckBusy = $state(false);
	let updateCheckStatus = $state<string | null>(null);

	onMount(() => {
		showTechnicalDiagnostics = dev || window.localStorage.getItem('mail.e2e') === '1';
	});

	const connectionStatus = $derived.by(() => {
		switch ($sessionState.status) {
			case 'ready':
				return {
					textKey: 'settings.about.connection.ready',
					className: 'text-foreground'
				};
			case 'loading':
				return {
					textKey: 'settings.about.connection.loading',
					className: 'text-muted-foreground'
				};
			case 'error':
				return {
					textKey: 'settings.about.connection.error',
					className: 'text-destructive'
				};
			default:
				return {
					textKey: 'settings.about.connection.idle',
					className: 'text-muted-foreground'
				};
		}
	});

	const notificationStatus = $derived.by(() => {
		switch ($notificationsStatus) {
			case 'open':
				return {
					textKey: 'app.sseOnline',
					className: 'font-medium text-foreground',
					dotClass: 'bg-emerald-500'
				};
			case 'connecting':
				return {
					textKey: 'app.sseConnecting',
					className: 'text-muted-foreground',
					dotClass: 'bg-muted-foreground/50'
				};
			case 'error':
				return {
					textKey: 'app.sseOffline',
					className: 'text-destructive',
					dotClass: 'bg-destructive'
				};
			default:
				return {
					textKey: 'app.sseIdle',
					className: 'text-muted-foreground',
					dotClass: 'bg-muted-foreground/50'
				};
		}
	});

	async function handleReloadSession() {
		if (reloadBusy) return;
		reloadBusy = true;
		reloadError = null;
		try {
			await initSession();
			await loadAccounts();
		} catch (err) {
			reloadError = toErrorMessage(err);
		} finally {
			reloadBusy = false;
		}
	}

	async function handleDownloadDiagnosticDump() {
		if (diagnosticBusy) return;
		diagnosticBusy = true;
		diagnosticError = null;
		try {
			const { blob, filename } = await downloadDiagnosticDump();
			saveBlobAsFile(blob, filename);
		} catch (err) {
			diagnosticError = toErrorMessage(err);
		} finally {
			diagnosticBusy = false;
		}
	}

	async function handleCheckForUpdates() {
		if (updateCheckBusy) return;
		updateCheckBusy = true;
		updateCheckStatus = null;
		try {
			const result = await checkForUpdateManually();
			updateCheckStatus = result.status;
		} finally {
			updateCheckBusy = false;
		}
	}

	const channelLabelKey = (option: UpdateChannel) =>
		`settings.about.versions.channel.options.${option}`;

	function handleUpdateChannelChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as UpdateChannel;
		setUpdateChannel(value);
		// A stale result line ("You are using the latest version.") would silently
		// refer to the previous channel once the preference changes.
		updateCheckStatus = null;
	}
</script>

<div class="max-w-2xl space-y-4">
	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.about.versions.heading')}</h2>
		<dl class="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2 text-sm">
			<dt class="text-muted-foreground">{$_('settings.about.versions.frontend')}</dt>
			<dd>{CLIENT_VERSION}</dd>

			<dt class="text-muted-foreground">{$_('settings.about.versions.backend')}</dt>
			<dd>
				{#if $sessionState.status === 'ready'}
					{$sessionState.session.appVersion}
				{:else if $sessionState.status === 'loading'}
					<span class="text-muted-foreground">{$_('settings.about.versions.backendLoading')}</span>
				{:else}
					<span class="text-muted-foreground"
						>{$_('settings.about.versions.backendUnavailable')}</span
					>
				{/if}
			</dd>
		</dl>

		<div class="border-t border-border pt-3">
			<Field
				for="update-channel-select"
				label={$_('settings.about.versions.channel.label')}
				hint={$_('settings.about.versions.channel.hint')}
			>
				<Select
					id="update-channel-select"
					value={$updateChannel}
					onchange={handleUpdateChannelChange}
					width="full"
					aria-describedby="update-channel-select-hint"
				>
					{#each UPDATE_CHANNELS as option (option)}
						<option value={option}>{$_(channelLabelKey(option))}</option>
					{/each}
				</Select>
			</Field>
		</div>

		<div class="flex flex-wrap items-center gap-2 border-t border-border pt-3">
			<Button
				type="button"
				onclick={handleCheckForUpdates}
				disabled={updateCheckBusy}
				variant="outline"
				size="xs"
			>
				{updateCheckBusy
					? $_('settings.about.versions.checkingUpdates')
					: $_('settings.about.versions.checkUpdates')}
			</Button>
			<!--
				Rendered unconditionally: a live region inserted into the DOM
				together with its text is not reliably announced, so the status
				element must exist before the result lands.
			-->
			<span
				role="status"
				class={`text-xs ${updateCheckStatus === 'failed' ? 'text-warning-foreground' : 'text-muted-foreground'}`}
			>
				{updateCheckStatus ? $_(`settings.about.versions.update.${updateCheckStatus}`) : ''}
			</span>
		</div>
	</Surface>

	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.about.connection.heading')}</h2>
		<!--
			role="status" doubles as the polite announcement for the reconnect
			button below: the session store drives this text through loading →
			ready/error, so a screen reader hears the outcome without hunting
			for it (errors additionally raise the role="alert" surfaces).
		-->
		<p role="status" class={`text-sm ${connectionStatus.className}`}>
			{$_(connectionStatus.textKey)}
		</p>

		<dl class="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2 text-sm">
			<dt class="text-muted-foreground">{$_('settings.about.connection.notifications')}</dt>
			<dd class="inline-flex items-center gap-1.5">
				<span class={`h-1.5 w-1.5 rounded-full ${notificationStatus.dotClass}`} aria-hidden="true"
				></span>
				<span class={notificationStatus.className}>{$_(notificationStatus.textKey)}</span>
			</dd>

			<dt class="text-muted-foreground">{$_('settings.about.connection.lastSync')}</dt>
			<dd>
				{#if $lastSync}
					{@const lastSyncFolder = $folders.find((f) => f.folderRef === $lastSync.folderName)}
					{$_('settings.about.connection.lastSyncValue', {
						values: {
							folder: lastSyncFolder ? folderLabel(lastSyncFolder, $_) : $lastSync.folderName,
							count: $lastSync.newMessagesCount
						}
					})}
				{:else}
					<span class="text-muted-foreground">{$_('settings.about.connection.noSyncYet')}</span>
				{/if}
			</dd>
		</dl>

		{#if $sessionState.status === 'error'}
			<StateMessage padding="none">{$_('settings.about.connection.errorHint')}</StateMessage>
		{/if}

		<Button
			type="button"
			onclick={handleReloadSession}
			disabled={reloadBusy}
			variant="outline"
			size="xs"
		>
			{reloadBusy
				? $_('settings.about.connection.reconnecting')
				: $_('settings.about.connection.reconnect')}
		</Button>
		<div class="flex flex-wrap items-center gap-2 border-t border-border pt-3">
			<Button
				type="button"
				onclick={handleDownloadDiagnosticDump}
				disabled={diagnosticBusy || $sessionState.status !== 'ready'}
				variant="outline"
				size="xs"
			>
				{diagnosticBusy
					? $_('settings.about.session.downloadingDump')
					: $_('settings.about.session.downloadDump')}
			</Button>
			<span class="text-xs text-muted-foreground">{$_('settings.about.session.dumpHint')}</span>
		</div>
		{#if reloadError}
			<Surface variant="danger" padding="sm">
				<p class="text-sm" role="alert">{reloadError}</p>
			</Surface>
		{/if}
		{#if diagnosticError}
			<Surface variant="danger" padding="sm">
				<p class="text-sm" role="alert">{diagnosticError}</p>
			</Surface>
		{/if}
	</Surface>

	{#if showTechnicalDiagnostics}
		<Surface as="section" class="space-y-3">
			<h2 class="text-sm font-semibold">{$_('settings.about.session.heading')}</h2>
			<dl class="grid grid-cols-[8rem_1fr] gap-x-3 gap-y-2 text-sm">
				<dt class="text-muted-foreground">{$_('app.sessionLabel')}</dt>
				<dd>
					{#if $sessionState.status === 'ready'}
						{$sessionState.session.appName} @ :{$sessionState.session.port}
					{:else if $sessionState.status === 'loading'}
						{$_('app.sessionLoading')}
					{:else if $sessionState.status === 'error'}
						<span class="text-destructive">{$sessionState.error.message}</span>
					{:else}
						{$_('app.sessionNotInitialized')}
					{/if}
				</dd>
				{#if $sessionState.status === 'ready'}
					{@const s = $sessionState.session}
					<dt class="text-muted-foreground">{$_('settings.about.session.apiVersion')}</dt>
					<dd>{s.apiVersion}</dd>
					<dt class="text-muted-foreground">{$_('settings.about.session.minClientVersion')}</dt>
					<dd>{s.minClientVersion}</dd>
					<dt class="text-muted-foreground">{$_('settings.about.session.dbSchemaVersion')}</dt>
					<dd>{s.dbSchemaVersion}</dd>
					<dt class="text-muted-foreground">{$_('settings.about.session.port')}</dt>
					<dd>{s.port}</dd>
					<dt class="text-muted-foreground">{$_('settings.about.session.baseUrl')}</dt>
					<dd class="truncate">{s.baseUrl}</dd>
					<dt class="text-muted-foreground">{$_('settings.about.session.apiKey')}</dt>
					<dd class="font-mono">{maskKey(s.apiKey)}</dd>
				{/if}
			</dl>
		</Surface>
	{/if}

	<ThirdPartyNotices />
</div>
