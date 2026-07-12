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
