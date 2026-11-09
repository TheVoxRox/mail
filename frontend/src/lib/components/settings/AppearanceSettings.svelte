<script lang="ts">
	import { _ } from '#lib/i18n/index.js';
	import { Field } from '#lib/components/ui/field/index.js';
	import { Select } from '#lib/components/ui/select/index.js';
	import { Surface } from '#lib/components/ui/surface/index.js';
	import { themePreference, setThemePreference, type ThemePreference } from '#lib/stores/theme.js';
	import { textSize, setTextSize, type TextSize } from '#lib/stores/textSize.js';
	import { currentListSize, LIST_SIZES, setListSize, type ListSize } from '#lib/stores/listSize.js';
	import {
		closeAction,
		messageBodyView,
		messageGrouping,
		newMailNotifications,
		readingPane,
		setCloseAction,
		setMessageBodyView,
		setMessageGrouping,
		setNewMailNotifications,
		setReadingPane,
		setToastDismissal,
		type CloseAction,
		type MessageBodyView,
		type MessageGrouping,
		type NewMailNotifications,
		type ReadingPane,
		toastDismissal,
		type ToastDismissal
	} from '#lib/stores/uiLayout.js';

	const THEME_OPTIONS: ReadonlyArray<ThemePreference> = ['system', 'light', 'dark'];
	const TEXT_SIZE_OPTIONS: ReadonlyArray<TextSize> = ['small', 'medium', 'large'];
	const READING_PANE_OPTIONS: ReadonlyArray<ReadingPane> = ['right', 'bottom', 'off'];
	const MESSAGE_BODY_OPTIONS: ReadonlyArray<MessageBodyView> = ['html', 'plain'];
	const GROUPING_OPTIONS: ReadonlyArray<MessageGrouping> = ['flat', 'grouped'];
	const CLOSE_ACTION_OPTIONS: ReadonlyArray<CloseAction> = ['tray', 'quit'];
	const NEW_MAIL_NOTIFICATION_OPTIONS: ReadonlyArray<NewMailNotifications> = ['on', 'off'];
	const TOAST_DISMISSAL_OPTIONS: ReadonlyArray<ToastDismissal> = ['auto', 'manual'];

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

	/*
	 * Read once here: the divider in Mail is the only other writer, and it is
	 * not on screen while this page is. "custom" names a size a drag left
	 * between the presets; it is shown, never chosen.
	 */
	let listSize = $state<ListSize | 'custom'>(currentListSize());
	const listSizeOptions = $derived<readonly string[]>(
		listSize === 'custom' ? [...LIST_SIZES, 'custom'] : LIST_SIZES
	);

	function handleListSizeChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as ListSize;
		setListSize(value);
		listSize = value;
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

	function handleNewMailNotificationsChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as NewMailNotifications;
		setNewMailNotifications(value);
	}

	function handleToastDismissalChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value as ToastDismissal;
		setToastDismissal(value);
	}
</script>

{#snippet selectCard(setting: {
	id: string;
	section: string;
	value: string;
	onchange: (event: Event) => void;
	options: readonly string[];
	disabledOptions?: readonly string[];
})}
	<Surface as="section" class="space-y-3">
		<h2 class="text-sm font-semibold">
			<label for={setting.id}>{$_(`settings.appearance.${setting.section}.heading`)}</label>
		</h2>
		<Field for={setting.id} hint={$_(`settings.appearance.${setting.section}.hint`)}>
			{#snippet children(control)}
				<Select
					id={setting.id}
					value={setting.value}
					onchange={setting.onchange}
					width="full"
					{...control}
				>
					{#each setting.options as option (option)}
						<option value={option} disabled={setting.disabledOptions?.includes(option)}>
							{$_(`settings.appearance.${setting.section}.options.${option}`)}
						</option>
					{/each}
				</Select>
			{/snippet}
		</Field>
	</Surface>
{/snippet}

<!--
	Each card's heading is its select's label: a `<label for>` inside the `<h2>`,
	so one wording is both the card's entry in the page outline and the
	control's name. `Field` is given no `label` and renders none of its own. A
	card holding one control does not need two names for it, and two is what it
	once had: the heading plus a field label worded differently. Screen-reader
	users heard both, one after the other, and voice-control users were left
	saying the heading at a control that answered to the label.

	The heading named the select through `aria-labelledby` before, which kept
	one name as well. The W3C forms tutorial prefers `<label for>` wherever it
	can be used, and it can here: a label is phrasing content, allowed inside a
	heading, and a click on the heading text now focuses the select as a label's
	click does.

	Those field labels were first hidden with `sr-only` rather than reworded,
	which removed the visible duplicate but not the spoken one — and the flag
	then spread to cards whose label was *not* a duplicate, so the reason
	stopped matching the code. The headings carry the wording those labels had,
	since it was written to name a control; `settings.appearance.*.label` is
	gone.
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
		id: 'list-size-select',
		section: 'listSize',
		value: listSize,
		onchange: handleListSizeChange,
		options: listSizeOptions,
		disabledOptions: ['custom']
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
	<!--
		Next to the close action because the two belong together: the tray keeps
		the app syncing, and this is what tells anyone about it. Desktop-only in
		effect, shown everywhere for the reason given above.
	-->
	{@render selectCard({
		id: 'new-mail-notifications-select',
		section: 'newMailNotifications',
		value: $newMailNotifications,
		onchange: handleNewMailNotificationsChange,
		options: NEW_MAIL_NOTIFICATION_OPTIONS
	})}
	{@render selectCard({
		id: 'message-body-select',
		section: 'messageBody',
		value: $messageBodyView,
		onchange: handleMessageBodyChange,
		options: MESSAGE_BODY_OPTIONS
	})}
	{@render selectCard({
		id: 'toast-dismissal-select',
		section: 'toastDismissal',
		value: $toastDismissal,
		onchange: handleToastDismissalChange,
		options: TOAST_DISMISSAL_OPTIONS
	})}
</div>
