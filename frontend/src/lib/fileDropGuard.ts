/**
 * Keeps a file dropped outside any drop zone from replacing the app.
 *
 * A browser's default for a file dropped on a page is to open it, and in the
 * desktop shell that would navigate the only webview away from the app. While
 * Tauri's own drag-and-drop handler was on (it is switched off in
 * src-tauri/src/lib.rs), it took every file drag before the page saw one, so
 * that default never came up — and neither did the drop zones: attaching a file
 * to a message or importing a vCard by dragging did nothing.
 *
 * Only `preventDefault`, and only on file drags nothing has cancelled yet. The
 * zones handle their own events first or regardless: the attachment picker on
 * its element, which a drag reaches before `window`, and the contacts page on
 * `window` itself, which reads neither `defaultPrevented` nor a `dropEffect`
 * left behind. A text drag — moving a selection in the compose editor — carries
 * no `Files` type and is left alone.
 */
export function installFileDropGuard(target: Window = window): () => void {
	const guard = (event: DragEvent) => {
		if (event.defaultPrevented || !carriesFiles(event)) return;
		event.preventDefault();
	};
	target.addEventListener('dragover', guard);
	target.addEventListener('drop', guard);
	return () => {
		target.removeEventListener('dragover', guard);
		target.removeEventListener('drop', guard);
	};
}

function carriesFiles(event: DragEvent): boolean {
	return Array.from(event.dataTransfer?.types ?? []).includes('Files');
}
