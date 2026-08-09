import { persistedStore } from './persisted.js';

export const READING_PANES = ['right', 'bottom', 'off'] as const;
export type ReadingPane = (typeof READING_PANES)[number];

export const MESSAGE_BODY_VIEWS = ['html', 'plain'] as const;
export type MessageBodyView = (typeof MESSAGE_BODY_VIEWS)[number];

export const readingPane = persistedStore<ReadingPane>('mail.readingPane', READING_PANES, 'off');

export function setReadingPane(value: ReadingPane): void {
	readingPane.set(value);
}

export const messageBodyView = persistedStore<MessageBodyView>(
	'mail.messageBodyView',
	MESSAGE_BODY_VIEWS,
	'html'
);

export function setMessageBodyView(value: MessageBodyView): void {
	messageBodyView.set(value);
}

export const MESSAGE_GROUPINGS = ['flat', 'grouped'] as const;
export type MessageGrouping = (typeof MESSAGE_GROUPINGS)[number];

/**
 * Whether the folder listing collapses messages into conversations (one row per
 * thread) or shows every message flat. Off (`flat`) by default — grouping is a
 * V0.2 opt-in, kept off so the rollout is user-visible-zero-risk (matches the
 * threading design's staged plan).
 */
export const messageGrouping = persistedStore<MessageGrouping>(
	'mail.messageGrouping',
	MESSAGE_GROUPINGS,
	'flat'
);

export function setMessageGrouping(value: MessageGrouping): void {
	messageGrouping.set(value);
}

export const CLOSE_ACTIONS = ['tray', 'quit'] as const;
export type CloseAction = (typeof CLOSE_ACTIONS)[number];

/**
 * What the window's close button does: hide to the tray and keep syncing
 * (`tray`) or quit the app (`quit`).
 *
 * Defaults to `tray` because that is the point of having a tray at all — a
 * mail client that stops checking for mail the moment its window is closed
 * cannot notify anyone. It is a setting rather than a fixed behaviour because
 * the same default is a trap for someone who reads the close button as "quit"
 * and then wonders why the app is still holding the mailbox.
 *
 * Read by `$lib/tray.ts`, which pushes it to the Rust close handler; outside
 * the desktop shell it has no effect.
 */
export const closeAction = persistedStore<CloseAction>('mail.closeAction', CLOSE_ACTIONS, 'tray');

export function setCloseAction(value: CloseAction): void {
	closeAction.set(value);
}
