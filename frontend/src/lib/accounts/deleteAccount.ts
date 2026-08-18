/**
 * Removing a mail account, asked for in the same words wherever it is offered.
 *
 * Two routes offer it — the account list and one account's detail — and both
 * spelled out the whole flow: the same four i18n keys in the same confirmation
 * dialog, `deleteAccount`, `loadAccounts`, the same success toast. Only the
 * bookkeeping around it differs (the list disables one row's button by id, the
 * detail page has a single flag and navigates away afterwards), which is why
 * the busy flag and the error sink stay with the callers and only the flow
 * itself lives here. Two copies of a destructive confirmation are two places
 * for the wording — or the tone — to drift apart.
 */
import { get } from 'svelte/store';
import { deleteAccount } from '$lib/api/accounts.js';
import { _ } from '$lib/i18n/index.js';
import { loadAccounts } from '$lib/stores/accounts.js';
import { confirmAction } from '$lib/stores/confirmDialog.js';
import { pushToast } from '$lib/stores/toasts.js';

/**
 * Asks first, then deletes and refreshes the account list.
 *
 * Returns whether the account is gone — false means the user declined, which
 * the detail page has to tell apart from success because only success may
 * navigate away from a route whose account no longer exists.
 *
 * Failures are thrown rather than reported: the two callers surface them
 * differently (a shared error line above the list, a message under the delete
 * button) and neither wants a toast on top of it.
 */
export async function confirmAndDeleteAccount(id: number, label: string): Promise<boolean> {
	const t = get(_);
	const confirmed = await confirmAction({
		title: t('accounts.deleteConfirmTitle'),
		description: t('accounts.deleteConfirm', { values: { name: label } }),
		confirmLabel: t('common.delete'),
		cancelLabel: t('common.cancel'),
		tone: 'destructive'
	});
	if (!confirmed) return false;
	await deleteAccount(id);
	await loadAccounts();
	pushToast(t('accounts.deletedToast'), { tone: 'success' });
	return true;
}
