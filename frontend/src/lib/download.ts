/*
 * Shared "save blob as file" flow for API downloads (diagnostic dump,
 * vCard export, message attachments): a transient <a download> click on
 * an object URL. The anchor is attached to the document so the click
 * reliably triggers the download across webviews, and the object URL is
 * revoked right away — the browser has already taken over the transfer.
 */
export function saveBlobAsFile(blob: Blob, filename: string): void {
	const url = URL.createObjectURL(blob);
	const link = document.createElement('a');
	link.href = url;
	link.download = filename;
	document.body.append(link);
	link.click();
	link.remove();
	URL.revokeObjectURL(url);
}
