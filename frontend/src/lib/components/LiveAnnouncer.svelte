<script lang="ts">
	import { politeAnnouncements, assertiveAnnouncements } from '$lib/stores/toasts.js';
</script>

<!--
	App-wide screen-reader announcer. Mounted at the layout root (outside every
	{#if}) so the live regions exist in the DOM from the first render and never
	remount across boot / i18n transitions — a live region is only announced
	reliably when it already exists before its content changes. Messages are
	pushed in as transient text children by the toasts store (see `announce`).
	Visually hidden (sr-only); the visible toasts live in ToastRegion.
-->
<div aria-live="polite" aria-atomic="false" class="sr-only" id="live-region">
	{#each $politeAnnouncements as announcement (announcement.id)}
		<p>{announcement.message}</p>
	{/each}
</div>
<div aria-live="assertive" aria-atomic="false" class="sr-only">
	{#each $assertiveAnnouncements as announcement (announcement.id)}
		<p>{announcement.message}</p>
	{/each}
</div>
