<script lang="ts">
	import { page } from '$app/stores';
	import { tick } from 'svelte';
	import { Command as BitsCommand } from 'bits-ui';
	import Icon from '$lib/components/Icon.svelte';
	import { DialogDescription, DialogShell, DialogTitle } from '$lib/components/ui/dialog/index.js';
	import { commands, type Command } from '$lib/stores/commands.js';
	import { closePalette, paletteOpen } from '$lib/stores/palette.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { _ } from '$lib/i18n/index.js';
	import {
		matchesAllTokens,
		normalizeText,
		sortByRelevance,
		tokenizeQuery,
		type VisibleCommand
	} from '$lib/commands/paletteRanking.js';
	import type { CommandGroup } from '$lib/commands/shared.js';
	import { inputVariants } from '$lib/components/ui/input/index.js';
	import { menuItemVariants } from '$lib/components/ui/menu/index.js';
	import { cn } from '$lib/utils.js';

	type PositionedCommand = VisibleCommand & { position: number };

	type CommandGroupView = {
		groupKey: CommandGroup;
		groupLabel: string;
		entries: PositionedCommand[];
	};

	let query = $state('');
	let selectedCommandId = $state('');
	let inputElement = $state<HTMLInputElement | null>(null);
	let executionError = $state('');

	function commandOptionId(command: Command): string {
		return `palette-option-${command.id.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
	}

	const visibleCommands = $derived.by<VisibleCommand[]>(() =>
		$commands.map((command, baseIndex) => {
			const title = $_(
				command.titleKey,
				command.titleValues ? { values: command.titleValues } : undefined
			);
			const groupLabel = $_(`palette.group_${command.groupKey}`);
			const keywords = command.keywords ?? [];
			const searchText = normalizeText([title, groupLabel, ...keywords].join(' '));
			return { command, title, groupLabel, searchText, baseIndex };
		})
	);

	const filteredCommands = $derived.by<VisibleCommand[]>(() => {
		const normalizedQuery = normalizeText(query.trim());
		const pathname = $page.url.pathname;
		if (!normalizedQuery) return sortByRelevance(visibleCommands, normalizedQuery, pathname);

		const tokens = tokenizeQuery(normalizedQuery);
		return sortByRelevance(
			visibleCommands.filter((entry) => matchesAllTokens(entry, tokens)),
			normalizedQuery,
			pathname
		);
	});

	const groupedCommands = $derived.by<CommandGroupView[]>(() => {
		const groups: CommandGroupView[] = [];

		filteredCommands.forEach((entry, index) => {
			const positioned = { ...entry, position: index + 1 };
			const lastGroup = groups[groups.length - 1];
			if (lastGroup?.groupKey === entry.command.groupKey) {
				lastGroup.entries.push(positioned);
			} else {
				groups.push({
					groupKey: entry.command.groupKey,
					groupLabel: entry.groupLabel,
					entries: [positioned]
				});
			}
		});

		return groups;
	});

	const hasQuery = $derived(query.trim().length > 0);
	const resultsAnnouncement = $derived.by(() => {
		if (filteredCommands.length === 0) {
			return $_('palette.noResultsLive');
		}
		return $_('palette.resultsLive', { values: { count: filteredCommands.length } });
	});

	$effect(() => {
		if (!$paletteOpen) {
			query = '';
			selectedCommandId = '';
			executionError = '';
			return;
		}

		void tick().then(() => inputElement?.focus());
	});

	$effect(() => {
		if (!$paletteOpen) return;
		const hasSelectedCommand = filteredCommands.some(
			(entry) => entry.command.id === selectedCommandId
		);
		if (!hasSelectedCommand) {
			selectedCommandId = filteredCommands[0]?.command.id ?? '';
		}
	});

	function moveSelection(delta: number): void {
		if (filteredCommands.length === 0) {
			selectedCommandId = '';
			return;
		}
		const currentIndex = filteredCommands.findIndex(
			(entry) => entry.command.id === selectedCommandId
		);
		const nextIndex =
			currentIndex === -1
				? delta > 0
					? 0
					: filteredCommands.length - 1
				: (currentIndex + delta + filteredCommands.length) % filteredCommands.length;
		selectedCommandId = filteredCommands[nextIndex].command.id;
	}

	function handleInputKeydown(event: KeyboardEvent): void {
		if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
		event.preventDefault();
		event.stopPropagation();
		moveSelection(event.key === 'ArrowDown' ? 1 : -1);
	}

	async function execute(command: Command): Promise<void> {
		executionError = '';
		try {
			await command.run();
			closePalette({ restoreFocus: command.restoreFocus ?? false });
		} catch (err) {
			const detail = toErrorMessage(err);
			const message = $_('palette.commandFailed', { values: { message: detail } });
			executionError = message;
			pushToast(message, { tone: 'error' });
			await tick();
			inputElement?.focus();
		}
	}

	function handleOpenChange(open: boolean): void {
		if (!open) closePalette();
	}
</script>

<DialogShell
	open={$paletteOpen}
	onOpenChange={handleOpenChange}
	size="2xl"
	placement="top"
	padding="none"
	class="overflow-hidden"
>
	<BitsCommand.Root
		label={$_('palette.title')}
		bind:value={selectedCommandId}
		shouldFilter={false}
		loop
		vimBindings={false}
	>
		<div class="border-b border-border px-4 py-3">
			<DialogTitle id="palette-title">
				{$_('palette.title')}
			</DialogTitle>
			<!--
				The id stays explicit here, unlike in the other dialogs: the search
				field aims its own aria-describedby at the same hint, so the text has
				to be addressable by something other than the dialog itself.
			-->
			<DialogDescription id="palette-hint" class="mt-1 text-xs">
				{$_('palette.hint')}
			</DialogDescription>
		</div>

		<div class="border-b border-border px-4 py-3">
			<BitsCommand.Input
				id="command-palette-input"
				bind:ref={inputElement}
				bind:value={query}
				oninput={() => (executionError = '')}
				onkeydown={handleInputKeydown}
				aria-describedby="palette-hint"
				autofocus
				placeholder={$_('palette.placeholder')}
				class={cn(inputVariants(), 'px-3 py-2')}
			/>
			<div aria-live="polite" class="sr-only">{resultsAnnouncement}</div>
			{#if executionError}
				<p role="alert" class="mt-2 text-sm text-destructive-foreground">
					{executionError}
				</p>
			{/if}
		</div>

		<BitsCommand.List
			aria-label={$_('palette.resultsLabel')}
			tabindex={0}
			class="max-h-[26rem] overflow-y-auto p-2"
		>
			<BitsCommand.Viewport id="palette-listbox">
				{#if filteredCommands.length === 0}
					<BitsCommand.Empty class="px-2 py-4 text-sm text-muted-foreground">
						{$_('palette.noResults')}
					</BitsCommand.Empty>
				{:else}
					{#each groupedCommands as group (group.groupKey)}
						<BitsCommand.Group value={group.groupKey}>
							<BitsCommand.GroupHeading
								class="px-2 pb-1 pt-3 text-caption font-semibold uppercase tracking-[0.16em] text-muted-foreground first:pt-0"
							>
								{group.groupLabel}
							</BitsCommand.GroupHeading>
							<BitsCommand.GroupItems>
								{#each group.entries as entry (entry.command.id)}
									<BitsCommand.Item
										id={commandOptionId(entry.command)}
										value={entry.command.id}
										keywords={entry.command.keywords ?? []}
										aria-posinset={entry.position}
										aria-setsize={filteredCommands.length}
										onSelect={() => void execute(entry.command)}
										class={cn(
											menuItemVariants(),
											'items-center gap-3 hover:bg-muted data-[selected]:bg-muted'
										)}
									>
										{#if entry.command.icon}
											<span
												class="flex h-9 w-9 items-center justify-center rounded-lg border border-border bg-background"
											>
												<Icon name={entry.command.icon} size={16} />
											</span>
										{/if}
										<span class="min-w-0 flex-1">
											<span class="block truncate text-sm font-medium">{entry.title}</span>
											{#if hasQuery && entry.command.keywords && entry.command.keywords.length > 0}
												<span class="block truncate text-xs text-muted-foreground">
													{entry.command.keywords.slice(0, 3).join(', ')}
												</span>
											{/if}
										</span>
										{#if entry.command.shortcut}
											<span
												class="rounded-md border border-border px-2 py-1 text-caption text-muted-foreground"
											>
												{entry.command.shortcut}
											</span>
										{/if}
									</BitsCommand.Item>
								{/each}
							</BitsCommand.GroupItems>
						</BitsCommand.Group>
					{/each}
				{/if}
			</BitsCommand.Viewport>
		</BitsCommand.List>
	</BitsCommand.Root>
</DialogShell>
