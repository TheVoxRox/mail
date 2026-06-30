<script lang="ts">
	import favicon from '$lib/assets/favicon.svg';
	import AppRail from '$lib/components/AppRail.svelte';
	import LiveAnnouncer from '$lib/components/LiveAnnouncer.svelte';
	import ToastRegion from '$lib/components/ToastRegion.svelte';
	import { onMount, type Component } from 'svelte';
	import { afterNavigate, goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/stores';
	import { SvelteURLSearchParams } from 'svelte/reactivity';
	import { bootstrap } from '$lib/bootstrap.js';
	import { reportClientError, resetClientErrorReportingForTests } from '$lib/api/clientErrors.js';
	import { downloadDiagnosticDump } from '$lib/api/diagnostics.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { initErrorReporting } from '$lib/errorReporting.js';
	import { sessionState } from '$lib/stores/session.js';
	import { accountsState, resolvedActiveAccountId } from '$lib/stores/accounts.js';
	import { backendSidecarState } from '$lib/backend/sidecar.js';
	import { watchSidecarAutoRestart } from '$lib/backend/sidecarRecovery.js';
	import { bootState } from '$lib/stores/boot.js';
	import { setNativeWindowTitle } from '$lib/windowTitle.js';
	import { _ } from '$lib/i18n/index.js';
	import { openPalette, paletteOpen } from '$lib/stores/palette.js';
	import { initThemeSideEffects } from '$lib/stores/theme.js';
	import { initTextSizeSideEffects } from '$lib/stores/textSize.js';
	import { type WorkspaceMode, workspaceHref, workspaceMode } from '$lib/stores/workspaceMode.js';
	import { handleGlobalKeydown } from '$lib/shortcuts/globalShortcuts.js';
	import { selectedMessage } from '$lib/stores/selectedMessage.js';
	import { forwardMessage, replyToMessage } from '$lib/mail/actions.js';
	import { deleteMessages, toggleMessageFlag, toggleMessageSeen } from '$lib/mail/mailbox.js';
	import { loadSidebar } from '$lib/components/sidebar/loader.js';
	import BootErrorView from '$lib/components/boot/BootErrorView.svelte';
	import BootLoadingView from '$lib/components/boot/BootLoadingView.svelte';
	import {
		resetUpdateStateForTests,
		showMockUpdateFailureForTests,
		showMockUpdateForTests
	} from '$lib/updates.js';
	import '../app.css';

	let { children } = $props();

	/*
	 * Lazy-loaded dialog components. Each is shown on a rare trigger (Ctrl+K
	 * palette, update prompts once per release, confirms on destructive
	 * actions), so keeping them out of the initial bundle measurably shrinks
	 * boot parse cost. Components are prefetched in onMount in parallel with
	 * bootstrap() — they typically land long before the user triggers them,
	 * because bootstrap takes 1-3 s while each dialog chunk is 5-30 KB.
	 */
	let CommandPaletteComp = $state<Component | null>(null);
	let ConfirmDialogComp = $state<Component | null>(null);
	let UpdatePromptDialogComp = $state<Component | null>(null);
	let UpdateFailureDialogComp = $state<Component | null>(null);

	/*
	 * Sidebar is loaded per active workspaceMode (see ./components/sidebar/
	 * loader.ts). Only one is ever visible at a time; eager-loading all three
	 * pulled each sidebar's bits-ui + per-feature stores into the initial
	 * bundle even though only one ever renders.
	 */
	const sidebarPromise = $derived(loadSidebar($workspaceMode));

	let diagnosticBusy = $state(false);
	let diagnosticError = $state<string | null>(null);
	const diagnosticDisabled = $derived(diagnosticBusy || $sessionState.status !== 'ready');

	/*
	 * Workspace-aware aria-label for the <main> element in the ready state.
	 * We deliberately do NOT prefix with "Main content:" — screen readers
	 * automatically append "main region" (or the localised equivalent) from
	 * role=main, so prefixing would produce duplication (e.g. "Main content:
	 * Settings, main region"). Best practice: a landmark's aria-label
	 * carries only the context, never the role name. Boot/error <main>
	 * elements have no aria-label at all — their state is transitive and
	 * the generic landmark announcement is enough.
	 */
	const mainLandmarkLabel = $derived($_(`workspace.${$workspaceMode}`));

	/*
	 * Native OS window title (taskbar / Alt+Tab, and the name a screen reader
	 * reads when the window gains focus) tracks boot readiness: a "loading" title
	 * until the backend is up or boot fails, then the app name. The window is
	 * created with the loading title in Rust (src-tauri/src/lib.rs) so the very
	 * first focus — before the webview even hydrates — is already informative.
	 * document.title (the webview/route title) is handled separately by SvelteKit.
	 */
	$effect(() => {
		const settled = $bootState.phase === 'ready' || $bootState.phase === 'failed';
		setNativeWindowTitle(settled ? $_('app.title') : $_('app.titleLoading'));
	});

	const bootPhaseTextKey = $derived.by(() => {
		switch ($bootState.phase) {
			case 'starting-sidecar':
				return 'app.bootPhase.startingSidecar';
			case 'waiting-for-session':
				return 'app.bootPhase.waitingForSession';
			case 'checking-readiness':
				return 'app.bootPhase.checkingReadiness';
			case 'loading-client-config':
				return 'app.bootPhase.loadingClientConfig';
			case 'loading-accounts':
				return 'app.bootPhase.loadingAccounts';
			case 'failed':
				return 'app.bootFailed';
			case 'ready':
				return 'settings.about.connection.ready';
			case 'idle':
			default:
				return 'app.waitingForBackend';
		}
	});

	/*
	 * After every navigation (and after the initial load) we move focus to
	 * <main> unless another element actively claimed it (autofocus,
	 * programmatic focus() in a component's onMount). Without this
	 * SvelteKit would leave focus on <body>, so a keyboard user would have
	 * to Tab through the entire AppRail + sidebar before reaching real
	 * content.
	 *
	 * `requestAnimationFrame` waits for Svelte to commit the new DOM and
	 * for components to finish onMount — only then do we check
	 * `document.activeElement` so we don't steal focus from an autofocused
	 * input.
	 */
	afterNavigate(() => {
		if (typeof window === 'undefined') return;
		requestAnimationFrame(() => {
			const active = document.activeElement;
			const isDefaultFocus =
				!active || active === document.body || active === document.documentElement;
			if (!isDefaultFocus) return;
			const main = document.getElementById('main-content');
			main?.focus({ preventScroll: true });
		});
	});

	onMount(() => {
		const cleanupTheme = initThemeSideEffects();
		const cleanupTextSize = initTextSizeSideEffects();
		const cleanupErrorReporting = initErrorReporting();

		/*
		 * After an automatic sidecar restart the backend returns on a new port
		 * with a new handshake key; re-handshake so requests / SSE stop hitting
		 * the dead old port (see lib/backend/sidecarRecovery.ts).
		 */
		const stopSidecarAutoRestartWatch = watchSidecarAutoRestart();

		const exposeE2E = window.localStorage.getItem('mail.e2e') === '1';
		if (exposeE2E) {
			window.__MAIL_E2E__ = {
				openPalette,
				reportClientError,
				resetClientErrorReportingForTests,
				showMockUpdateForTests,
				showMockUpdateFailureForTests,
				resetUpdateStateForTests
			};
		}
		// i18n is initialised eagerly at module import (see lib/i18n/index.ts —
		// messages are bundled via import.meta.glob({ eager: true }), so `$_`
		// works synchronously from the first render). No await needed here.
		bootstrap().catch(() => {});

		/*
		 * Prefetch lazy dialog chunks in parallel with bootstrap. Bootstrap takes
		 * 1-3 s while each chunk is small, so by the time the user can trigger
		 * any of these (post-boot), the component is already resolved.
		 */
		void import('$lib/components/CommandPalette.svelte').then(
			(m) => (CommandPaletteComp = m.default as Component)
		);
		void import('$lib/components/ConfirmDialog.svelte').then(
			(m) => (ConfirmDialogComp = m.default as Component)
		);
		void import('$lib/components/UpdatePromptDialog.svelte').then(
			(m) => (UpdatePromptDialogComp = m.default as Component)
		);
		void import('$lib/components/UpdateFailureDialog.svelte').then(
			(m) => (UpdateFailureDialogComp = m.default as Component)
		);
		return () => {
			if (window.__MAIL_E2E__) {
				delete window.__MAIL_E2E__;
			}
			stopSidecarAutoRestartWatch();
			cleanupErrorReporting();
			cleanupTheme();
			cleanupTextSize();
		};
	});

	async function retryBootstrap() {
		try {
			diagnosticError = null;
			await bootstrap({ force: true, restartSidecar: $backendSidecarState.status === 'error' });
		} catch {
			// Error state is exposed by the session/accounts stores rendered below.
		}
	}

	async function restartBootstrap() {
		try {
			diagnosticError = null;
			await bootstrap({ force: true, restartSidecar: true });
		} catch {
			// Error state is exposed by the boot/session/accounts stores rendered below.
		}
	}

	async function handleDownloadDiagnosticDump() {
		if (diagnosticBusy || $sessionState.status !== 'ready') return;
		diagnosticBusy = true;
		diagnosticError = null;
		try {
			const { blob, filename } = await downloadDiagnosticDump();
			const url = URL.createObjectURL(blob);
			const link = document.createElement('a');
			link.href = url;
			link.download = filename;
			document.body.append(link);
			link.click();
			link.remove();
			URL.revokeObjectURL(url);
		} catch (err) {
			diagnosticError = toErrorMessage(err);
		} finally {
			diagnosticBusy = false;
		}
	}

	async function goToWorkspace(mode: WorkspaceMode) {
		await goto(workspaceHref(mode, $resolvedActiveAccountId));
	}

	async function goToCompose() {
		await goto(resolve('/compose'));
	}

	async function goToShortcuts() {
		await goto(resolve('/settings/shortcuts'));
	}

	async function goToNewContact() {
		const accountId = $resolvedActiveAccountId ?? Number($page.params.accountId);
		if (!accountId) return;
		const params = new SvelteURLSearchParams($page.url.searchParams);
		params.set('create', '1');
		const query = params.toString();
		await goto(
			`${resolve('/contacts/[accountId]', {
				accountId: String(accountId)
			})}${query ? `?${query}` : ''}`
		);
	}

	async function goToPrimaryNewAction() {
		if ($workspaceMode === 'contacts') {
			await goToNewContact();
			return;
		}
		await goToCompose();
	}

	/*
	 * A message is "open" when the route carries a stableId segment, i.e.
	 * /mail/[accountId]/[folderName]/[stableId] — five segments once split on
	 * "/". The message-action shortcuts apply only there, not when a row is
	 * merely focused in the list.
	 */
	function isOpenMessageRoute(pathname: string): boolean {
		return pathname.startsWith('/mail/') && pathname.split('/').length >= 5;
	}

	function runOnOpenMessage(action: (stableId: string) => unknown): void {
		const stableId = $selectedMessage?.stableId;
		if (stableId) void action(stableId);
	}

	function handleGlobalKeydownWired(event: KeyboardEvent) {
		handleGlobalKeydown(event, {
			openPalette,
			isPaletteOpen: () => $paletteOpen,
			goToShortcuts,
			goToPrimaryNewAction,
			goToWorkspace,
			getMessageShortcutContext: () => {
				const selected = $selectedMessage;
				if (!selected?.stableId || !isOpenMessageRoute($page.url.pathname)) return null;
				return { seen: selected.detail?.seen ?? false };
			},
			reply: () => runOnOpenMessage((id) => replyToMessage(id, false)),
			replyAll: () => runOnOpenMessage((id) => replyToMessage(id, true)),
			forward: () => runOnOpenMessage((id) => forwardMessage(id)),
			toggleFlag: () => runOnOpenMessage((id) => toggleMessageFlag(id)),
			toggleSeen: () => runOnOpenMessage((id) => toggleMessageSeen(id)),
			deleteMessage: () => runOnOpenMessage((id) => deleteMessages([id]))
		});
	}
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>{$_('app.title')}</title>
</svelte:head>

<svelte:window onkeydown={handleGlobalKeydownWired} />

<!--
	Mounted at the root, outside every {#if}, so the screen-reader live regions
	exist from the first render and never remount across i18n / boot transitions.
-->
<LiveAnnouncer />

<div class="flex h-screen flex-col bg-background text-foreground antialiased">
	<a href="#main-content" class="sr-only-focusable">{$_('app.skipToMain')}</a>
	<a href={resolve('/settings/shortcuts')} class="sr-only-focusable">
		{$_('app.skipToShortcuts')}
	</a>

	<div class="flex flex-1 overflow-hidden bg-muted/30">
		{#if $backendSidecarState.status === 'error'}
			<BootErrorView
				errorMessage={$backendSidecarState.error.message}
				onRetry={retryBootstrap}
				onRestart={restartBootstrap}
			/>
		{:else if $bootState.phase === 'ready' && $sessionState.status === 'ready' && $accountsState.status === 'ready'}
			<AppRail />
			{#await sidebarPromise then sidebarMod}
				{@const Sidebar = sidebarMod.default}
				<Sidebar />
			{:catch}
				<!--
					A lazy sidebar chunk failed to load (corrupt/missing bundle on a
					desktop install). Render a visible, screen-reader-announced
					message in the sidebar slot instead of silently dropping the
					primary navigation. The loader (sidebar/loader.ts) evicts the
					failed chunk from its cache, so navigating away and back retries.
				-->
				<div
					class="flex h-full w-64 shrink-0 flex-col gap-2 border-r border-sidebar-border bg-sidebar px-3 py-4 text-sm text-muted-foreground"
					role="alert"
				>
					{$_('app.sidebarLoadFailed')}
				</div>
			{/await}
			<main
				id="main-content"
				tabindex="-1"
				aria-label={mainLandmarkLabel}
				class="flex flex-1 flex-col overflow-hidden bg-background"
			>
				{@render children()}
			</main>
		{:else if $bootState.phase === 'failed' || $sessionState.status === 'error' || $accountsState.status === 'error'}
			{@const bootErrorMessage =
				$bootState.phase === 'failed' && $bootState.error
					? $bootState.error.message
					: $sessionState.status === 'error'
						? $sessionState.error.message
						: $accountsState.status === 'error'
							? $accountsState.error.message
							: ''}
			<BootErrorView
				errorMessage={bootErrorMessage}
				onRetry={retryBootstrap}
				onRestart={restartBootstrap}
				onDownloadDiagnostic={handleDownloadDiagnosticDump}
				{diagnosticBusy}
				{diagnosticDisabled}
				{diagnosticError}
				diagnosticUnavailable={$sessionState.status !== 'ready'}
			/>
		{:else}
			<BootLoadingView
				statusKey={bootPhaseTextKey}
				slowLevel={$bootState.slowLevel}
				onRetry={retryBootstrap}
				onRestart={restartBootstrap}
				onDownloadDiagnostic={handleDownloadDiagnosticDump}
				{diagnosticBusy}
				{diagnosticDisabled}
				{diagnosticError}
				diagnosticUnavailable={$sessionState.status !== 'ready'}
			/>
		{/if}
	</div>

	<ToastRegion />
	{#if CommandPaletteComp}
		<CommandPaletteComp />
	{/if}
	{#if UpdatePromptDialogComp}
		<UpdatePromptDialogComp />
	{/if}
	{#if UpdateFailureDialogComp}
		<UpdateFailureDialogComp />
	{/if}
	{#if ConfirmDialogComp}
		<ConfirmDialogComp />
	{/if}
</div>
