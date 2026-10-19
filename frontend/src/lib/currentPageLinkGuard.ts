/**
 * Keeps a link to the page already open from doing anything, as it did before
 * SvelteKit 3.
 *
 * SvelteKit 3 turns a click on a link to the current URL into a navigation with
 * `refreshAll` (the click handler in its client.js, `refreshAll: !changed`). Being
 * a navigation, it resets focus to <body>, the layout's afterNavigate then parks
 * it on the page's <h1>, and the route announcer reads the title. For a screen
 * reader that means Enter on the folder or contacts view already open in the
 * pane throws the user out of the pane onto the heading, and the contacts page
 * fetches its list again because its effects read `data`. SvelteKit 2 ignored
 * such a click, and that is the behaviour kept here.
 *
 * Only `preventDefault`, on `document.body`, for an unmodified primary click on
 * an anchor whose URL is the current one. SvelteKit listens on
 * `document.documentElement` and returns when the event is already cancelled,
 * so a click that bubbles through the body first never reaches its router. A
 * component that handles its own link (the subject links in the mail grids)
 * runs on the anchor, before this, and is left alone; so is everything
 * SvelteKit would not handle either — another window, a download, a modified
 * click or a different URL.
 *
 * A link with a fragment is left alone too, the one already in the URL
 * included: SvelteKit takes it as a hash navigation on the page, which scrolls
 * to the target and focuses it, and never as the `refreshAll` navigation this
 * guards against. Cancelling it broke the skip link the second time it was
 * used, once its `#main-content` was in the URL. That hash is also why the
 * current URL is compared without its own: after the skip link, Enter on the
 * folder already open would otherwise navigate to the same page without it.
 */
export function installCurrentPageLinkGuard(target: HTMLElement = document.body): () => void {
	const guard = (event: MouseEvent) => {
		if (event.defaultPrevented || event.button !== 0) return;
		if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
		const anchor = (event.target as Element | null)?.closest?.('a[href]');
		if (!(anchor instanceof HTMLAnchorElement)) return;
		if (anchor.hasAttribute('download')) return;
		if (anchor.target && anchor.target !== '_self') return;
		if (anchor.href.includes('#')) return;
		if (anchor.href !== window.location.href.split('#')[0]) return;
		event.preventDefault();
	};
	target.addEventListener('click', guard);
	return () => target.removeEventListener('click', guard);
}
