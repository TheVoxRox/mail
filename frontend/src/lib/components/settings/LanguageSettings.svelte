<script lang="ts">
	import { _, appLocale, setLocale, LOCALE_LABELS, SUPPORTED_LOCALES } from '#lib/i18n/index.js';
	import { Field } from '#lib/components/ui/field/index.js';
	import { Select } from '#lib/components/ui/select/index.js';
	import { Surface } from '#lib/components/ui/surface/index.js';

	function handleLocaleChange(event: Event) {
		const value = (event.target as HTMLSelectElement).value;
		setLocale(value);
	}
</script>

<Surface as="section" class="max-w-2xl space-y-3">
	<h2 class="text-sm font-semibold">{$_('settings.language.heading')}</h2>
	<Field
		for="locale-select"
		label={$_('settings.language.label')}
		hint={$_('settings.language.hint')}
	>
		{#snippet children(control)}
			<Select
				id="locale-select"
				value={$appLocale ?? SUPPORTED_LOCALES[0]}
				onchange={handleLocaleChange}
				width="full"
				{...control}
			>
				<!--
					Each language is named in itself (English as "English", Czech in
					Czech), so each option says which language its name is in: read
					with the voice of the interface around it, the other language's
					name comes out mangled (3.1.2).
				-->
				{#each SUPPORTED_LOCALES as loc (loc)}
					<option value={loc} lang={loc}>{LOCALE_LABELS[loc]}</option>
				{/each}
			</Select>
		{/snippet}
	</Field>
</Surface>
