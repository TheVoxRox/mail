<script lang="ts">
	/**
	 * One IMAP/SMTP endpoint group (host + port + "use SSL") for the custom
	 * server form. Rendered twice by {@link CustomServerFields} — the only
	 * differences between the IMAP and SMTP blocks are the id prefix, heading
	 * and host placeholder, so they share this component.
	 *
	 * The ids stay `{idPrefix}-host` / `{idPrefix}-port` (e.g. `acc-imap-host`)
	 * because `FIELD_INPUT_IDS` in accountFormValidation.ts focuses them by id
	 * after validation, and the e2e suite locates them the same way.
	 */
	import { Field } from '$lib/components/ui/field/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
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

<div class={cn('space-y-2', separated && 'border-t border-border/60 pt-3')}>
	<p class="text-sm font-medium text-foreground">{heading}</p>
	<div class="grid gap-3 sm:grid-cols-[1fr_120px]">
		<Field for={hostId} label={$_('accounts.form.host')} error={hostError} errorId={hostErrorId}>
			<Input
				id={hostId}
				type="text"
				bind:value={host}
				required
				maxlength={255}
				{placeholder}
				autocomplete="off"
				aria-invalid={hostError ? 'true' : undefined}
				aria-describedby={hostError ? hostErrorId : undefined}
			/>
		</Field>
		<Field for={portId} label={$_('accounts.form.port')} error={portError} errorId={portErrorId}>
			<Input
				id={portId}
				type="number"
				min={1}
				max={65535}
				bind:value={port}
				required
				aria-invalid={portError ? 'true' : undefined}
				aria-describedby={portError ? portErrorId : undefined}
			/>
		</Field>
	</div>
	<label class="flex items-center gap-2 text-sm">
		<input
			type="checkbox"
			bind:checked={useSsl}
			class="h-4 w-4 rounded border-input text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
		/>
		<span>{$_('accounts.form.useSsl')}</span>
	</label>
</div>
