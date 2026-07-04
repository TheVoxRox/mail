<script lang="ts">
	import { resolve } from '$app/paths';
	import { page } from '$app/stores';
	import Icon from '$lib/components/Icon.svelte';
	import { SidebarNavItem } from '$lib/components/ui/sidebar-nav-item/index.js';
	import { SidebarSection } from '$lib/components/ui/sidebar-section/index.js';
	import { SidebarShell } from '$lib/components/ui/sidebar-shell/index.js';
	import { _ } from '$lib/i18n/index.js';

	type SettingsItem = {
		id: 'appearance' | 'language' | 'shortcuts' | 'accounts' | 'about';
		href: string;
		labelKey: string;
		icon: 'swatch' | 'language' | 'keyboard' | 'user-circle' | 'information-circle';
	};

	type SettingsGroup = {
		id: 'primary' | 'reference';
		labelKey: string;
		items: SettingsItem[];
	};

	const primaryItems: SettingsItem[] = [
		{
			id: 'appearance',
			href: resolve('/settings/appearance'),
			labelKey: 'settings.nav.appearance',
			icon: 'swatch'
		},
		{
			id: 'language',
			href: resolve('/settings/language'),
			labelKey: 'settings.nav.language',
			icon: 'language'
		},
		{
			id: 'accounts',
			href: resolve('/settings/accounts'),
			labelKey: 'settings.nav.accounts',
			icon: 'user-circle'
		}
	];

	const referenceItems: SettingsItem[] = [
		{
			id: 'shortcuts',
			href: resolve('/settings/shortcuts'),
			labelKey: 'settings.nav.shortcuts',
			icon: 'keyboard'
		},
		{
			id: 'about',
			href: resolve('/settings/about'),
			labelKey: 'settings.nav.about',
			icon: 'information-circle'
		}
	];

	const groups: SettingsGroup[] = [
		{
			id: 'primary',
			labelKey: 'settings.navGroups.primary',
			items: primaryItems
		},
		{
			id: 'reference',
			labelKey: 'settings.navGroups.reference',
			items: referenceItems
		}
	];

	function isActive(item: SettingsItem): boolean {
		const pathname = $page.url.pathname;
		return pathname === `/settings/${item.id}` || pathname.startsWith(`/settings/${item.id}/`);
	}
</script>

{#snippet header()}
	<h2 class="text-base font-semibold">{$_('workspace.settings')}</h2>
{/snippet}

<SidebarShell
	label={$_('workspace.settingsSidebarLabel')}
	{header}
	headerClass="px-4 py-4"
	contentClass="p-2.5"
>
	<div class="space-y-4">
		{#each groups as group (group.id)}
			<SidebarSection id={`settings-group-${group.id}`} label={$_(group.labelKey)} landmark={false}>
				<ul role="list" class="space-y-1">
					{#each group.items as item (item.id)}
						{@const active = isActive(item)}
						<li>
							<SidebarNavItem href={item.href} {active}>
								{#snippet icon()}
									<Icon name={item.icon} />
								{/snippet}

								{$_(item.labelKey)}
							</SidebarNavItem>
						</li>
					{/each}
				</ul>
			</SidebarSection>
		{/each}
	</div>
</SidebarShell>
