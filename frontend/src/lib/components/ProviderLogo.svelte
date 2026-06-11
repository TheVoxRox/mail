<script lang="ts" module>
	import type { ProviderPreset } from '$lib/accounts/providerPresets.js';

	export type ProviderLogoKey = ProviderPreset['key'];

	const LOGO_TEXT: Record<ProviderLogoKey, string> = {
		gmail: 'G',
		outlook: 'O',
		seznam: 'S',
		custom: '@'
	};

	const LOGO_CLASSES: Record<ProviderLogoKey, string> = {
		gmail: 'border-[#d93025]/35 bg-white text-[#d93025]',
		outlook: 'border-[#0f6cbd]/35 bg-[#0f6cbd] text-white',
		seznam: 'border-[#cc1f1a]/35 bg-[#cc1f1a] text-white',
		custom: 'border-border bg-muted text-muted-foreground'
	};
</script>

<script lang="ts">
	import { cn } from '$lib/utils.js';

	interface Props {
		keyName?: ProviderLogoKey | null;
		size?: 'sm' | 'md';
	}

	let { keyName = 'custom', size = 'md' }: Props = $props();

	const logoKey = $derived(keyName ?? 'custom');
	const sizeClasses = $derived(size === 'sm' ? 'size-6 text-xs' : 'size-9 text-sm');
</script>

<span
	aria-hidden="true"
	class={cn(
		'inline-grid shrink-0 place-items-center rounded-md border font-semibold shadow-sm',
		sizeClasses,
		LOGO_CLASSES[logoKey]
	)}
>
	{LOGO_TEXT[logoKey]}
</span>
