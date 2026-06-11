// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		// interface PageState {}
		// interface Platform {}
	}

	interface Window {
		__MAIL_E2E__?: {
			openPalette: () => void;
			reportClientError: (input: {
				kind: 'window_error' | 'unhandled_rejection' | 'manual';
				error?: unknown;
				message?: string;
				source?: string;
				line?: number;
				column?: number;
				context?: Record<string, unknown>;
			}) => Promise<'sent' | 'disabled' | 'failed'>;
			resetClientErrorReportingForTests: () => void;
			showMockUpdateForTests: (version?: string, options?: { failInstall?: boolean }) => void;
			showMockUpdateFailureForTests: (message?: string) => void;
			resetUpdateStateForTests: () => void;
		};
		__MAIL_MSW__?: {
			worker: unknown;
			reset: () => void;
			pushSyncCompleted: (event: {
				accountId: number;
				folderName: string;
				newMessagesCount?: number;
				timestamp?: string;
				type?: string;
			}) => void;
			pushSyncCompletedCrLf: (event: {
				accountId: number;
				folderName: string;
				newMessagesCount?: number;
				timestamp?: string;
				type?: string;
			}) => void;
			pushSendCompleted: () => void;
			pushSendFailed: (errorCode?: string) => void;
			setReadinessDelayMs: (delayMs: number) => void;
			setReadinessFailures: (count: number) => void;
			setFolderAuthFailure: (enabled: boolean) => void;
			setVCardExportDelayMs: (delayMs: number) => void;
			failNextVCardExport: () => void;
		};
		__MAIL_CAPTURED_ERROR_REPORTS__?: Array<{
			url: string;
			body: string | null;
			headers: string[][];
		}>;
		__MAIL_LAST_CLIENT_ERROR_REPORT_FAILURE__?: string;
		__MAIL_TEST_SESSION__?: {
			appName: string;
			appVersion: string;
			apiVersion: string;
			minClientVersion: string;
			dbSchemaVersion: string;
			port: number;
			apiKey: string;
			baseUrl: string;
		};
	}
}

export {};
