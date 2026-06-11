<script lang="ts">
	import { _, appLocale, setLocale, LOCALE_LABELS, SUPPORTED_LOCALES } from '$lib/i18n/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';

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
		<Select
			id="locale-select"
			value={$appLocale ?? SUPPORTED_LOCALES[0]}
			onchange={handleLocaleChange}
			width="full"
		>
			{#each SUPPORTED_LOCALES as loc (loc)}
				<option value={loc}>{LOCALE_LABELS[loc]}</option>
			{/each}
		</Select>
	</Field>
</Surface>
