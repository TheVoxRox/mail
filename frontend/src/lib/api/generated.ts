import type { components } from './schema';

type Schema<Name extends keyof components['schemas']> = components['schemas'][Name];

export type ProblemDetail = Schema<'ProblemDetail'>;
export type ClientConfigResponse = Schema<'ClientConfigResponse'>;

export type AccountResponse = Schema<'AccountResponse'>;
export type AccountCreateRequest = Schema<'AccountCreateRequest'>;
export type AccountUpdateRequest = Schema<'AccountUpdateRequest'>;

export type MailProviderResponse = Schema<'MailProviderResponse'>;
export type FolderResponse = Schema<'FolderResponse'>;

export type AttachmentRequest = Schema<'AttachmentRequest'>;
export type AttachmentResponse = Schema<'AttachmentResponse'>;
export type MailRequest = Schema<'MailRequest'>;
export type DraftRequest = Schema<'DraftRequest'>;
export type SendAcceptedResponse = Schema<'SendAcceptedResponse'>;
export type MailSummaryResponse = Schema<'MailSummaryResponse'>;
export type MailDetailResponse = Schema<'MailDetailResponse'>;
export type MailContentResponse = Schema<'MailContentResponse'>;
export type MoveRequest = Schema<'MoveRequest'>;

// SSE notification stream — backend-defined payloads for every event variant.
export type SyncNotification = Schema<'SyncNotification'>;
export type SendNotification = Schema<'SendNotification'>;
export type ThreadUpdated = Schema<'ThreadUpdated'>;

// Conversation detail endpoint shape.
export type ThreadResponse = Schema<'ThreadResponse'>;
export type ConversationSummaryResponse = Schema<'ConversationSummaryResponse'>;

export type ContactEmailRequest = Schema<'ContactEmailRequest'>;
export type ContactEmailResponse = Schema<'ContactEmailResponse'>;
export type ContactCreateRequest = Schema<'ContactCreateRequest'>;
export type ContactUpdateRequest = Schema<'ContactUpdateRequest'>;
export type ContactPatchRequest = Schema<'ContactPatchRequest'>;
export type ContactResponse = Schema<'ContactResponse'>;
export type ContactAutocompleteResponse = Schema<'ContactAutocompleteResponse'>;
export type BulkContactCreateRequest = Schema<'BulkContactCreateRequest'>;
export type BulkContactCreateResponse = Schema<'BulkContactCreateResponse'>;
export type BulkContactCreateResult = Schema<'BulkContactCreateResult'>;
export type BulkContactDeleteRequest = Schema<'BulkContactDeleteRequest'>;
export type BulkContactDeleteResponse = Schema<'BulkContactDeleteResponse'>;
export type BulkContactDeleteResult = Schema<'BulkContactDeleteResult'>;

export type PagedMailSummaryResponse = Schema<'PagedResponseMailSummaryResponse'>;
export type PagedConversationSummaryResponse = Schema<'PagedResponseConversationSummaryResponse'>;
export type PagedContactResponse = Schema<'PagedResponseContactResponse'>;
