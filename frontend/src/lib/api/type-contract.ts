import type * as Generated from '$lib/api/generated.js';
import type * as Local from '$lib/types.js';

type Assert<T extends true> = T;

type GeneratedValue<T> = undefined extends T ? Exclude<T, undefined> | null | undefined : T;

type LooseGenerated<T> = T extends readonly (infer Item)[]
	? LooseGenerated<Item>[]
	: T extends object
		? { [Key in keyof T]: LooseGenerated<GeneratedValue<T[Key]>> }
		: GeneratedValue<T>;

type ExtraLocalKeys<LocalDto, GeneratedDto> = Exclude<keyof LocalDto, keyof GeneratedDto>;

type IncompatibleLocalKeys<LocalDto, GeneratedDto> = {
	[Key in keyof LocalDto & keyof GeneratedDto]: LocalDto[Key] extends LooseGenerated<
		GeneratedDto[Key]
	>
		? never
		: Key;
}[keyof LocalDto & keyof GeneratedDto];

type LocalDtoMatchesSchema<LocalDto, GeneratedDto> = [
	ExtraLocalKeys<LocalDto, GeneratedDto>
] extends [never]
	? [IncompatibleLocalKeys<LocalDto, GeneratedDto>] extends [never]
		? true
		: false
	: false;

type _AccountResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.AccountResponse, Generated.AccountResponse>
>;
type _AccountCreateRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.AccountCreateRequest, Generated.AccountCreateRequest>
>;
type _AccountUpdateRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.AccountUpdateRequest, Generated.AccountUpdateRequest>
>;
type _MailProviderResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.MailProviderResponse, Generated.MailProviderResponse>
>;
type _FolderResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.FolderResponse, Generated.FolderResponse>
>;
type _MailSummaryResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.MailSummaryResponse, Generated.MailSummaryResponse>
>;
type _MailDetailResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.MailDetailResponse, Generated.MailDetailResponse>
>;
type _MailContentResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.MailContentResponse, Generated.MailContentResponse>
>;
type _MailRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.MailRequest, Generated.MailRequest>
>;
type _DraftRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.DraftRequest, Generated.DraftRequest>
>;
type _ContactEmailRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.ContactEmailRequest, Generated.ContactEmailRequest>
>;
type _ContactEmailResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.ContactEmailResponse, Generated.ContactEmailResponse>
>;
type _ContactCreateRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.ContactCreateRequest, Generated.ContactCreateRequest>
>;
type _ContactUpdateRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.ContactUpdateRequest, Generated.ContactUpdateRequest>
>;
type _ContactPatchRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.ContactPatchRequest, Generated.ContactPatchRequest>
>;
type _ContactResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.ContactResponse, Generated.ContactResponse>
>;
type _ContactAutocompleteResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.ContactAutocompleteResponse, Generated.ContactAutocompleteResponse>
>;
type _BulkContactCreateRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.BulkContactCreateRequest, Generated.BulkContactCreateRequest>
>;
type _BulkContactDeleteRequestMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.BulkContactDeleteRequest, Generated.BulkContactDeleteRequest>
>;
type _PagedMailSummaryResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<
		Local.PagedResponse<Local.MailSummaryResponse>,
		Generated.PagedMailSummaryResponse
	>
>;
type _PagedContactResponseMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.PagedResponse<Local.ContactResponse>, Generated.PagedContactResponse>
>;

// SSE notification stream payloads — guards against backend record drift.
type _SyncNotificationMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.SyncNotification, Generated.SyncNotification>
>;
type _SendNotificationMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.SendNotification, Generated.SendNotification>
>;
type _ThreadUpdatedMatchesSchema = Assert<
	LocalDtoMatchesSchema<Local.ThreadUpdated, Generated.ThreadUpdated>
>;
