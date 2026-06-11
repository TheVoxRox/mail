/** OAuth2 login flow opened in an external browser via the backend sidecar. */

import { requireSession } from '$lib/stores/session.js';

/** Builds the absolute URL that starts the OAuth2 flow for a given provider. */
async function oauthAuthStartUrl(provider: string): Promise<string> {
	const session = await requireSession();
	const url = new URL(`${session.baseUrl.replace(/\/+$/, '')}/v1/auth/oauth2/start`);
	// Microsoft Azure registers redirect_uri as http://localhost/... (Web
	// platform), and Spring Security derives {baseUrl} in redirect_uri from
	// the Host header of the current request. The external browser uses
	// Happy Eyeballs (IPv6 fail → IPv4 fallback), so `localhost` works even
	// when Tomcat only listens on 127.0.0.1. session.baseUrl keeps 127.0.0.1
	// because of the Tauri webview (plugin-http has no reliable IPv6
	// fallback).
	url.hostname = 'localhost';
	url.searchParams.set('provider', provider);
	return url.toString();
}

/**
 * Opens the authorization URL in the user's external browser. On success the
 * backend redirects to `auth-finished.html`, where Tauri / the user closes
 * the window.
 */
export async function startOAuthLogin(provider: string): Promise<void> {
	const url = await oauthAuthStartUrl(provider);
	const { open } = await import('@tauri-apps/plugin-shell');
	await open(url);
}
