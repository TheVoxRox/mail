/**
 * Trusting a sender to load remote images, and taking that trust back.
 *
 * Both are one request to the allow-list plus one thing the request cannot do
 * for itself: the bodies already fetched from that sender carry the old answer
 * in the reading pane's cache (see `forgetContentFromSender`). The two belong
 * together, which is why callers come here rather than to the API module — a
 * call site that changed the list and forgot the cache would look right until a
 * message was reopened.
 */
import { allowSenderRemoteImages, disallowSenderRemoteImages } from '#lib/api/remoteImages.js';
import { forgetContentFromSender } from '#lib/stores/selectedMessage.js';

/** From now on the sender's messages load their remote images on open. */
export async function trustRemoteImageSender(
	accountId: number,
	senderEmail: string
): Promise<void> {
	await allowSenderRemoteImages(accountId, senderEmail);
	forgetContentFromSender(senderEmail);
}

/** From now on the sender's messages open with their remote images blocked. */
export async function distrustRemoteImageSender(
	accountId: number,
	senderEmail: string
): Promise<void> {
	await disallowSenderRemoteImages(accountId, senderEmail);
	forgetContentFromSender(senderEmail);
}
