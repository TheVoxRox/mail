<script lang="ts">
	/**
	 * The link to the place an error's text sends the user, for the surfaces that
	 * show an error where it happened rather than as a toast. Renders nothing for
	 * an error that names no place, and nothing on that place itself.
	 *
	 * A link, not a button: it opens a place that exists. The toast carries the
	 * same link through `errorRemedy` as well.
	 */
	import { page } from '$app/state';
	import { errorRemedy } from '#lib/api/errors.js';
	import { Button } from '#lib/components/ui/button/index.js';

	interface Props {
		error: unknown;
		class?: string;
	}

	let { error, class: className }: Props = $props();

	const remedy = $derived(errorRemedy(error));
</script>

{#if remedy && remedy.href !== page.url.pathname}
	<Button variant="outline" size="xs" href={remedy.href} class={className}>{remedy.label}</Button>
{/if}
