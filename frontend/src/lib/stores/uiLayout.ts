import { browser } from '$app/environment';
import { writable } from 'svelte/store';

export type ReadingPane = 'right' | 'bottom' | 'off';
export type MessageBodyView = 'html' | 'plain';

const STORAGE_KEY = 'mail.readingPane';
const DEFAULT_READING_PANE: ReadingPane = 'off';

const MESSAGE_BODY_VIEW_STORAGE_KEY = 'mail.messageBodyView';
const DEFAULT_MESSAGE_BODY_VIEW: MessageBodyView = 'html';

function readInitialReadingPane(): ReadingPane {
	if (!browser) return DEFAULT_READING_PANE;

	try {
		const stored = window.localStorage.getItem(STORAGE_KEY);
		if (stored === 'right' || stored === 'bottom' || stored === 'off') {
			return stored;
		}
	} catch {
		// ignore
	}

	return DEFAULT_READING_PANE;
}

function readInitialMessageBodyView(): MessageBodyView {
	if (!browser) return DEFAULT_MESSAGE_BODY_VIEW;

	try {
		const stored = window.localStorage.getItem(MESSAGE_BODY_VIEW_STORAGE_KEY);
		if (stored === 'html' || stored === 'plain') {
			return stored;
		}
	} catch {
		// ignore
	}

	return DEFAULT_MESSAGE_BODY_VIEW;
}

export const readingPane = writable<ReadingPane>(readInitialReadingPane());

readingPane.subscribe((value) => {
	if (!browser) return;

	try {
		window.localStorage.setItem(STORAGE_KEY, value);
	} catch {
		// ignore
	}
});

export function setReadingPane(value: ReadingPane): void {
	readingPane.set(value);
}

export const messageBodyView = writable<MessageBodyView>(readInitialMessageBodyView());

messageBodyView.subscribe((value) => {
	if (!browser) return;

	try {
		window.localStorage.setItem(MESSAGE_BODY_VIEW_STORAGE_KEY, value);
	} catch {
		// ignore
	}
});

export function setMessageBodyView(value: MessageBodyView): void {
	messageBodyView.set(value);
}
