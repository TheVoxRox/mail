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
 * Defaults to `quit` because that is what a close button is read as. The tray
 * is the reason the other value exists — a mail client that stops checking for
 * mail the moment its window is closed cannot notify anyone — but an app that
 * outlives its own window is a surprise, and a surprise belongs behind a
 * choice the user made rather than behind the default. Whoever wants the
 * background notifications switches it on; whoever closes the window gets what
 * closing a window does everywhere else on the machine.
 *
 * Read by `#lib/tray.ts`, which pushes it to the Rust close handler; outside
 * the desktop shell it has no effect.
 */
export const closeAction = persistedStore<CloseAction>('mail.closeAction', CLOSE_ACTIONS, 'quit');

export function setCloseAction(value: CloseAction): void {
	closeAction.set(value);
}

export const NEW_MAIL_NOTIFICATIONS = ['on', 'off'] as const;
export type NewMailNotifications = (typeof NEW_MAIL_NOTIFICATIONS)[number];

/**
 * Whether mail that arrives while the window is hidden or behind another
 * application is announced with a Windows notification
 * (`#lib/newMailNotifications.ts`).
 *
 * On by default, because it is what the tray is for: an app that syncs in the
 * background and tells nobody has kept the mail to itself. And it costs
 * nothing to a user who is looking — a window with the focus never gets one,
 * since its own toast has already been read out. Off is for whoever finds
 * the sound or the screen-reader announcement intrusive; Windows' own
 * per-app notification setting turns it off as well.
 */
export const newMailNotifications = persistedStore<NewMailNotifications>(
	'mail.newMailNotifications',
	NEW_MAIL_NOTIFICATIONS,
	'on'
);

export function setNewMailNotifications(value: NewMailNotifications): void {
	newMailNotifications.set(value);
}

export const TOAST_DISMISSALS = ['auto', 'manual'] as const;
export type ToastDismissal = (typeof TOAST_DISMISSALS)[number];

/**
 * Whether a toast that is not an error closes by itself after a few seconds.
 *
 * A toast that vanishes on a timer is a time limit on reading it, and WCAG
 * 2.2.1 lets one stand only if the user can turn it off (or extend it tenfold),
 * so `manual` keeps every toast until it is closed. Error toasts never close by
 * themselves either way; while the pointer or focus is on a toast its clock
 * stops (ToastRegion).
 */
export const toastDismissal = persistedStore<ToastDismissal>(
	'mail.toastDismissal',
	TOAST_DISMISSALS,
	'auto'
);

export function setToastDismissal(value: ToastDismissal): void {
	toastDismissal.set(value);
}
