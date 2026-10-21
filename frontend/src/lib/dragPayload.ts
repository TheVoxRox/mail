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
 * `items` is what some sources fill instead.
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
 * The files a paste is carrying that come from the file system: what Explorer
 * puts on the clipboard when a file is copied. An image on the clipboard
 * reaches the paste as a file too, named `image.png`, and is left out. Excel
 * puts a picture of the copied cells there beside their text, so taking it
 * turned a paste of cells into an attachment and swallowed the text.
 *
 * Measured in Edge 155, the Chromium of the app's WebView2: a file from the
 * file system has a file system entry, an image from the clipboard has none.
 * A copied folder has an entry that is not a file, and is left out as well.
 */
export function filesFromFileSystem(dataTransfer: DataTransfer | null): File[] {
	if (!dataTransfer) return [];
	return Array.from(dataTransfer.items)
		.filter((item) => item.kind === 'file' && item.webkitGetAsEntry()?.isFile === true)
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
 * Input types that are controls rather than places to type. Everything else is
 * text entry, including a `type` the browser does not know: the IDL attribute
 * reports an unknown one as `text`, which is also how it behaves.
 *
 * Measured against Chromium rather than reasoned about, because the whole
 * point is what the browser does with a drop nobody claims. Dropping a link on
 * `text`, `search`, `url`, `email`, `tel`, `password` and `textarea` fires a
 * drop event and inserts the address; `number` fires one and discards the
 * value, which is useless but harmless. A `checkbox` fires **no drop event at
 * all** — it behaves exactly like a plain `div`, so nothing on the page can
 * claim that drop, and what happens next is the browser's own default for a
 * link. The date family is here for the same reason as `checkbox`: it is a
 * picker, not a field, and an unmeasured target belongs on the guarded side.
 */
const NON_TEXT_INPUT_TYPES = new Set([
	'button',
	'checkbox',
	'color',
	'date',
	'datetime-local',
	'file',
	'hidden',
	'image',
	'month',
	'radio',
	'range',
	'reset',
	'submit',
	'time',
	'week'
]);

/**
 * True when the drop landed in something that takes typed text, where dropping
 * a link inserts its address instead of following it — the compose body, which
 * is a plain textarea, and the recipient inputs.
 */
export function isTextEntryTarget(target: EventTarget | null): boolean {
	if (target instanceof HTMLTextAreaElement) return true;
	if (target instanceof HTMLInputElement) return !NON_TEXT_INPUT_TYPES.has(target.type);
	return target instanceof HTMLElement && target.isContentEditable;
}
