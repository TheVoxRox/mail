<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { themePreference, setThemePreference, type ThemePreference } from '$lib/stores/theme.js';
	import { textSize, setTextSize, type TextSize } from '$lib/stores/textSize.js';
	import {
		closeAction,
		messageBodyView,
		messageGrouping,
		readingPane,
		setCloseAction,
		setMessageBodyView,
		setMessageGrouping,
		setReadingPane,
		type CloseAction,
		type MessageBodyView,
		type MessageGrouping,
		type ReadingPane
	} from '$lib/stores/uiLayout.js';

	const THEME_OPTIONS: ReadonlyArray<ThemePreference> = ['system', 'light', 'dark'];
	const TEXT_SIZE_OPTIONS: ReadonlyArray<TextSize> = ['small', 'medium', 'large'];
	const READING_PANE_OPTIONS: ReadonlyArray<ReadingPane> = ['right', 'bottom', 'off'];
	const MESSAGE_BODY_OPTIONS: ReadonlyArray<MessageBodyView> = ['html', 'plain'];
	const GROUPING_OPTIONS: ReadonlyArray<MessageGrouping> = ['flat', 'grouped'];
	const CLOSE_ACTION_OPTIONS: ReadonlyArray<CloseAction> = ['tray', 'quit'];

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

	function handleCloseActionChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as CloseAction;
		setCloseAction(value);
	}
</script>

{#snippet selectCard(setting: {
	id: string;
	section: string;
	value: string;
	onchange: (event: Event) => void;
	options: readonly string[];
})}
	<Surface as="section" class="space-y-3">
		<h2 id={`${setting.id}-label`} class="text-sm font-semibold">
			{$_(`settings.appearance.${setting.section}.heading`)}
		</h2>
		<Field for={setting.id} hint={$_(`settings.appearance.${setting.section}.hint`)}>
			{#snippet children(control)}
				<Select
					id={setting.id}
					value={setting.value}
					onchange={setting.onchange}
					width="full"
					aria-labelledby={`${setting.id}-label`}
					{...control}
				>
					{#each setting.options as option (option)}
						<option value={option}>
							{$_(`settings.appearance.${setting.section}.options.${option}`)}
						</option>
					{/each}
				</Select>
			{/snippet}
		</Field>
	</Surface>
{/snippet}

<!--
	Each card names its select with its own heading (`aria-labelledby`) instead
	of carrying a separate `<label>`. A card holding one control does not need
	two names for it, and two is what it had: the heading plus a field label
	worded differently. Screen-reader users heard both, one after the other, and
	voice-control users were left saying the heading at a control that answered
	to the label.

	The labels were hidden with `sr-only` rather than reworded, which removed the
	visible duplicate but not the spoken one — and the flag then spread to cards
	whose label was *not* a duplicate, so the reason stopped matching the code.
	The headings below carry the wording those labels had, since it was written
	to name a control; `settings.appearance.*.label` is gone.
-->
<div class="max-w-2xl space-y-4">
	{@render selectCard({
		id: 'theme-select',
		section: 'theme',
		value: $themePreference,
		onchange: handleThemeChange,
		options: THEME_OPTIONS
	})}
	{@render selectCard({
		id: 'text-size-select',
		section: 'textSize',
		value: $textSize,
		onchange: handleTextSizeChange,
		options: TEXT_SIZE_OPTIONS
	})}
	{@render selectCard({
		id: 'reading-pane-select',
		section: 'readingPane',
		value: $readingPane,
		onchange: handleReadingPaneChange,
		options: READING_PANE_OPTIONS
	})}
	{@render selectCard({
		id: 'message-grouping-select',
		section: 'grouping',
		value: $messageGrouping,
		onchange: handleGroupingChange,
		options: GROUPING_OPTIONS
	})}
	<!--
		Desktop-only in effect (the tray lives in the Tauri shell), but shown
		unconditionally: the app ships as a desktop bundle, and hiding a setting in
		the browser dev build would make the two surfaces disagree for no gain.
	-->
	{@render selectCard({
		id: 'close-action-select',
		section: 'closeAction',
		value: $closeAction,
		onchange: handleCloseActionChange,
		options: CLOSE_ACTION_OPTIONS
	})}
	{@render selectCard({
		id: 'message-body-select',
		section: 'messageBody',
		value: $messageBodyView,
		onchange: handleMessageBodyChange,
		options: MESSAGE_BODY_OPTIONS
	})}
</div>
