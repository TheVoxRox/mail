import base from './playwright.config';

/*
 * The default config minus its webServer, for scripts/run-playwright-with-preview.mjs,
 * which starts the preview itself. Derived rather than restated so the projects
 * and baseURL live in one place.
 */
const { webServer: _webServer, ...config } = base;

export default config;
