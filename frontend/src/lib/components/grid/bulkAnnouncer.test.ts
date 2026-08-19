import { describe, expect, it, vi } from 'vitest';
import { createBulkAnnouncer } from './bulkAnnouncer.js';

describe('createBulkAnnouncer', () => {
	it('announces when the first row is selected', () => {
		const announce = vi.fn();
		createBulkAnnouncer(announce)(true);
		expect(announce).toHaveBeenCalledTimes(1);
	});

	it('stays quiet while the selection only grows', () => {
		const announce = vi.fn();
		const announceBulkActions = createBulkAnnouncer(announce);
		announceBulkActions(true);
		announceBulkActions(true);
		announceBulkActions(true);
		expect(announce).toHaveBeenCalledTimes(1);
	});

	it('says nothing while nothing is selected', () => {
		const announce = vi.fn();
		createBulkAnnouncer(announce)(false);
		expect(announce).not.toHaveBeenCalled();
	});

	it('arms again once the selection empties', () => {
		const announce = vi.fn();
		const announceBulkActions = createBulkAnnouncer(announce);
		announceBulkActions(true);
		announceBulkActions(false);
		announceBulkActions(true);
		expect(announce).toHaveBeenCalledTimes(2);
	});

	it('does not re-announce on an effect re-run that changed nothing', () => {
		// The effect also depends on the locale store, so it re-runs on a language
		// switch with the selection untouched.
		const announce = vi.fn();
		const announceBulkActions = createBulkAnnouncer(announce);
		announceBulkActions(true);
		announceBulkActions(true);
		expect(announce).toHaveBeenCalledTimes(1);
	});

	it('gives each grid its own flag', () => {
		const mail = vi.fn();
		const contacts = vi.fn();
		const announceMail = createBulkAnnouncer(mail);
		const announceContacts = createBulkAnnouncer(contacts);
		announceMail(true);
		announceMail(true);
		announceContacts(true);
		expect(mail).toHaveBeenCalledTimes(1);
		expect(contacts).toHaveBeenCalledTimes(1);
	});
});
