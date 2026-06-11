import { writable } from 'svelte/store';

type ConfirmTone = 'default' | 'destructive';

type ConfirmRequest = {
	id: number;
	title: string;
	description: string;
	confirmLabel: string;
	cancelLabel: string;
	tone: ConfirmTone;
};

type StoreEntry = ConfirmRequest & {
	resolve: (confirmed: boolean) => void;
};

export const confirmDialog = writable<StoreEntry | null>(null);

let nextId = 1;

type ConfirmActionOptions = {
	title: string;
	description: string;
	confirmLabel: string;
	cancelLabel: string;
	tone?: ConfirmTone;
};

export function confirmAction(options: ConfirmActionOptions): Promise<boolean> {
	return new Promise((resolve) => {
		confirmDialog.set({
			id: nextId++,
			title: options.title,
			description: options.description,
			confirmLabel: options.confirmLabel,
			cancelLabel: options.cancelLabel,
			tone: options.tone ?? 'default',
			resolve
		});
	});
}

export function resolveConfirmDialog(confirmed: boolean): void {
	confirmDialog.update((current) => {
		if (current) {
			current.resolve(confirmed);
		}
		return null;
	});
}
