<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { get } from 'svelte/store';
	import { tick } from 'svelte';
	import { downloadDiagnosticDump } from '#lib/api/diagnostics.js';
	import { toErrorMessage } from '#lib/api/errors.js';
	import { saveBlobAsFile } from '#lib/download.js';
	import { formatSize } from '#lib/formatters.js';
	import { _, appLocale, LOCALE_LABELS } from '#lib/i18n/index.js';
	import { accountsState } from '#lib/stores/accounts.js';
	import { clientConfig } from '#lib/stores/clientConfig.js';
	import { sessionState } from '#lib/stores/session.js';
	import { updateChannel } from '#lib/stores/updateChannel.js';
	import { CLIENT_VERSION } from '#lib/version.js';
	import { emptyFeedbackForm, feedbackForm, stageFeedback } from '#lib/feedback/handoff.js';
	import {
		buildFeedbackMessage,
		FEEDBACK_ADDRESS,
		FEEDBACK_CATEGORIES,
		FEEDBACK_SUMMARY_MAX_LENGTH,
		feedbackErrors,
		feedbackMessageAsText,
		type FeedbackCategory,
		type FeedbackField,
		type FeedbackMessage
	} from '#lib/feedback/message.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { Field } from '#lib/components/ui/field/index.js';
	import { Input } from '#lib/components/ui/input/index.js';
	import { Surface } from '#lib/components/ui/surface/index.js';
	import { Textarea } from '#lib/components/ui/textarea/index.js';
	import {
		nativeChoiceClass,
		nativeChoiceControlClass
	} from '#lib/components/ui/native-control/index.js';
	import { cn } from '#lib/utils.js';

	const ISSUES_URL = 'https://github.com/TheVoxRox/mail/issues/new/choose';
	const IDEAS_URL = 'https://github.com/TheVoxRox/mail/discussions/categories/ideas';

	/** Where focus goes for each field's error: the control itself, or a group's first radio. */
	const FIELD_INPUT_IDS: Record<FeedbackField, string> = {
		category: `feedback-category-${FEEDBACK_CATEGORIES[0]}`,
		summary: 'feedback-summary',
		description: 'feedback-description'
	};
	const errorIdOf = (field: FeedbackField) => `feedback-${field}-error`;

	const initial = get(feedbackForm);
	let category = $state<FeedbackCategory | null>(initial.category);
	let summary = $state(initial.summary);
	let description = $state(initial.description);
	let includeDiagnostics = $state(initial.includeDiagnostics);

	let fieldErrors = $state<Partial<Record<FeedbackField, string>>>({});
	let announcedField = $state<FeedbackField | null>(null);
	let formError = $state('');
	let preparing = $state(false);
	let copyStatus = $state('');
	let copyFailed = $state(false);
	let saveBusy = $state(false);
	let saveError = $state('');

	$effect(() => {
		feedbackForm.set({ category, summary, description, includeDiagnostics });
	});

	const sessionReady = $derived($sessionState.status === 'ready');
	// Nothing to send from: the copy and the file below are the way, so the form
	// does not offer a step the composer would refuse. While accounts load the
	// step stays, and the composer says so if none turns up.
	const noAccount = $derived(
		$accountsState.status === 'ready' && $accountsState.accounts.length === 0
	);
	const descriptionHintKey = $derived(`settings.feedback.description.hints.${category ?? 'none'}`);

	function clearFieldError(field: FeedbackField): void {
		if (!fieldErrors[field]) return;
		const { [field]: _cleared, ...rest } = fieldErrors;
		fieldErrors = rest;
		if (announcedField === field) announcedField = null;
	}

	/**
	 * Marks every invalid field and moves focus to the first. Only that one is
	 * announced (`announcedField`), as in AccountForm: the others are read with
	 * their fields, and a screen reader handed all of them at once loses them to
	 * the focus move.
	 */
	function validate(): boolean {
		const errors = feedbackErrors({ category, summary, description });
		fieldErrors = Object.fromEntries(
			errors.map((error) => [error.field, $_(error.messageKey, { values: error.values })])
		);
		announcedField = errors[0]?.field ?? null;
		if (errors.length === 0) return true;
		const firstId = FIELD_INPUT_IDS[errors[0].field];
		void tick().then(() => document.getElementById(firstId)?.focus());
		return false;
	}

	function currentMessage(): FeedbackMessage {
		return buildFeedbackMessage(
			{ category: category ?? 'other', summary, description },
			{
				clientVersion: CLIENT_VERSION,
				backendVersion: $sessionState.status === 'ready' ? $sessionState.session.appVersion : null,
				channel: $updateChannel,
				languageLabel: LOCALE_LABELS[$appLocale ?? 'cs']
			},
			$_
		);
	}

	/*
	 * The button stays enabled while the diagnostics download: a disabled button
	 * drops its focus onto <body>, and a failure would then leave the user
	 * nowhere. A second press is ignored instead.
	 */
	async function handleSubmit(event: SubmitEvent) {
		event.preventDefault();
		if (preparing || noAccount) return;
		formError = '';
		if (!validate()) return;
		preparing = true;
		try {
			let diagnostics: { blob: Blob; fileName: string } | null = null;
			// A checkbox ticked before the backend went away stays ticked but disabled.
			if (includeDiagnostics && sessionReady) {
				const dump = await downloadDiagnosticDump();
				const limit = $clientConfig.attachmentMaxBytes;
				if (dump.blob.size > limit) {
					formError = $_('settings.feedback.errors.diagnosticsTooLarge', {
						values: {
							size: formatSize(dump.blob.size, $appLocale),
							limit: formatSize(limit, $appLocale)
						}
					});
					return;
				}
				diagnostics = { blob: dump.blob, fileName: dump.filename };
			}
			const id = stageFeedback({ ...currentMessage(), diagnostics });
			// The composer holds the report from here on, as a draft once saved.
			feedbackForm.set(emptyFeedbackForm());
			await goto(`${resolve('compose')}?feedback=${encodeURIComponent(id)}`);
		} catch (err) {
			formError = toErrorMessage(err);
		} finally {
			preparing = false;
		}
	}

	async function handleCopy() {
		copyStatus = '';
		if (!validate()) return;
		try {
			await navigator.clipboard.writeText(feedbackMessageAsText(currentMessage(), $_));
			copyFailed = false;
			copyStatus = $_('settings.feedback.fallback.copied');
		} catch {
			copyFailed = true;
			copyStatus = $_('settings.feedback.fallback.copyFailed');
		}
	}

	async function handleSaveDiagnostics() {
		if (saveBusy) return;
		saveBusy = true;
		saveError = '';
		try {
			const { blob, filename } = await downloadDiagnosticDump();
			saveBlobAsFile(blob, filename);
		} catch (err) {
			saveError = toErrorMessage(err);
		} finally {
			saveBusy = false;
		}
	}
</script>

<div class="max-w-2xl space-y-4">
	<Surface as="section" class="space-y-4">
		<h2 class="text-sm font-semibold">{$_('settings.feedback.form.heading')}</h2>
		<p class="text-sm text-muted-foreground">{$_('settings.feedback.form.security')}</p>

		<form novalidate onsubmit={handleSubmit} class="space-y-4">
			<fieldset class="space-y-1">
				<legend class="mb-1 block text-sm font-medium text-foreground">
					{$_('settings.feedback.category.legend')}
				</legend>
				{#each FEEDBACK_CATEGORIES as option (option)}
					<label for={`feedback-category-${option}`} class={cn(nativeChoiceClass, 'text-sm')}>
						<input
							id={`feedback-category-${option}`}
							type="radio"
							name="feedback-category"
							value={option}
							bind:group={category}
							onchange={() => clearFieldError('category')}
							aria-describedby={fieldErrors.category ? errorIdOf('category') : undefined}
							class={nativeChoiceControlClass}
						/>
						<span class="text-foreground">{$_(`settings.feedback.category.options.${option}`)}</span
						>
					</label>
				{/each}
				{#if fieldErrors.category}
					<p
						id={errorIdOf('category')}
						class="text-xs text-destructive-foreground"
						role={announcedField === 'category' ? 'alert' : undefined}
					>
						{fieldErrors.category}
					</p>
				{/if}
			</fieldset>

			<Field
				for={FIELD_INPUT_IDS.summary}
				label={$_('settings.feedback.summary.label')}
				hint={$_('settings.feedback.summary.hint', {
					values: { max: FEEDBACK_SUMMARY_MAX_LENGTH }
				})}
				error={fieldErrors.summary}
				errorId={errorIdOf('summary')}
				announce={announcedField === 'summary'}
			>
				{#snippet children(control)}
					<Input
						id={FIELD_INPUT_IDS.summary}
						bind:value={summary}
						oninput={() => clearFieldError('summary')}
						autocomplete="off"
						{...control}
					/>
				{/snippet}
			</Field>

			<Field
				for={FIELD_INPUT_IDS.description}
				label={$_('settings.feedback.description.label')}
				hint={$_(descriptionHintKey)}
				error={fieldErrors.description}
				errorId={errorIdOf('description')}
				announce={announcedField === 'description'}
			>
				{#snippet children(control)}
					<Textarea
						id={FIELD_INPUT_IDS.description}
						bind:value={description}
						oninput={() => clearFieldError('description')}
						rows={8}
						{...control}
					/>
				{/snippet}
			</Field>

			{#if noAccount}
				<p class="text-sm text-foreground">{$_('settings.feedback.form.noAccount')}</p>
			{:else}
				<div class="space-y-1">
					<label for="feedback-diagnostics" class={cn(nativeChoiceClass, 'text-sm')}>
						<input
							id="feedback-diagnostics"
							type="checkbox"
							bind:checked={includeDiagnostics}
							disabled={!sessionReady}
							aria-describedby="feedback-diagnostics-hint"
							class={nativeChoiceControlClass}
						/>
						<span class="text-foreground">{$_('settings.feedback.diagnostics.label')}</span>
					</label>
					<!-- Read with the checkbox through aria-describedby, so hidden from the
					     tree: as a line of its own it repeated the checkbox (see Field). -->
					<p
						id="feedback-diagnostics-hint"
						aria-hidden="true"
						class="text-xs text-muted-foreground"
					>
						{$_('settings.feedback.diagnostics.hint')}
					</p>
				</div>

				<Button type="submit">
					{preparing
						? $_('settings.feedback.form.preparing')
						: $_('settings.feedback.form.continue')}
				</Button>
			{/if}

			{#if formError}
				<Surface variant="danger" padding="sm">
					<p class="text-sm" role="alert">{formError}</p>
				</Surface>
			{/if}
		</form>
	</Surface>

	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.feedback.fallback.heading')}</h2>
		<p class="text-sm text-foreground">
			{$_('settings.feedback.fallback.description', { values: { address: FEEDBACK_ADDRESS } })}
		</p>
		<div class="flex flex-wrap items-center gap-2">
			<Button type="button" onclick={handleCopy} variant="outline" size="xs">
				{$_('settings.feedback.fallback.copy')}
			</Button>
			<Button
				type="button"
				onclick={handleSaveDiagnostics}
				disabled={saveBusy || !sessionReady}
				variant="outline"
				size="xs"
			>
				{saveBusy
					? $_('settings.feedback.fallback.savingDiagnostics')
					: $_('settings.feedback.fallback.saveDiagnostics')}
			</Button>
		</div>
		<!--
			Rendered unconditionally: a live region inserted into the DOM together
			with its text is not reliably announced, so it must exist before the
			outcome of the copy lands.
		-->
		<p
			role="status"
			class={cn('text-xs', copyFailed ? 'text-warning-foreground' : 'text-muted-foreground')}
		>
			{copyStatus}
		</p>
		{#if saveError}
			<Surface variant="danger" padding="sm">
				<p class="text-sm" role="alert">{saveError}</p>
			</Surface>
		{/if}
	</Surface>

	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.feedback.github.heading')}</h2>
		<p class="text-sm text-muted-foreground">{$_('settings.feedback.github.description')}</p>
		<ul class="space-y-1">
			<li>
				<Button
					variant="link"
					size="sm"
					class="px-0"
					href={ISSUES_URL}
					target="_blank"
					rel="noopener noreferrer"
				>
					{$_('settings.feedback.github.issues')}
					<span class="sr-only">{$_('common.opensInNewWindow')}</span>
				</Button>
			</li>
			<li>
				<Button
					variant="link"
					size="sm"
					class="px-0"
					href={IDEAS_URL}
					target="_blank"
					rel="noopener noreferrer"
				>
					{$_('settings.feedback.github.ideas')}
					<span class="sr-only">{$_('common.opensInNewWindow')}</span>
				</Button>
			</li>
		</ul>
	</Surface>
</div>
