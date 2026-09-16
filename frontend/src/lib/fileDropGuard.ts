import { dragHasFiles, dragHasUrl, isTextEntryTarget } from '$lib/dragPayload.js';

/**
 * Keeps a drop outside any drop zone from replacing the app.
 *
 * A browser's default for a file dropped on a page is to open it, and for a
 * link to follow it; in the desktop shell either one navigates the only webview
 * away from the app. While Tauri's own drag-and-drop handler was on (it is
 * switched off in src-tauri/src/lib.rs), wry called `SetAllowExternalDrop(false)`
 * and took every drag before the page saw one, so those defaults never came up
 * — and neither did the drop zones: attaching a file to a message or importing
 * a vCard by dragging did nothing. Switching the handler off brings the zones
 * back and the defaults with them.
 *
 * Only `preventDefault`, and only on drags nothing has cancelled yet. The zones
 * handle their own events first or regardless: the attachment picker on its
 * element, which a drag reaches before `window`, and the contacts page on
 * `window` itself, which reads no `defaultPrevented`.
 *
 * Two payloads are guarded, for the same reason and not the same way:
 *
 * - **Files**, everywhere. Nothing in the app opens a dropped file by itself.
 * - **A link**, but not over a text field. `text/uri-list` arrives from another
 *   window, from an internet shortcut, or dragged out of the message body,
 *   which is untrusted HTML — following it would hand the shell to whoever
 *   wrote the mail, and the CSP does not stop a top-level navigation. Over the
 *   compose body or a recipient input the same drop inserts the address, which
 *   is editing rather than navigation, so it is left alone. A selection dragged
 *   inside a text field carries no `text/uri-list` at all and is never touched.
 */
export function installFileDropGuard(target: Window = window): () => void {
	const guard = (event: DragEvent) => {
		if (event.defaultPrevented || !leavesTheApp(event)) return;
		event.preventDefault();
		/*
		 * Cancelling `dragover` is what makes a target droppable, so without
		 * this the whole app would show the copy cursor for a drop it is about
		 * to discard. Safe to set unconditionally: a zone that ran before us
		 * already returned above, and one that runs after us on `window` sets
		 * its own — see AttachmentPicker.svelte and routes/contacts/+page.svelte.
		 */
		if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
	};
	target.addEventListener('dragover', guard);
	target.addEventListener('drop', guard);
	return () => {
		target.removeEventListener('dragover', guard);
		target.removeEventListener('drop', guard);
	};
}

function leavesTheApp(event: DragEvent): boolean {
	if (dragHasFiles(event)) return true;
	return dragHasUrl(event) && !isTextEntryTarget(event.target);
}
