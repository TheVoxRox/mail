<script lang="ts">
	/**
	 * One IMAP/SMTP endpoint group (host + port + "use SSL") for the custom
	 * server form. Rendered twice by {@link CustomServerFields} — the only
	 * differences between the IMAP and SMTP blocks are the id prefix, legend
	 * and host placeholder, so they share this component.
	 *
	 * The ids stay `{idPrefix}-host` / `{idPrefix}-port` (e.g. `acc-imap-host`)
	 * because `FIELD_INPUT_IDS` in accountFormValidation.ts focuses them by id
	 * after validation, and the e2e suite locates them the same way.
	 */
	import { Field } from '$lib/components/ui/field/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { nativeControlClass } from '$lib/components/ui/native-control/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { cn } from '$lib/utils.js';

	interface Props {
		/** `acc-imap` or `acc-smtp` — drives the field ids. */
		idPrefix: string;
		heading: string;
		placeholder: string;
		host: string;
		port: number | null;
		useSsl: boolean;
		hostError?: string | null;
		portError?: string | null;
		/** Adds the divider above the SMTP block. */
		separated?: boolean;
	}

	let {
		idPrefix,
		heading,
		placeholder,
		host = $bindable(),
		port = $bindable(),
		useSsl = $bindable(),
		hostError = null,
		portError = null,
		separated = false
	}: Props = $props();

	const hostId = $derived(`${idPrefix}-host`);
	const portId = $derived(`${idPrefix}-port`);
	const hostErrorId = $derived(`${hostId}-error`);
	const portErrorId = $derived(`${portId}-error`);
</script>

<!--
	A fieldset, not a paragraph styled as a heading: both blocks hold a "Host", a
	"Port" and an SSL checkbox, and the legend is the only thing that tells a
	screen reader which server a field belongs to (WCAG 1.3.1). The divider stays
	on the wrapper, since a fieldset draws its legend into its top border.
-->
<div class={cn(separated && 'border-t border-border/60 pt-3')}>
	<fieldset class="space-y-2">
		<legend class="block text-sm font-medium text-foreground">{heading}</legend>
		<div class="grid gap-3 sm:grid-cols-[1fr_7.5rem]">
			<Field for={hostId} label={$_('accounts.form.host')} error={hostError} errorId={hostErrorId}>
				{#snippet children(control)}
					<Input
						id={hostId}
						type="text"
						bind:value={host}
						required
						maxlength={255}
						{placeholder}
						autocomplete="off"
						{...control}
					/>
				{/snippet}
			</Field>
			<Field for={portId} label={$_('accounts.form.port')} error={portError} errorId={portErrorId}>
				{#snippet children(control)}
					<Input
						id={portId}
						type="number"
						min={1}
						max={65535}
						bind:value={port}
						required
						{...control}
					/>
				{/snippet}
			</Field>
		</div>
		<label class="flex items-center gap-2 text-sm">
			<input type="checkbox" bind:checked={useSsl} class={nativeControlClass} />
			<span>{$_('accounts.form.useSsl')}</span>
		</label>
	</fieldset>
</div>
