/**
 * Where focus counts as being, for a shortcut that acts on what focus is in.
 *
 * A menu renders in a portal on `<body>`, away from the control that opened
 * it, so `closest()` from a focused menu item finds none of the regions around
 * that control. While a menu is open its trigger names it in `aria-controls`,
 * and that is the relation this follows: focus inside a menu counts as focus
 * on its trigger. A submenu's trigger is an item of the parent menu, so the
 * walk repeats until it leaves every menu. An element outside any menu, or in
 * one nothing names, stands for itself.
 */
export function focusAnchor(element: Element | null): Element | null {
	const visited = new Set<Element>();
	let current = element;
	while (current && !visited.has(current)) {
		visited.add(current);
		const menu = current.closest('[role="menu"]');
		if (!menu?.id) return current;
		const trigger = controllerOf(menu.id, current.ownerDocument);
		if (!trigger) return current;
		current = trigger;
	}
	return current;
}

/** `aria-controls` is an id list, so an exact attribute match could miss it. */
function controllerOf(id: string, doc: Document): Element | undefined {
	return [...doc.querySelectorAll('[aria-controls]')].find((element) =>
		element.getAttribute('aria-controls')?.split(/\s+/).includes(id)
	);
}
