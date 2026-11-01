<script lang="ts">
	/**
	 * The folder items of a "move to folder" menu — one per target, labelled by
	 * role or by the server's own name for USER folders (see mail/folderLabel).
	 * A subfolder sits in a group named after its parent and indented under it
	 * (see `moveMenuEntries`), so where it lives is announced, not only drawn.
	 *
	 * Rendered inside the caller's `MenuContent` (detail toolbar, both bulk
	 * bars) or `MenuSubContent` (row actions menu): the container and its
	 * trigger differ per surface, the items do not. bits-ui wires items
	 * to their menu through Svelte context, which reaches across this component
	 * boundary, and collects them for keyboard navigation and typeahead by
	 * querying the content element — so wrapping them in a component, or in
	 * groups, changes neither. Typing a folder's first letters jumps to it; that
	 * is what keeps this a menu instead of a filterable dialog.
	 */
	import { DropdownMenu } from 'bits-ui';
	import { menuItemVariants } from '#lib/components/ui/menu/index.js';
	import { folderLabel } from '#lib/mail/folderLabel.js';
	import { moveMenuEntries, type MoveMenuEntry } from '#lib/mail/moveTargets.js';
	import { folders } from '#lib/stores/folders.js';
	import { _ } from '#lib/i18n/index.js';
	import type { FolderResponse } from '#lib/types.js';

	interface Props {
		/** Usually `moveTargetsFor($folders, <current folder>)`. */
		targets: readonly FolderResponse[];
		onMoveTo: (folderRef: string) => Promise<unknown> | void;
	}

	let { targets, onMoveTo }: Props = $props();

	// The hierarchy is read from the whole list `targets` was taken from: the
	// current folder is not a target but still names its subfolders' group.
	const menuEntries = $derived(moveMenuEntries($folders, targets));
</script>

{#snippet rows(level: MoveMenuEntry[])}
	{#each level as entry (`${entry.kind}:${entry.kind === 'item' ? entry.folder.folderRef : entry.parent.folderRef}`)}
		{#if entry.kind === 'item'}
			{@const label = folderLabel(entry.folder, $_)}
			<DropdownMenu.Item
				class={menuItemVariants()}
				title={label}
				onSelect={() => onMoveTo(entry.folder.folderRef)}
			>
				<span class="truncate">{label}</span>
			</DropdownMenu.Item>
		{:else}
			<DropdownMenu.Group aria-label={folderLabel(entry.parent, $_)} class="ps-4">
				{@render rows(entry.entries)}
			</DropdownMenu.Group>
		{/if}
	{/each}
{/snippet}

{@render rows(menuEntries)}
