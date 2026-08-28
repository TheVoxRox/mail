<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { DialogDescription, DialogShell, DialogTitle } from '$lib/components/ui/dialog/index.js';
	import { announcePolite } from '$lib/stores/toasts.js';
	import {
		installPromptedUpdate,
		postponePromptedUpdate,
		skipPromptedUpdateVersion,
		updatePromptState,
		type UpdateInstallPhase
	} from '$lib/updates.js';

	/**
	 * Percentage steps that get announced. The bar itself moves in whole
	 * percents, but a screen reader hearing a hundred of those would learn
	 * nothing it could not get from the progress bar on demand — and would lose
	 * the phase announcements in the noise.
	 */
	const ANNOUNCED_STEPS = [25, 50, 75];

	let open = $derived(
		$updatePromptState.status === 'available' || $updatePromptState.status === 'installing'
	);
	let update = $derived(
		$updatePromptState.status === 'available' || $updatePromptState.status === 'installing'
			? $updatePromptState.update
			: null
	);
	let installing = $derived($updatePromptState.status === 'installing');
	let phase = $derived(
		$updatePromptState.status === 'installing' ? $updatePromptState.phase : null
	);
	let progress = $derived(
		$updatePromptState.status === 'installing' ? $updatePromptState.progress : null
	);
	/** `null` while downloading without a `Content-Length`, and outside the download. */
	let percent = $derived(
		phase === 'downloading' && progress && progress.total
			? Math.min(100, Math.round((progress.downloaded / progress.total) * 100))
			: null
	);

	function phaseLabel(current: UpdateInstallPhase): string {
		return $_(`update.prompt.phase.${current}`);
	}

	let announcedPhase: UpdateInstallPhase | null = $state(null);
	let announcedStep = $state(0);

	$effect(() => {
		if (!phase) {
			announcedPhase = null;
			announcedStep = 0;
			return;
		}
		if (phase !== announcedPhase) {
			announcedPhase = phase;
			announcedStep = 0;
			announcePolite(phaseLabel(phase));
		}
	});

	$effect(() => {
		if (phase !== 'downloading' || percent === null) return;
		const reached = ANNOUNCED_STEPS.filter((step) => percent >= step).pop() ?? 0;
		if (reached > announcedStep) {
			announcedStep = reached;
			announcePolite($_('update.prompt.progressAnnouncement', { values: { percent: reached } }));
		}
	});

	/**
	 * Escape and a click outside land here. They close the prompt and nothing
	 * more — skipping the version is a separate, named button, because a
	 * dismissal gesture that silently decides "not this version, ever" is a
	 * decision the user did not make.
	 */
	function handleOpenChange(nextOpen: boolean) {
		if (!nextOpen && !installing) {
			postponePromptedUpdate();
		}
	}
</script>

<DialogShell {open} onOpenChange={handleOpenChange}>
	<DialogTitle>
		{$_('update.prompt.title', { values: { version: update?.version ?? '' } })}
	</DialogTitle>
	<DialogDescription>
		{$_('update.prompt.description', {
			values: {
				version: update?.version ?? '',
				currentVersion: update?.currentVersion ?? ''
			}
		})}
	</DialogDescription>

	{#if phase}
		<div class="mt-4 space-y-2">
			<p class="text-sm">{phaseLabel(phase)}</p>
			<!--
				The bar carries its own value for sighted users; announcements go
				through the app-wide polite region above, at coarse steps, so the
				two do not compete. An unknown Content-Length leaves off
				aria-valuenow, which is what makes the role indeterminate.
			-->
			<div
				role="progressbar"
				aria-label={$_('update.prompt.progressLabel')}
				aria-valuemin={0}
				aria-valuemax={100}
				aria-valuenow={percent ?? undefined}
				aria-valuetext={percent === null
					? $_('update.prompt.progressUnknown')
					: $_('update.prompt.progressAnnouncement', { values: { percent } })}
				class="h-2 w-full overflow-hidden rounded-full bg-muted"
			>
				<div
					class="h-full rounded-full bg-primary transition-[width] duration-200 motion-reduce:transition-none"
					class:animate-pulse={percent === null}
					style:width={percent === null ? '100%' : `${percent}%`}
				></div>
			</div>
		</div>
	{/if}

	<!--
		Order runs least to most wanted, so the primary action is last in the tab
		order as everywhere else in the app, and the one irreversible-ish choice
		(skipping) is the least prominent rather than the easiest to hit.
	-->
	<div class="mt-5 flex flex-wrap justify-end gap-2">
		<Button variant="ghost" onclick={skipPromptedUpdateVersion} disabled={installing}>
			{$_('update.prompt.skipVersion')}
		</Button>
		<Button variant="outline" onclick={postponePromptedUpdate} disabled={installing}>
			{$_('update.prompt.later')}
		</Button>
		<Button onclick={() => void installPromptedUpdate()} disabled={installing}>
			{installing ? $_('update.prompt.installing') : $_('update.prompt.installNow')}
		</Button>
	</div>
</DialogShell>
