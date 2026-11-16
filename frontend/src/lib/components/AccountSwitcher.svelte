<script lang="ts">
	/**
	 * The sidebar's account switcher: a menu button whose items are the
	 * accounts, the active one checked.
	 *
	 * It was a native `<select>` that switched on `change`, and switching is a
	 * change of context — the app leaves for the other account's mailbox and
	 * focus lands on its heading. A closed select changes its value without
	 * being opened: End, Home, PageUp, PageDown, ArrowUp and every typed letter
	 * did it, so a keyboard or screen reader user exploring the options left the
	 * mailbox on the first key (WCAG 3.2.2, failure F37). The Select primitive
	 * only ever turned ArrowDown into opening the list.
	 *
	 * In a menu every key but Enter and Space only moves the highlight —
	 * typeahead included — so the account changes on a deliberate choice and on
	 * nothing else. The items are `menuitemradio`, so the active account is
	 * announced as checked, as the selected option of the select was.
	 */
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { DropdownMenu } from 'bits-ui';
	import { accountsState, activeAccount, setActiveAccount } from '#lib/stores/accounts.js';
	import { accountOptionLabel } from '#lib/accounts/accountLabel.js';
	import Icon from '#lib/components/Icon.svelte';
	import { MenuContent, menuItemVariants } from '#lib/components/ui/menu/index.js';
	import { selectVariants } from '#lib/components/ui/select/index.js';
	import { _ } from '#lib/i18n/index.js';
	import { cn } from '#lib/utils.js';

	const accounts = $derived($accountsState.status === 'ready' ? $accountsState.accounts : []);
	const current = $derived($activeAccount);
	const currentValue = $derived(current ? String(current.id) : '');
	const currentLabel = $derived(
		current ? accountOptionLabel(current.accountName, current.email) : null
	);

	async function selectAccount(value: string): Promise<void> {
		const id = Number(value);
		if (!Number.isFinite(id) || id === current?.id) return;
		setActiveAccount(id);
		await goto(resolve('/'), { replace: true });
	}
</script>

{#if accounts.length > 0}
	<DropdownMenu.Root>
		<!--
			Dressed as the select it replaces — the frame, the ring and the size come
			from `selectVariants` — because it still reads as "one of several,
			pick another" to the eye. The name puts what the control is in front of
			the account it shows; the visible text stays whole inside it (2.5.3).
		-->
		<DropdownMenu.Trigger
			class={cn(
				selectVariants({ size: 'sm', width: 'full' }),
				'relative flex h-auto items-center py-1.5 pl-7 pr-7 text-left hover:bg-muted data-[state=open]:bg-muted'
			)}
			aria-label={currentLabel
				? $_('nav.activeAccount', { values: { account: currentLabel } })
				: undefined}
		>
			<Icon
				name="user-circle"
				size={14}
				class="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground"
			/>
			<span class="truncate">{currentLabel ?? $_('nav.switchAccount')}</span>
			<Icon
				name="chevron-down"
				size={14}
				class="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground"
			/>
		</DropdownMenu.Trigger>
		<MenuContent label={$_('nav.switchAccountLabel')} width="md">
			<DropdownMenu.RadioGroup value={currentValue} onValueChange={selectAccount}>
				{#each accounts as account (account.id)}
					<DropdownMenu.RadioItem
						value={String(account.id)}
						class={cn(menuItemVariants(), 'items-center gap-2')}
					>
						{#snippet children({ checked })}
							<!-- The check is the state for the eye; aria-checked carries it for the ear. -->
							<span class="flex size-4 shrink-0 items-center justify-center" aria-hidden="true">
								{#if checked}
									<Icon name="check" size={14} />
								{/if}
							</span>
							<span class="min-w-0 break-words">
								{accountOptionLabel(account.accountName, account.email)}
							</span>
						{/snippet}
					</DropdownMenu.RadioItem>
				{/each}
			</DropdownMenu.RadioGroup>
		</MenuContent>
	</DropdownMenu.Root>
{/if}
