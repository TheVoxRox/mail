<script lang="ts">
	import Icon from '$lib/components/Icon.svelte';
	import { Button } from '$lib/components/ui/button';
	import { formatSize } from '$lib/formatters.js';
	import { clientConfig } from '$lib/stores/clientConfig.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import type { ComposeAttachment } from '$lib/compose/request.js';
	import { onDestroy, onMount } from 'svelte';

	interface Props {
		attachments: ComposeAttachment[];
		onSelectStart?: () => void;
		onError?: (message: string) => void;
		disabled?: boolean;
	}

	let { attachments = $bindable(), onSelectStart, onError, disabled = false }: Props = $props();
	let readingFileName = $state('');
	let dragActive = $state(false);

	function attachmentTotalSize(list: ComposeAttachment[]): number {
		return list.reduce((total, item) => total + item.size, 0);
	}

	function validateFiles(files: File[]): string | null {
		const tooLarge = files.find((file) => file.size > $clientConfig.attachmentMaxBytes);
		if (tooLarge) {
			return $_('compose.attachmentTooLarge', {
				values: {
					name: tooLarge.name,
					limit: formatSize($clientConfig.attachmentMaxBytes, $appLocale ?? 'cs')
				}
			});
		}

		const nextTotal =
			attachmentTotalSize(attachments) + files.reduce((total, file) => total + file.size, 0);
		if (nextTotal > $clientConfig.attachmentTotalMaxBytes) {
			return $_('compose.attachmentsTooLarge', {
				values: { limit: formatSize($clientConfig.attachmentTotalMaxBytes, $appLocale ?? 'cs') }
			});
		}

		return null;
	}

	function confirmLargeFiles(files: File[]): Promise<boolean> {
		const largeFiles = files.filter(
			(file) => file.size >= $clientConfig.largeAttachmentWarningBytes
		);
		if (largeFiles.length === 0) return Promise.resolve(true);
		return confirmAction({
			title: $_('compose.largeAttachmentConfirmTitle'),
			description: $_('compose.largeAttachmentConfirm', {
				values: {
					count: largeFiles.length,
					limit: formatSize($clientConfig.largeAttachmentWarningBytes, $appLocale ?? 'cs')
				}
			}),
			confirmLabel: $_('compose.largeAttachmentConfirmAction'),
			cancelLabel: $_('common.cancel')
		});
	}

	function readAsBase64(file: File): Promise<string> {
		return new Promise((res, rej) => {
			const reader = new FileReader();
			reader.onload = () => {
				const result = reader.result as string;
				const comma = result.indexOf(',');
				res(comma >= 0 ? result.slice(comma + 1) : result);
			};
			reader.onerror = () => rej(reader.error ?? new Error('FileReader failed'));
			reader.readAsDataURL(file);
		});
	}

	function clipboardFileName(file: File, index: number): string {
		if (file.name) return file.name;
		const subtype = file.type.split('/')[1]?.split(';')[0] || 'bin';
		return `clipboard-${index + 1}.${subtype}`;
	}

	async function addFiles(files: File[], options: { clipboard?: boolean } = {}) {
		if (disabled || files.length === 0) return;
		onSelectStart?.();
		const validationError = validateFiles(files);
		if (validationError) {
			onError?.(validationError);
			return;
		}
		if (!(await confirmLargeFiles(files))) return;
		try {
			const added: ComposeAttachment[] = [];
			for (const [index, file] of files.entries()) {
				const fileName = options.clipboard ? clipboardFileName(file, index) : file.name;
				readingFileName = fileName;
				const base64Data = await readAsBase64(file);
				added.push({
					localId: crypto.randomUUID(),
					fileName,
					contentType: file.type || 'application/octet-stream',
					base64Data,
					size: file.size
				});
			}
			attachments = [...attachments, ...added];
		} catch (err) {
			onError?.(toErrorMessage(err));
		} finally {
			readingFileName = '';
		}
	}

	async function handleAttachmentSelect(event: Event) {
		if (disabled) return;
		const input = event.target as HTMLInputElement;
		const files = input.files;
		if (!files || files.length === 0) return;
		const fileList = Array.from(files);
		await addFiles(fileList);
		input.value = '';
	}

	function filesFromDataTransfer(dataTransfer: DataTransfer | null): File[] {
		if (!dataTransfer) return [];
		const files = Array.from(dataTransfer.files);
		if (files.length > 0) return files;
		return Array.from(dataTransfer.items)
			.filter((item) => item.kind === 'file')
			.map((item) => item.getAsFile())
			.filter((file): file is File => file != null);
	}

	function dataTransferHasFiles(dataTransfer: DataTransfer | null): boolean {
		return Boolean(
			dataTransfer &&
			(dataTransfer.files.length > 0 || Array.from(dataTransfer.types).includes('Files'))
		);
	}

	function handleDragEnter(event: DragEvent): void {
		if (disabled || !dataTransferHasFiles(event.dataTransfer)) return;
		event.preventDefault();
		dragActive = true;
	}

	function handleDragOver(event: DragEvent): void {
		if (disabled || !dataTransferHasFiles(event.dataTransfer)) return;
		event.preventDefault();
		if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
		dragActive = true;
	}

	function handleDragLeave(event: DragEvent): void {
		if (event.currentTarget !== event.target) return;
		dragActive = false;
	}

	function handleDrop(event: DragEvent): void {
		const files = filesFromDataTransfer(event.dataTransfer);
		if (disabled || files.length === 0) return;
		event.preventDefault();
		dragActive = false;
		void addFiles(files);
	}

	function handlePaste(event: ClipboardEvent): void {
		const files = filesFromDataTransfer(event.clipboardData);
		if (disabled || files.length === 0) return;
		event.preventDefault();
		void addFiles(files, { clipboard: true });
	}

	function handleDocumentPaste(event: Event): void {
		handlePaste(event as ClipboardEvent);
	}

	function removeAttachment(localId: string) {
		if (disabled) return;
		attachments = attachments.filter((a) => a.localId !== localId);
	}

	onMount(() => {
		document.addEventListener('paste', handleDocumentPaste);
	});

	onDestroy(() => {
		document.removeEventListener('paste', handleDocumentPaste);
	});
</script>

<div
	role="group"
	aria-label={$_('compose.attachmentDropZone')}
	class={`flex flex-wrap items-center gap-3 border-b px-4 py-2 transition-colors ${
		dragActive ? 'border-primary bg-primary/8' : 'border-border bg-background'
	}`}
	ondragenter={handleDragEnter}
	ondragover={handleDragOver}
	ondragleave={handleDragLeave}
	ondrop={handleDrop}
>
	<label
		class={`inline-flex items-center gap-1 rounded border border-input bg-background px-2 py-1 text-xs text-foreground ${
			disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:bg-muted'
		}`}
	>
		<Icon name="paperclip" size={14} />
		<span>{$_('compose.addAttachment')}</span>
		<input
			type="file"
			multiple
			{disabled}
			class="sr-only"
			onchange={handleAttachmentSelect}
			aria-label={$_('compose.addAttachment')}
		/>
	</label>
	<p class="text-xs text-muted-foreground">
		{dragActive ? $_('compose.dropAttachmentsActive') : $_('compose.dropAttachmentsHint')}
	</p>
	{#if attachments.length > 0}
		<ul class="flex flex-wrap gap-2" aria-label={$_('compose.attachmentsLabel')}>
			{#each attachments as att (att.localId)}
				<li
					class="inline-flex items-center gap-1 rounded bg-secondary px-2 py-1 text-xs text-secondary-foreground"
				>
					<span>{att.fileName}</span>
					<span class="text-muted-foreground">({formatSize(att.size, $appLocale ?? 'cs')})</span>
					<Button
						type="button"
						variant="ghost"
						size="icon-xs"
						onclick={() => removeAttachment(att.localId)}
						{disabled}
						class="ml-0.5 text-muted-foreground hover:text-destructive"
						aria-label={$_('compose.removeAttachment', { values: { name: att.fileName } })}
					>
						<Icon name="trash" size={12} />
					</Button>
				</li>
			{/each}
		</ul>
	{/if}
	{#if readingFileName}
		<p class="text-xs text-muted-foreground" role="status">
			{$_('compose.attachmentReading', { values: { name: readingFileName } })}
		</p>
	{/if}
</div>
