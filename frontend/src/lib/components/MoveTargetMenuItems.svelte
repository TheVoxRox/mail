<script lang="ts">
	/**
	 * The folder items of a "move to folder" menu — one per target, labelled by
	 * role or by the server's own name for USER folders (see mail/folderLabel).
	 *
	 * Rendered inside the caller's `DropdownMenu.Content` (detail toolbar, both
	 * bulk bars) or `DropdownMenu.SubContent` (row actions menu): the container
	 * and its trigger differ per surface, the items do not. bits-ui wires items
	 * to their menu through Svelte context, which reaches across this component
	 * boundary, and collects them for keyboard navigation and typeahead by
	 * querying the content element — so wrapping them in a component changes
	 * neither. Typing a folder's first letters jumps to it; that is what keeps
	 * this a menu instead of a filterable dialog.
	 */
	import { DropdownMenu } from 'bits-ui';
	import { menuItemVariants } from '$lib/components/ui/menu/index.js';
	import { folderLabel } from '$lib/mail/folderLabel.js';
	import { _ } from '$lib/i18n/index.js';
	import type { FolderResponse } from '$lib/types.js';

	interface Props {
		/** Usually `moveTargetsFor($folders, <current folder>)`. */
		targets: readonly FolderResponse[];
		onMoveTo: (folderRef: string) => Promise<unknown> | void;
	}

	let { targets, onMoveTo }: Props = $props();
</script>

{#each targets as folder (folder.folderRef)}
	{@const label = folderLabel(folder, $_)}
	<DropdownMenu.Item
		class={menuItemVariants()}
		title={label}
		onSelect={() => onMoveTo(folder.folderRef)}
	>
		<span class="truncate">{label}</span>
	</DropdownMenu.Item>
{/each}
