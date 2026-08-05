<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { themePreference, setThemePreference, type ThemePreference } from '$lib/stores/theme.js';
	import { textSize, setTextSize, type TextSize } from '$lib/stores/textSize.js';
	import {
		messageBodyView,
		messageGrouping,
		readingPane,
		setMessageBodyView,
		setMessageGrouping,
		setReadingPane,
		type MessageBodyView,
		type MessageGrouping,
		type ReadingPane
	} from '$lib/stores/uiLayout.js';

	const THEME_OPTIONS: ReadonlyArray<ThemePreference> = ['system', 'light', 'dark'];
	const TEXT_SIZE_OPTIONS: ReadonlyArray<TextSize> = ['small', 'medium', 'large'];
	const READING_PANE_OPTIONS: ReadonlyArray<ReadingPane> = ['right', 'bottom', 'off'];
	const MESSAGE_BODY_OPTIONS: ReadonlyArray<MessageBodyView> = ['html', 'plain'];
	const GROUPING_OPTIONS: ReadonlyArray<MessageGrouping> = ['flat', 'grouped'];
	const groupingLabelKey = (option: MessageGrouping) =>
		`settings.appearance.grouping.options.${option}.title`;
	const themeLabelKey = (option: ThemePreference) => `settings.appearance.theme.options.${option}`;
	const textSizeLabelKey = (option: TextSize) => `settings.appearance.textSize.options.${option}`;
	const paneLabelKey = (option: ReadingPane) =>
		`settings.appearance.readingPane.options.${option}.title`;
	const bodyLabelKey = (option: MessageBodyView) =>
		`settings.appearance.messageBody.options.${option}.title`;

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

	function handleMessageBodyChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as MessageBodyView;
		setMessageBodyView(value);
	}

	function handleGroupingChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as MessageGrouping;
		setMessageGrouping(value);
	}
</script>

<div class="max-w-2xl space-y-4">
	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.appearance.theme.heading')}</h2>
		<!--
			labelClass="sr-only": the card heading already shows the same text right
			above the select, so the field label stays screen-reader-only to avoid
			a visible duplicate while keeping the select's accessible name.
		-->
		<Field
			for="theme-select"
			label={$_('settings.appearance.theme.label')}
			labelClass="sr-only"
			hint={$_('settings.appearance.theme.hint')}
		>
			<Select
				id="theme-select"
				value={$themePreference}
				onchange={handleThemeChange}
				width="full"
				aria-describedby="theme-select-hint"
			>
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
			labelClass="sr-only"
			hint={$_('settings.appearance.textSize.hint')}
		>
			<Select
				id="text-size-select"
				value={$textSize}
				onchange={handleTextSizeChange}
				width="full"
				aria-describedby="text-size-select-hint"
			>
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
				aria-describedby="reading-pane-select-hint"
			>
				{#each READING_PANE_OPTIONS as option (option)}
					<option value={option}>{$_(paneLabelKey(option))}</option>
				{/each}
			</Select>
		</Field>
	</Surface>

	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.appearance.grouping.heading')}</h2>
		<Field
			for="message-grouping-select"
			label={$_('settings.appearance.grouping.label')}
			labelClass="sr-only"
			hint={$_('settings.appearance.grouping.hint')}
		>
			<Select
				id="message-grouping-select"
				value={$messageGrouping}
				onchange={handleGroupingChange}
				width="full"
				aria-describedby="message-grouping-select-hint"
			>
				{#each GROUPING_OPTIONS as option (option)}
					<option value={option}>{$_(groupingLabelKey(option))}</option>
				{/each}
			</Select>
		</Field>
	</Surface>

	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">{$_('settings.appearance.messageBody.heading')}</h2>
		<Field
			for="message-body-select"
			label={$_('settings.appearance.messageBody.label')}
			labelClass="sr-only"
			hint={$_('settings.appearance.messageBody.hint')}
		>
			<Select
				id="message-body-select"
				value={$messageBodyView}
				onchange={handleMessageBodyChange}
				width="full"
				aria-describedby="message-body-select-hint"
			>
				{#each MESSAGE_BODY_OPTIONS as option (option)}
					<option value={option}>{$_(bodyLabelKey(option))}</option>
				{/each}
			</Select>
		</Field>
	</Surface>
</div>
