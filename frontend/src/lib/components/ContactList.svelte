<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { bulkDeleteContacts, deleteContact } from '$lib/api/contacts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { _ } from '$lib/i18n/index.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { contactCounts } from '$lib/stores/contactCounts.js';
	import { announcePolite, pushToast } from '$lib/stores/toasts.js';
	import ContactLabelAssignDialog from '$lib/components/ContactLabelAssignDialog.svelte';
	import ContactMergeDialog from '$lib/components/ContactMergeDialog.svelte';
	import Icon from '$lib/components/Icon.svelte';
	import Pagination from '$lib/components/Pagination.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { avatarColorClass } from '$lib/components/ui/avatar/index.js';
	import { nativeControlClass } from '$lib/components/ui/native-control/index.js';
	import { createRovingGrid } from '$lib/components/grid/rovingGrid.svelte.js';
	import type { ContactResponse, PagedResponse } from '$lib/types.js';
	import type { ContactSort } from '$lib/api/contacts.js';
	import { cn } from '$lib/utils.js';
	import { focusRingInset } from '$lib/components/ui/focus-ring/index.js';
	import { tick } from 'svelte';

	interface Props {
		page: PagedResponse<ContactResponse>;
		sort?: ContactSort | null;
		labelId?: number | null;
		/** Display name of the active label filter, for the empty state. */
		activeLabelName?: string | null;
		onChanged: () => void | Promise<void>;
		/**
		 * Where a row's edit control points. The form is a view of the contacts
		 * route (`?edit=`), so it has an address and the control that opens it is
		 * a link; the screen that owns the URL builds it.
		 */
		editHref: (id: number) => string;
		/**
		 * Opens the edit form from the paths that are not the link itself — a
		 * click on the row, Enter on a non-action cell. Those are activations of
		 * the row, not of a control, so they navigate programmatically.
		 */
		onEdit: (id: number) => void;
		onFilterApply?: (filters: { sort: ContactSort | null; labelId: number | null }) => void;
		/** Where to page to, 0-based; the pager clamps before it calls. */
		onNavigate: (target: number) => void;
		/**
		 * Contact to put the roving focus on once the list renders — the one the
		 * user just came back from editing. The list unmounts while the form is
		 * open, so the position cannot survive inside it.
		 */
		restoreFocusContactId?: number | null;
		/** Fired once the restore above was attempted, so the caller can clear it. */
		onFocusRestored?: () => void;
	}

	let {
		page,
		sort = null,
		labelId = null,
		activeLabelName = null,
		onChanged,
		editHref,
		onEdit,
		onFilterApply,
		onNavigate,
		restoreFocusContactId = null,
		onFocusRestored
	}: Props = $props();

	const DEFAULT_SORT: ContactSort = 'surname';
	const SORT_OPTIONS = [
		{ value: 'surname', label: 'contacts.sortOptions.surname' },
		{ value: 'name', label: 'contacts.sortOptions.name' },
		{ value: 'recent', label: 'contacts.sortOptions.recent' }
	] as const satisfies ReadonlyArray<{ value: ContactSort; label: string }>;
	const sortValue = $derived<ContactSort>(sort ?? DEFAULT_SORT);
	let pendingSortValue = $state<ContactSort>(DEFAULT_SORT);
	const sortFilterDirty = $derived(pendingSortValue !== sortValue);
	// The filter offers the account's own labels; the counts store already holds
	// them in display order and is refreshed on every list load.
	const labelOptions = $derived($contactCounts?.labels ?? []);
	const labelValue = $derived(labelId == null ? '' : String(labelId));
	let pendingLabelValue = $state('');
	const labelFilterDirty = $derived(pendingLabelValue !== labelValue);
	const filtersDirty = $derived(sortFilterDirty || labelFilterDirty);

	let selectedIds = $state<number[]>([]);
	let bulkBusy = $state(false);
	let bulkError = $state<string | null>(null);

	let visibleIds = $derived(page.content.map((contact) => contact.id));
	let selectedVisibleIds = $derived(selectedIds.filter((id) => visibleIds.includes(id)));
	let allVisibleSelected = $derived(
		visibleIds.length > 0 && visibleIds.every((id) => selectedIds.includes(id))
	);
	let someVisibleSelected = $derived(selectedVisibleIds.length > 0 && !allVisibleSelected);
	let mergeDialogOpen = $state(false);
	let labelDialogOpen = $state(false);
	const selectedContacts = $derived(
		page.content.filter((contact) => selectedIds.includes(contact.id))
	);

	function openMergeDialog(): void {
		if (selectedVisibleIds.length < 2) return;
		mergeDialogOpen = true;
	}

	function openLabelDialog(): void {
		if (selectedVisibleIds.length === 0) return;
		labelDialogOpen = true;
	}

	/*
	 * The list is an ARIA grid with a roving tabindex, the same keyboard model
	 * as the message list: one tab stop for the whole table, arrows move
	 * between cells. Without it every row contributed four tab stops (checkbox
	 * + three action buttons), so reaching the pagination below a full page
	 * meant tabbing through ~80 controls. The action buttons are cells of the
	 * row like any other — arrows reach them, Enter/Space activates them.
	 */
	const COL_SELECT = 0;
	const COL_NAME = 1;
	const COL_EMAIL = 2;
	const COL_LABELS = 3;
	const COL_NOTE = 4;
	const COL_COMPOSE = 5;
	const COL_EDIT = 6;
	const COL_DELETE = 7;
	const MAX_COL = COL_DELETE;

	let selectAllInput = $state<HTMLInputElement | null>(null);
	let tableBodyElement = $state<HTMLTableSectionElement | null>(null);
	let emptyStateElement = $state<HTMLParagraphElement | null>(null);
	const grid = createRovingGrid({
		element: () => tableBodyElement,
		initialCol: COL_NAME,
		maxCol: MAX_COL
	});

	$effect(() => {
		if (selectAllInput) selectAllInput.indeterminate = someVisibleSelected;
	});

	$effect(() => {
		pendingSortValue = sortValue;
		pendingLabelValue = labelValue;
	});

	// Keep the roving row inside the page when the list shrinks (delete, filter).
	$effect(() => {
		grid.clampRow(page.content.length);
	});

	/**
	 * Puts the roving focus on `contactId`'s name cell — the row's reading
	 * anchor. When that contact is gone (it was the one deleted), falls back to
	 * whichever row now sits at `fallbackIndex`, clamped to the page. Returns
	 * false when this render has no such row to give focus to.
	 */
	function focusContactRowNow(contactId: number | null, fallbackIndex: number): boolean {
		const known = contactId == null ? -1 : page.content.findIndex((c) => c.id === contactId);
		const target = known >= 0 ? known : Math.min(fallbackIndex, page.content.length - 1);
		if (target < 0) return false;
		return grid.focusNow(target, COL_NAME);
	}

	/**
	 * Attempts the restore and reports back only once focus actually moved. A
	 * frame late on purpose: the confirm dialog that triggered the action
	 * restores focus to its own trigger on close, and that trigger left the DOM
	 * with the row it belonged to. The success signal matters because the list
	 * remounts on the way back from the contact form — an attempt made by an
	 * instance that is about to be torn down must leave the request standing
	 * for the instance that replaces it.
	 */
	function scheduleRowFocus(
		contactId: number | null,
		fallbackIndex: number,
		onApplied?: () => void
	): void {
		requestAnimationFrame(() => {
			void tick().then(() => {
				if (focusContactRowNow(contactId, fallbackIndex)) onApplied?.();
			});
		});
	}

	/*
	 * An action that removes rows (delete, bulk delete, merge) destroys the
	 * control that invoked it, so focus would drop to <body>. The target is
	 * captured before the mutation, while the rows are still on the page, and
	 * applied once the reloaded page arrives — the parent reloads in place, so
	 * this component survives and the reload is what re-runs the effect.
	 */
	let pendingFocus: { contactId: number | null; fallbackIndex: number } | null = null;

	$effect(() => {
		// Tracks the reload: the parent hands over a new page object each time.
		const content = page.content;
		const pending = pendingFocus;
		if (!pending) return;
		/*
		 * Deleting the last contact replaces the table with the empty-state
		 * message, so that message is where focus belongs — the button that
		 * triggered the delete went away with the row it sat in.
		 */
		if (content.length === 0) {
			pendingFocus = null;
			const target = emptyStateElement;
			if (target) requestAnimationFrame(() => target.focus());
			return;
		}
		scheduleRowFocus(pending.contactId, pending.fallbackIndex, () => (pendingFocus = null));
	});

	$effect(() => {
		const contactId = restoreFocusContactId;
		if (contactId == null) return;
		scheduleRowFocus(contactId, 0, () => onFocusRestored?.());
	});

	function handleRowKeydown(
		event: KeyboardEvent,
		contact: ContactResponse,
		rowIndex: number
	): void {
		if (event.key === 'Enter' || event.key === ' ') {
			// The checkbox and the action buttons own their own activation.
			if (grid.col >= COL_COMPOSE || grid.col === COL_SELECT) return;
			event.preventDefault();
			onEdit(contact.id);
			return;
		}
		grid.navigate(event, rowIndex, page.content.length);
	}

	$effect(() => {
		const nextSelectedIds = selectedIds.filter((id) => visibleIds.includes(id));
		if (nextSelectedIds.length !== selectedIds.length) selectedIds = nextSelectedIds;
	});

	/*
	 * The bulk toolbar (merge / delete / clear) appears with the first selected
	 * row, which a screen reader would miss — the conditional status span alone
	 * is a freshly inserted live region and is not announced reliably. Announce
	 * the availability once per selection through the persistent LiveAnnouncer
	 * (mirrors MessageList). Plain (non-reactive) flag to avoid an effect
	 * self-dependency.
	 */
	let bulkActionsAnnounced = false;
	$effect(() => {
		if (selectedVisibleIds.length > 0) {
			if (!bulkActionsAnnounced) {
				bulkActionsAnnounced = true;
				announcePolite($_('contacts.bulkActionsAvailable'));
			}
		} else {
			bulkActionsAnnounced = false;
		}
	});

	function contactLabel(c: ContactResponse): string {
		const fullName = [c.name, c.surname].filter(Boolean).join(' ');
		return fullName || c.emails[0]?.email || $_('contacts.noName');
	}

	function primaryEmail(c: ContactResponse): string | null {
		return c.emails.find((email) => email.primary)?.email ?? c.emails[0]?.email ?? null;
	}

	function labelSummary(c: ContactResponse): string {
		// Backend order (by case-folded name) is already the display order.
		return c.labels.length > 0 ? c.labels.map((l) => l.name).join(', ') : $_('contacts.labelsNone');
	}

	function avatarColor(c: ContactResponse): string {
		return avatarColorClass((c.name ?? '') + (c.surname ?? '') + (c.emails[0]?.email ?? c.id));
	}

	function avatarInitials(c: ContactResponse): string {
		const first = c.name?.trim()[0];
		const last = c.surname?.trim()[0];
		if (first && last) return (first + last).toUpperCase();
		if (first) return first.toUpperCase();
		if (last) return last.toUpperCase();
		const email = c.emails[0]?.email;
		if (email) return email.trim()[0]?.toUpperCase() ?? '?';
		return '?';
	}

	function handleCompose(c: ContactResponse): void {
		const email = primaryEmail(c);
		if (!email) return;
		void goto(`${resolve('/compose')}?to=${encodeURIComponent(email)}`);
	}

	function toggleSelected(id: number, checked: boolean) {
		bulkError = null;
		selectedIds = checked
			? selectedIds.includes(id)
				? selectedIds
				: [...selectedIds, id]
			: selectedIds.filter((selectedId) => selectedId !== id);
	}

	function toggleAllVisible(checked: boolean) {
		bulkError = null;
		selectedIds = checked ? visibleIds : [];
	}

	function clearSelection() {
		selectedIds = [];
		bulkError = null;
	}

	function handleRowClick(event: MouseEvent, contact: ContactResponse): void {
		const target = event.target as HTMLElement | null;
		if (target?.closest('input, button, a, label')) return;
		onEdit(contact.id);
	}

	async function handleDelete(c: ContactResponse) {
		const label = contactLabel(c);
		const confirmed = await confirmAction({
			title: $_('contacts.deleteConfirmTitle'),
			description: $_('contacts.deleteConfirm', { values: { label } }),
			confirmLabel: $_('common.delete'),
			cancelLabel: $_('common.cancel'),
			tone: 'destructive'
		});
		if (!confirmed) return;
		const index = page.content.findIndex((contact) => contact.id === c.id);
		const neighbour = page.content[index + 1]?.id ?? page.content[index - 1]?.id ?? null;
		try {
			await deleteContact(c.id);
			pushToast($_('contacts.deleteDone'), { tone: 'success' });
			pendingFocus = { contactId: neighbour, fallbackIndex: Math.max(0, index) };
			await onChanged();
		} catch (err) {
			pushToast(toErrorMessage(err), { tone: 'error' });
		}
	}

	async function handleBulkDelete() {
		const ids = [...selectedVisibleIds];
		if (ids.length === 0) return;
		const confirmed = await confirmAction({
			title: $_('contacts.bulkDeleteConfirmTitle'),
			description: $_('contacts.bulkDeleteConfirm', { values: { count: ids.length } }),
			confirmLabel: $_('common.delete'),
			cancelLabel: $_('common.cancel'),
			tone: 'destructive'
		});
		if (!confirmed) return;

		// The bulk toolbar disappears with the selection it acts on, so focus has
		// to move to the list — the row that ends up where the first deleted one
		// was.
		const firstIndex = Math.min(
			...ids.map((id) => page.content.findIndex((contact) => contact.id === id))
		);
		bulkBusy = true;
		bulkError = null;
		try {
			const result = await bulkDeleteContacts({ ids });
			pushToast(
				$_('contacts.bulkDeleteDone', {
					values: { deleted: result.deleted ?? 0, failed: result.failed ?? 0 }
				}),
				{ tone: (result.failed ?? 0) > 0 ? 'error' : 'success' }
			);
			selectedIds = [];
			pendingFocus = { contactId: null, fallbackIndex: Math.max(0, firstIndex) };
			await onChanged();
		} catch (err) {
			bulkError = toErrorMessage(err);
		} finally {
			bulkBusy = false;
		}
	}

	function handleSortSelectChange(event: Event) {
		pendingSortValue = (event.currentTarget as HTMLSelectElement).value as ContactSort;
	}

	function handleLabelSelectChange(event: Event) {
		pendingLabelValue = (event.currentTarget as HTMLSelectElement).value;
	}

	function applyFilters() {
		onFilterApply?.({
			sort: pendingSortValue === DEFAULT_SORT ? null : pendingSortValue,
			labelId: pendingLabelValue === '' ? null : Number(pendingLabelValue)
		});
	}
</script>

<div
	class="flex min-h-11 flex-wrap items-center gap-3 border-b border-border bg-muted/20 px-4 py-2 text-xs"
>
	<div class="flex min-w-0 items-center gap-1.5">
		<label class="text-muted-foreground" for="contacts-sort">{$_('contacts.sortLabel')}</label>
		<Select
			id="contacts-sort"
			value={pendingSortValue}
			onchange={handleSortSelectChange}
			size="sm"
			disabled={onFilterApply === undefined}
		>
			{#each SORT_OPTIONS as option (option.value)}
				<option value={option.value}>{$_(option.label)}</option>
			{/each}
		</Select>
	</div>
	<div class="flex min-w-0 items-center gap-1.5">
		<label class="text-muted-foreground" for="contacts-label-filter">
			{$_('contacts.labelFilterLabel')}
		</label>
		<Select
			id="contacts-label-filter"
			value={pendingLabelValue}
			onchange={handleLabelSelectChange}
			size="sm"
			disabled={onFilterApply === undefined}
		>
			<option value="">{$_('contacts.labelFilterAny')}</option>
			{#each labelOptions as option (option.id)}
				<option value={String(option.id)}>{option.name}</option>
			{/each}
		</Select>
		<Button
			type="button"
			onclick={applyFilters}
			size="sm"
			disabled={onFilterApply === undefined || !filtersDirty}
		>
			{$_('contacts.applyFilter')}
		</Button>
	</div>
</div>

{#if page.content.length === 0}
	<!-- Focus target after the last contact was deleted (see the pending-focus effect). -->
	<StateMessage bind:ref={emptyStateElement} padding="lg" role="status" tabindex={-1}>
		{activeLabelName
			? $_('contacts.emptyLabeled', { values: { label: activeLabelName } })
			: $_('contacts.empty')}
	</StateMessage>
{:else}
	<div
		class="flex min-h-11 flex-wrap items-center gap-2 border-b border-border bg-muted/20 px-4 py-2 text-xs"
	>
		<label class="inline-flex min-w-0 items-center gap-2 text-muted-foreground">
			<input
				bind:this={selectAllInput}
				type="checkbox"
				class={nativeControlClass}
				checked={allVisibleSelected}
				onchange={(event) => toggleAllVisible(event.currentTarget.checked)}
				disabled={bulkBusy}
			/>
			<span>{$_('contacts.selectAllLabel')}</span>
		</label>

		{#if selectedVisibleIds.length > 0}
			<span class="text-muted-foreground" role="status">
				{$_('contacts.selectedCount', { values: { count: selectedVisibleIds.length } })}
			</span>
			<Button type="button" onclick={clearSelection} variant="ghost" size="xs" disabled={bulkBusy}>
				{$_('contacts.clearSelection')}
			</Button>
			<Button
				type="button"
				onclick={openLabelDialog}
				variant="outline"
				size="xs"
				disabled={bulkBusy}
			>
				{$_('contacts.assignLabelsAction')}
			</Button>
			<Button
				type="button"
				onclick={openMergeDialog}
				variant="outline"
				size="xs"
				disabled={bulkBusy || selectedVisibleIds.length < 2}
			>
				{$_('contacts.mergeAction')}
			</Button>
			<Button
				type="button"
				onclick={handleBulkDelete}
				variant="destructive"
				size="xs"
				disabled={bulkBusy}
			>
				{bulkBusy ? $_('contacts.bulkDeleting') : $_('contacts.bulkDelete')}
			</Button>
		{/if}
	</div>

	<ContactLabelAssignDialog
		open={labelDialogOpen}
		contacts={selectedContacts}
		onOpenChange={(next) => (labelDialogOpen = next)}
		onAssigned={async () => {
			// The selection survives on purpose — assigning labels does not remove
			// rows, and the user may want to apply a second label to the same set.
			// Focus goes back to the first selected row, which the dialog took it from.
			pendingFocus = { contactId: selectedVisibleIds[0] ?? null, fallbackIndex: 0 };
			await onChanged();
		}}
	/>

	<ContactMergeDialog
		open={mergeDialogOpen}
		contacts={selectedContacts}
		onOpenChange={(next) => (mergeDialogOpen = next)}
		onMerged={async (targetId) => {
			selectedIds = [];
			// The merged-away rows are gone; the surviving target is where the
			// user's attention belongs.
			pendingFocus = { contactId: targetId, fallbackIndex: 0 };
			await onChanged();
		}}
	/>
	{#if bulkError}
		<div class="border-b border-border px-4 py-2">
			<StateMessage variant="error" padding="none" role="alert">{bulkError}</StateMessage>
		</div>
	{/if}
	<div class="overflow-x-auto">
		<!--
			role="grid" (not a plain table): the rows carry interactive controls,
			so the list is navigated with arrows under a roving tabindex rather
			than read cell by cell in browse mode. Native table markup keeps the
			row/column semantics; the roles are stated explicitly because a grid's
			children must not fall back to plain table cells.
		-->
		<table
			role="grid"
			aria-rowcount={page.totalElements + 1}
			class="min-w-[50rem] table-fixed border-collapse text-sm"
		>
			<caption class="sr-only">{$_('contacts.tableCaption')}</caption>
			<colgroup>
				<col class="w-11" />
				<col class="w-[26%]" />
				<col class="w-[30%]" />
				<col class="w-[13%]" />
				<col class="w-[20%]" />
				<col class="w-[11rem]" />
			</colgroup>
			<thead class="border-b border-border bg-muted/20 text-xs text-muted-foreground">
				<tr aria-rowindex={1}>
					<th role="columnheader" scope="col" class="px-3 py-2 text-left font-medium">
						<span class="sr-only">{$_('contacts.columnSelect')}</span>
					</th>
					<th role="columnheader" scope="col" class="px-3 py-2 text-left font-medium">
						{$_('contacts.columnName')}
					</th>
					<th role="columnheader" scope="col" class="px-3 py-2 text-left font-medium">
						{$_('contacts.columnEmail')}
					</th>
					<th role="columnheader" scope="col" class="px-3 py-2 text-left font-medium">
						{$_('contacts.columnLabels')}
					</th>
					<th role="columnheader" scope="col" class="px-3 py-2 text-left font-medium">
						{$_('contacts.columnNote')}
					</th>
					<th role="columnheader" scope="col" class="px-3 py-2 text-right font-medium">
						{$_('contacts.columnActions')}
					</th>
				</tr>
			</thead>
			<tbody bind:this={tableBodyElement} class="divide-y divide-border">
				{#each page.content as contact, rowIndex (contact.id)}
					{@const label = contactLabel(contact)}
					{@const composeTarget = primaryEmail(contact)}
					<tr
						data-row-index={rowIndex}
						data-contact-id={contact.id}
						aria-rowindex={page.page * page.size + rowIndex + 2}
						class="cursor-pointer transition-colors hover:bg-muted/40 focus-within:bg-muted/40"
						onclick={(event: MouseEvent) => handleRowClick(event, contact)}
						onkeydown={(event: KeyboardEvent) => handleRowKeydown(event, contact, rowIndex)}
					>
						<td role="gridcell" class="px-3 py-3 align-top">
							<input
								type="checkbox"
								{...grid.cell(rowIndex, COL_SELECT)}
								class={cn('mt-0.5', nativeControlClass)}
								checked={selectedIds.includes(contact.id)}
								onchange={(event) => toggleSelected(contact.id, event.currentTarget.checked)}
								disabled={bulkBusy}
								aria-label={$_('contacts.selectContact', {
									values: { label: contactLabel(contact) }
								})}
							/>
						</td>
						<th
							role="rowheader"
							scope="row"
							{...grid.cell(rowIndex, COL_NAME)}
							class={cn('px-3 py-3 text-left align-top font-normal', focusRingInset)}
						>
							<div class="flex min-w-0 items-center gap-2">
								<div
									aria-hidden="true"
									class={cn(
										'flex size-7 shrink-0 select-none items-center justify-center rounded-full text-caption font-semibold',
										avatarColor(contact)
									)}
								>
									{avatarInitials(contact)}
								</div>
								<span class="min-w-0 truncate font-medium text-foreground">
									{[contact.name, contact.surname].filter(Boolean).join(' ') ||
										$_('contacts.noName')}
								</span>
							</div>
						</th>
						<td
							role="gridcell"
							{...grid.cell(rowIndex, COL_EMAIL)}
							class={cn('px-3 py-3 align-top text-muted-foreground', focusRingInset)}
						>
							<ul class="m-0 list-none space-y-1 p-0">
								{#each contact.emails as email (email.id)}
									<li class="flex min-w-0 items-center gap-1.5">
										<Icon name="envelope" size={14} class="shrink-0 text-muted-foreground/80" />
										<span class="min-w-0 truncate">{email.email}</span>
									</li>
								{/each}
							</ul>
						</td>
						<td
							role="gridcell"
							{...grid.cell(rowIndex, COL_LABELS)}
							class={cn('px-3 py-3 align-top text-muted-foreground', focusRingInset)}
						>
							{labelSummary(contact)}
						</td>
						<td
							role="gridcell"
							{...grid.cell(rowIndex, COL_NOTE)}
							class={cn('px-3 py-3 align-top text-muted-foreground', focusRingInset)}
						>
							<p class="line-clamp-2">{contact.note ?? ''}</p>
						</td>
						<td role="gridcell" class="px-3 py-3 align-top">
							<div class="flex justify-end gap-1">
								<!--
									Always rendered, and aria-disabled rather than disabled when
									the contact has no address: a `disabled` button leaves the
									focus order, which would punch a hole in the roving cell
									sequence of that one row. Looking unavailable is the button's
									own job — `buttonVariants` styles `aria-disabled` like
									`disabled` precisely so this call site does not have to.
								-->
								<Button
									type="button"
									{...grid.cell(rowIndex, COL_COMPOSE)}
									onclick={() => handleCompose(contact)}
									variant="outline"
									size="xs"
									aria-disabled={composeTarget ? undefined : 'true'}
									aria-label={$_('contacts.composeContact', { values: { label } })}
								>
									{$_('contacts.compose')}
								</Button>
								<!--
									A link, not a button: the edit form is an addressable view of
									this screen (`?edit=<id>`), so Back closes it and a reload
									reopens it. It used to be a button that called `goto` — the
									address existed, the control just did not admit to it, which
									is what a screen reader then had to relay.

									The compose control above stays a button on purpose. The line
									is whether the control opens something that already exists (a
									link) or makes something new (a button) — composing starts a
									new draft, and an address that happens to implement it does
									not change that. Same reason the drafts list links to an
									existing draft but the new-message control does not.
								-->
								<Button
									href={editHref(contact.id)}
									{...grid.cell(rowIndex, COL_EDIT)}
									variant="outline"
									size="xs"
									aria-label={$_('contacts.editContact', { values: { label } })}
								>
									{$_('contacts.edit')}
								</Button>
								<Button
									type="button"
									{...grid.cell(rowIndex, COL_DELETE)}
									onclick={() => handleDelete(contact)}
									variant="destructive"
									size="xs"
									aria-label={$_('contacts.deleteContact', { values: { label } })}
								>
									{$_('contacts.delete')}
								</Button>
							</div>
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	<Pagination
		page={page.page}
		totalPages={page.totalPages}
		totalElements={page.totalElements}
		first={page.first}
		last={page.last}
		{onNavigate}
		landmarkLabel={$_('contacts.paginationLandmark')}
		variant="contacts"
	/>
{/if}
