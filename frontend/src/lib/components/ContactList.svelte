<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { bulkDeleteContacts, deleteContact } from '$lib/api/contacts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { _ } from '$lib/i18n/index.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { announcePolite, pushToast } from '$lib/stores/toasts.js';
	import ContactMergeDialog from '$lib/components/ContactMergeDialog.svelte';
	import Icon from '$lib/components/Icon.svelte';
	import Pagination from '$lib/components/Pagination.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import {
		computeNextCell,
		focusGridCell,
		ROW_NAV_PAGE_STEP
	} from '$lib/components/grid/rowNavigation.js';
	import type { ContactResponse, EmailLabel, PagedResponse } from '$lib/types.js';
	import type { ContactSort } from '$lib/api/contacts.js';
	import { cn } from '$lib/utils.js';
	import { tick } from 'svelte';

	interface Props {
		accountId: number;
		page: PagedResponse<ContactResponse>;
		sort?: ContactSort | null;
		label?: EmailLabel | null;
		onChanged: () => void | Promise<void>;
		onEdit: (id: number) => void;
		onFilterApply?: (filters: { sort: ContactSort | null; label: EmailLabel | null }) => void;
		onPrev: () => void;
		onNext: () => void;
		onFirst: () => void;
		onLast: () => void;
		onJump: (page: number) => void;
	}

	let {
		accountId,
		page,
		sort = null,
		label = null,
		onChanged,
		onEdit,
		onFilterApply,
		onPrev,
		onNext,
		onFirst,
		onLast,
		onJump
	}: Props = $props();

	const DEFAULT_SORT: ContactSort = 'surname';
	const LABEL_FILTER_OPTIONS = [
		{ value: '', label: 'contacts.labelFilterAny' },
		{ value: 'HOME', label: 'contacts.labelOptions.HOME' },
		{ value: 'WORK', label: 'contacts.labelOptions.WORK' },
		{ value: 'OTHER', label: 'contacts.labelOptions.OTHER' }
	] as const;
	const SORT_OPTIONS = [
		{ value: 'surname', label: 'contacts.sortOptions.surname' },
		{ value: 'name', label: 'contacts.sortOptions.name' },
		{ value: 'recent', label: 'contacts.sortOptions.recent' }
	] as const satisfies ReadonlyArray<{ value: ContactSort; label: string }>;
	const sortValue = $derived<ContactSort>(sort ?? DEFAULT_SORT);
	let pendingSortValue = $state<ContactSort>(DEFAULT_SORT);
	const sortFilterDirty = $derived(pendingSortValue !== sortValue);
	const labelValue = $derived<EmailLabel | ''>(label ?? '');
	let pendingLabelValue = $state<EmailLabel | ''>('');
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
	const selectedContacts = $derived(
		page.content.filter((contact) => selectedIds.includes(contact.id))
	);

	function openMergeDialog(): void {
		if (selectedVisibleIds.length < 2) return;
		mergeDialogOpen = true;
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
	let focusedRowIndex = $state(0);
	let focusedCol = $state(COL_NAME);

	$effect(() => {
		if (selectAllInput) selectAllInput.indeterminate = someVisibleSelected;
	});

	$effect(() => {
		pendingSortValue = sortValue;
		pendingLabelValue = labelValue;
	});

	// Keep the roving row inside the page when the list shrinks (delete, filter).
	$effect(() => {
		const max = page.content.length - 1;
		if (max < 0) {
			if (focusedRowIndex !== 0) focusedRowIndex = 0;
			return;
		}
		if (focusedRowIndex > max) focusedRowIndex = max;
	});

	function setFocus(rowIndex: number, col: number): void {
		focusedRowIndex = rowIndex;
		focusedCol = col;
		void tick().then(() => focusGridCell(tableBodyElement, rowIndex, col));
	}

	function handleCellFocus(rowIndex: number, col: number): void {
		focusedRowIndex = rowIndex;
		focusedCol = col;
	}

	function handleRowKeydown(
		event: KeyboardEvent,
		contact: ContactResponse,
		rowIndex: number
	): void {
		if (event.key === 'Enter' || event.key === ' ') {
			// The checkbox and the action buttons own their own activation.
			if (focusedCol >= COL_COMPOSE || focusedCol === COL_SELECT) return;
			event.preventDefault();
			onEdit(contact.id);
			return;
		}
		const next = computeNextCell(event.key, {
			row: rowIndex,
			col: focusedCol,
			maxRow: page.content.length - 1,
			maxCol: MAX_COL,
			ctrl: event.ctrlKey,
			pageStep: ROW_NAV_PAGE_STEP
		});
		if (!next) return;
		event.preventDefault();
		setFocus(next.row, next.col);
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

	function emailLabelSummary(c: ContactResponse): string {
		const labels = c.emails
			.map((email) => email.label)
			.filter((value): value is EmailLabel => value != null);
		// Canonical order (sidebar / filter / form), not the contact's email order.
		const unique = [...new Set(labels)].sort(
			(a, b) =>
				LABEL_FILTER_OPTIONS.findIndex((option) => option.value === a) -
				LABEL_FILTER_OPTIONS.findIndex((option) => option.value === b)
		);
		return unique.length > 0
			? unique.map((value) => $_(`contacts.labelOptions.${value}`)).join(', ')
			: $_('contacts.labelOptions.none');
	}

	const AVATAR_PALETTE = [
		'bg-rose-200 text-rose-900',
		'bg-amber-200 text-amber-900',
		'bg-emerald-200 text-emerald-900',
		'bg-sky-200 text-sky-900',
		'bg-violet-200 text-violet-900',
		'bg-pink-200 text-pink-900',
		'bg-teal-200 text-teal-900',
		'bg-indigo-200 text-indigo-900'
	];

	function hashString(value: string): number {
		let hash = 0;
		for (let i = 0; i < value.length; i++) {
			hash = (hash << 5) - hash + value.charCodeAt(i);
			hash |= 0;
		}
		return Math.abs(hash);
	}

	function avatarColor(c: ContactResponse): string {
		const seed = (c.name ?? '') + (c.surname ?? '') + (c.emails[0]?.email ?? c.id);
		return AVATAR_PALETTE[hashString(seed) % AVATAR_PALETTE.length];
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
		try {
			await deleteContact(accountId, c.id);
			pushToast($_('contacts.deleteDone'), { tone: 'success' });
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

		bulkBusy = true;
		bulkError = null;
		try {
			const result = await bulkDeleteContacts(accountId, { ids });
			pushToast(
				$_('contacts.bulkDeleteDone', {
					values: { deleted: result.deleted ?? 0, failed: result.failed ?? 0 }
				}),
				{ tone: (result.failed ?? 0) > 0 ? 'error' : 'success' }
			);
			selectedIds = [];
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
		pendingLabelValue = (event.currentTarget as HTMLSelectElement).value as EmailLabel | '';
	}

	function applyFilters() {
		onFilterApply?.({
			sort: pendingSortValue === DEFAULT_SORT ? null : pendingSortValue,
			label: pendingLabelValue === '' ? null : pendingLabelValue
		});
	}
</script>

<div
	class="flex min-h-11 flex-wrap items-center gap-3 border-b border-border bg-muted/15 px-4 py-2 text-xs"
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
			{#each LABEL_FILTER_OPTIONS as option (option.value)}
				<option value={option.value}>{$_(option.label)}</option>
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
	<StateMessage padding="lg">
		{label
			? $_('contacts.emptyLabeled', {
					values: { label: $_(`contacts.labelOptions.${label}`) }
				})
			: $_('contacts.empty')}
	</StateMessage>
{:else}
	<div
		class="flex min-h-11 flex-wrap items-center gap-2 border-b border-border bg-muted/25 px-4 py-2 text-xs"
	>
		<label class="inline-flex min-w-0 items-center gap-2 text-muted-foreground">
			<input
				bind:this={selectAllInput}
				type="checkbox"
				class="size-4 rounded border-input bg-background text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
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

	<ContactMergeDialog
		open={mergeDialogOpen}
		{accountId}
		contacts={selectedContacts}
		onOpenChange={(next) => (mergeDialogOpen = next)}
		onMerged={async () => {
			selectedIds = [];
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
					{@const cellTabindex = (col: number) =>
						focusedRowIndex === rowIndex && focusedCol === col ? 0 : -1}
					<tr
						data-row-index={rowIndex}
						data-contact-id={contact.id}
						aria-rowindex={page.page * page.size + rowIndex + 2}
						class="cursor-pointer transition-colors hover:bg-muted/35 focus-within:bg-muted/35"
						onclick={(event: MouseEvent) => handleRowClick(event, contact)}
						onkeydown={(event: KeyboardEvent) => handleRowKeydown(event, contact, rowIndex)}
					>
						<td role="gridcell" class="px-3 py-3 align-top">
							<input
								type="checkbox"
								data-cell-target
								data-col={COL_SELECT}
								tabindex={cellTabindex(COL_SELECT)}
								onfocus={() => handleCellFocus(rowIndex, COL_SELECT)}
								class="mt-0.5 size-4 rounded border-input bg-background text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
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
							data-cell-target
							data-col={COL_NAME}
							tabindex={cellTabindex(COL_NAME)}
							onfocus={() => handleCellFocus(rowIndex, COL_NAME)}
							class="px-3 py-3 text-left align-top font-normal outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
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
							data-cell-target
							data-col={COL_EMAIL}
							tabindex={cellTabindex(COL_EMAIL)}
							onfocus={() => handleCellFocus(rowIndex, COL_EMAIL)}
							class="px-3 py-3 align-top text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
						>
							<ul class="m-0 list-none space-y-1 p-0">
								{#each contact.emails as email (email.id)}
									<li class="flex min-w-0 items-center gap-1.5">
										<Icon name="envelope" size={13} class="shrink-0 text-muted-foreground/80" />
										<span class="min-w-0 truncate">{email.email}</span>
									</li>
								{/each}
							</ul>
						</td>
						<td
							role="gridcell"
							data-cell-target
							data-col={COL_LABELS}
							tabindex={cellTabindex(COL_LABELS)}
							onfocus={() => handleCellFocus(rowIndex, COL_LABELS)}
							class="px-3 py-3 align-top text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
						>
							{emailLabelSummary(contact)}
						</td>
						<td
							role="gridcell"
							data-cell-target
							data-col={COL_NOTE}
							tabindex={cellTabindex(COL_NOTE)}
							onfocus={() => handleCellFocus(rowIndex, COL_NOTE)}
							class="px-3 py-3 align-top text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
						>
							<p class="line-clamp-2">{contact.note ?? ''}</p>
						</td>
						<td role="gridcell" class="px-3 py-3 align-top">
							<div class="flex justify-end gap-1">
								<!--
									Always rendered, and aria-disabled rather than disabled when
									the contact has no address: a `disabled` button leaves the
									focus order, which would punch a hole in the roving cell
									sequence of that one row.
								-->
								<Button
									type="button"
									data-cell-target
									data-col={COL_COMPOSE}
									tabindex={cellTabindex(COL_COMPOSE)}
									onfocus={() => handleCellFocus(rowIndex, COL_COMPOSE)}
									onclick={() => handleCompose(contact)}
									variant="outline"
									size="xs"
									aria-disabled={composeTarget ? undefined : 'true'}
									aria-label={$_('contacts.composeContact', { values: { label } })}
								>
									{$_('contacts.compose')}
								</Button>
								<Button
									type="button"
									data-cell-target
									data-col={COL_EDIT}
									tabindex={cellTabindex(COL_EDIT)}
									onfocus={() => handleCellFocus(rowIndex, COL_EDIT)}
									onclick={() => onEdit(contact.id)}
									variant="outline"
									size="xs"
									aria-label={$_('contacts.editContact', { values: { label } })}
								>
									{$_('contacts.edit')}
								</Button>
								<Button
									type="button"
									data-cell-target
									data-col={COL_DELETE}
									tabindex={cellTabindex(COL_DELETE)}
									onfocus={() => handleCellFocus(rowIndex, COL_DELETE)}
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
		{onPrev}
		{onNext}
		{onFirst}
		{onLast}
		{onJump}
		landmarkLabel={$_('contacts.paginationLandmark')}
		pageInfoKey="contacts.pageInfo"
		totalCountKey="contacts.totalCount"
		prevLabelKey="contacts.prev"
		nextLabelKey="contacts.next"
		firstLabelKey="contacts.firstPage"
		lastLabelKey="contacts.lastPage"
		jumpLabelKey="contacts.jumpLabel"
		jumpButtonKey="contacts.jumpButton"
	/>
{/if}
