<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { themePreference, setThemePreference, type ThemePreference } from '$lib/stores/theme.js';
	import { textSize, setTextSize, type TextSize } from '$lib/stores/textSize.js';
	import {
		messageBodyView,
		readingPane,
		setMessageBodyView,
		setReadingPane,
		type MessageBodyView,
		type ReadingPane
	} from '$lib/stores/uiLayout.js';

	const THEME_OPTIONS: ReadonlyArray<ThemePreference> = ['system', 'light', 'dark'];
	const TEXT_SIZE_OPTIONS: ReadonlyArray<TextSize> = ['small', 'medium', 'large'];
	const READING_PANE_OPTIONS: ReadonlyArray<ReadingPane> = ['right', 'bottom', 'off'];
	const MESSAGE_BODY_OPTIONS: ReadonlyArray<MessageBodyView> = ['html', 'plain'];
	const themeLabelKey = (option: ThemePreference) => `settings.appearance.theme.options.${option}`;
	const textSizeLabelKey = (option: TextSize) => `settings.appearance.textSize.options.${option}`;
	const paneLabelKey = (option: ReadingPane) =>
		`settings.appearance.readingPane.options.${option}.title`;
	const bodyLabelKey = (option: MessageBodyView) =>
		`settings.appearance.messageBody.options.${option}.title`;
	const bodyOptionInputId = (option: MessageBodyView) => `message-body-${option}`;

	function handleThemeChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as ThemePreference;
		setThemePreference(value);
	}

	function handleTextSizeChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as TextSize;
		setTextSize(value);
	}

	function handleReadingPaneChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as ReadingPane;
		setReadingPane(value);
	}

	function handleMessageBodyChange(value: MessageBodyView) {
		setMessageBodyView(value);
	}
</script>

<div class="max-w-2xl space-y-4">
	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.appearance.theme.heading')}</h2>
		<Field
			for="theme-select"
			label={$_('settings.appearance.theme.label')}
			hint={$_('settings.appearance.theme.hint')}
		>
			<Select id="theme-select" value={$themePreference} onchange={handleThemeChange} width="full">
				{#each THEME_OPTIONS as opt (opt)}
					<option value={opt}>{$_(themeLabelKey(opt))}</option>
				{/each}
			</Select>
		</Field>
	</Surface>

	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.appearance.textSize.heading')}</h2>
		<Field
			for="text-size-select"
			label={$_('settings.appearance.textSize.label')}
			hint={$_('settings.appearance.textSize.hint')}
		>
			<Select id="text-size-select" value={$textSize} onchange={handleTextSizeChange} width="full">
				{#each TEXT_SIZE_OPTIONS as option (option)}
					<option value={option}>{$_(textSizeLabelKey(option))}</option>
				{/each}
			</Select>
		</Field>
	</Surface>

	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.appearance.readingPane.heading')}</h2>
		<Field
			for="reading-pane-select"
			label={$_('settings.appearance.readingPane.label')}
			hint={$_('settings.appearance.readingPane.hint')}
		>
			<Select
				id="reading-pane-select"
				value={$readingPane}
				onchange={handleReadingPaneChange}
				width="full"
			>
				{#each READING_PANE_OPTIONS as option (option)}
					<option value={option}>{$_(paneLabelKey(option))}</option>
				{/each}
			</Select>
		</Field>
	</Surface>

	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.appearance.messageBody.heading')}</h2>
		<fieldset class="space-y-3">
			<legend class="sr-only">{$_('settings.appearance.messageBody.label')}</legend>
			<p class="text-xs text-muted-foreground">
				{$_('settings.appearance.messageBody.hint')}
			</p>
			{#each MESSAGE_BODY_OPTIONS as option (option)}
				{@const inputId = bodyOptionInputId(option)}
				<label
					for={inputId}
					class="flex items-center gap-3 rounded-md border border-border bg-background/70 p-3 text-sm font-medium transition-colors hover:bg-muted/60"
				>
					<input
						id={inputId}
						type="radio"
						name="message-body-view"
						value={option}
						checked={$messageBodyView === option}
						onchange={() => handleMessageBodyChange(option)}
					/>
					<span>{$_(bodyLabelKey(option))}</span>
				</label>
			{/each}
		</fieldset>
	</Surface>
</div>
