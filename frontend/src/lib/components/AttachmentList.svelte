<script lang="ts">
	import Icon from '$lib/components/Icon.svelte';
	import { chipVariants } from '$lib/components/ui/chip/index.js';
	import { focusRing } from '$lib/components/ui/focus-ring/index.js';
	import { formatSize } from '$lib/formatters.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { cn } from '$lib/utils.js';
	import type { AttachmentResponse } from '$lib/types.js';

	interface Props {
		attachments: AttachmentResponse[];
		onDownload: (attachment: AttachmentResponse) => void;
	}

	let { attachments, onDownload }: Props = $props();
</script>

{#if attachments.length > 0}
	<div class="border-b border-border px-4 py-2">
		<p class="text-xs font-medium text-muted-foreground">
			{$_('detail.attachmentsHeading')}
		</p>
		<ul
			class="mt-1 flex flex-wrap gap-2"
			role="list"
			aria-label={$_('detail.attachmentsListLabel')}
		>
			{#each attachments as attachment (attachment.partPath)}
				<li>
					<button
						type="button"
						onclick={() => onDownload(attachment)}
						class={cn(chipVariants(), 'hover:bg-secondary/80', focusRing)}
					>
						<Icon name="paperclip" size={14} />
						<span>{attachment.fileName}</span>
						<span class="text-muted-foreground">({formatSize(attachment.size, $appLocale)})</span>
					</button>
				</li>
			{/each}
		</ul>
	</div>
{/if}
