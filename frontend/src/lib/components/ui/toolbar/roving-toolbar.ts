import type { Attachment } from 'svelte/attachments';

/** What a toolbar holds that takes focus. A disabled control is passed over. */
const ITEMS = 'button, a[href], input:not([type="hidden"]), select, [tabindex]';

/** Controls that use the arrows, Home and End themselves; the toolbar leaves them alone. */
const OWN_ARROWS =
	'input:not([type="checkbox"]):not([type="radio"]):not([type="button"]), select, textarea';

/**
 * The keyboard model of a WAI-ARIA toolbar, for an element with
 * `role="toolbar"`: one Tab stop, ArrowLeft and ArrowRight to the previous
 * and next control (wrapping at either end), Home and End to the first and
 * last. The Tab stop is the control used last, so Tab back into the toolbar
 * lands where the user left it; while it is disabled, the first enabled one.
 *
 * Without it every button was a Tab stop of its own, as in a plain group, so
 * reaching the message after the toolbar took a Tab per action, and the role
 * promised the arrow keys a screen reader user then found doing nothing.
 *
 * The controls are found again on every change below the toolbar, because
 * they come and go with the selection (disabled without one) and with the
 * window width (folded into an overflow menu). ArrowDown and ArrowUp are left to
 * the control: on a menu button they open its menu.
 */
export function rovingToolbar(): Attachment<HTMLElement> {
	return (toolbar) => {
		let current: HTMLElement | null = null;

		const controls = () => [...toolbar.querySelectorAll<HTMLElement>(ITEMS)];
		const items = () =>
			controls().filter((el) => !(el as HTMLButtonElement).disabled && !el.closest('[inert]'));

		const setTabIndex = (el: HTMLElement, value: '0' | '-1') => {
			// Only a change is written: the observer below watches this attribute.
			if (el.getAttribute('tabindex') !== value) el.setAttribute('tabindex', value);
		};

		const apply = () => {
			const list = items();
			if (!current || !list.includes(current)) current = list[0] ?? null;
			// Disabled ones too, so none keeps a 0 from before it was disabled.
			for (const el of controls()) setTabIndex(el, el === current ? '0' : '-1');
		};

		const onKeydown = (event: KeyboardEvent) => {
			if (event.defaultPrevented || event.ctrlKey || event.altKey || event.metaKey) return;
			const target = event.target as HTMLElement;
			if (target.matches(OWN_ARROWS)) return;
			const list = items();
			const index = list.indexOf(target);
			if (index < 0 || list.length === 0) return;
			const targets: Record<string, HTMLElement | undefined> = {
				ArrowRight: list[(index + 1) % list.length],
				ArrowLeft: list[(index - 1 + list.length) % list.length],
				Home: list[0],
				End: list[list.length - 1]
			};
			const next = targets[event.key];
			if (!next) return;
			event.preventDefault();
			current = next;
			apply();
			next.focus();
		};

		const onFocusin = (event: FocusEvent) => {
			const target = event.target as HTMLElement;
			if (!items().includes(target)) return;
			current = target;
			apply();
		};

		apply();
		const observer = new MutationObserver(apply);
		observer.observe(toolbar, {
			subtree: true,
			childList: true,
			attributes: true,
			attributeFilter: ['disabled', 'tabindex']
		});
		toolbar.addEventListener('keydown', onKeydown);
		toolbar.addEventListener('focusin', onFocusin);
		return () => {
			observer.disconnect();
			toolbar.removeEventListener('keydown', onKeydown);
			toolbar.removeEventListener('focusin', onFocusin);
		};
	};
}
