/**
 * What a drag is carrying, and where it landed.
 *
 * Three copies of "does this drag hold files" had grown — the attachment
 * picker, the vCard import and the drop guard each had one, and only one of
 * them looked past `dataTransfer.types`. They all read the same payload for
 * the same reason, so they read it from here.
 */

/** True when the drag payload contains files, rather than text or a link. */
export function dragHasFiles(event: DragEvent): boolean {
	const dt = event.dataTransfer;
	if (!dt) return false;
	if (dt.types && Array.from(dt.types).includes('Files')) return true;
	if (dt.files && dt.files.length > 0) return true;
	const items = dt.items;
	if (!items) return false;
	for (let i = 0; i < items.length; i++) {
		if (items[i].kind === 'file') return true;
	}
	return false;
}

/**
 * The files a drop is carrying. `files` is the list once the drop has landed;
 * `items` is what a paste hands over, and what some sources fill instead.
 */
export function filesFromDataTransfer(dataTransfer: DataTransfer | null): File[] {
	if (!dataTransfer) return [];
	const files = Array.from(dataTransfer.files);
	if (files.length > 0) return files;
	return Array.from(dataTransfer.items)
		.filter((item) => item.kind === 'file')
		.map((item) => item.getAsFile())
		.filter((file): file is File => file != null);
}

/**
 * True when the drag payload contains a URL — a link dragged out of another
 * window, or out of the message body.
 *
 * `text/uri-list` is the type the platform sets for a link; a selection dragged
 * inside a text field carries `text/plain` and `text/html` and nothing else.
 */
export function dragHasUrl(event: DragEvent): boolean {
	const types = event.dataTransfer?.types;
	if (!types) return false;
	return Array.from(types).includes('text/uri-list');
}

/**
 * True when the drop landed in something that takes typed text, where dropping
 * a link inserts its address instead of following it — the compose body, which
 * is a plain textarea, and the recipient inputs.
 */
export function isTextEntryTarget(target: EventTarget | null): boolean {
	if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) return true;
	return target instanceof HTMLElement && target.isContentEditable;
}
