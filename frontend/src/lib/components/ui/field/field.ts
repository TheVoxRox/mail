/**
 * The link between a Field's hint / error and the control they describe.
 *
 * Field renders both, and it owns their ids — `{for}-hint` for the hint, the
 * caller's `errorId` for the error. Until now the control repeated that
 * knowledge by hand (`aria-describedby="acc-email-hint"`, and the
 * `error ? errorId : undefined` conditional a second time), which made the
 * link a convention rather than a guarantee: a `<Field hint={…}>` whose
 * control forgot the attribute renders a hint that no screen reader ever
 * reaches, and nothing says so. Every one of the nine call sites happened to
 * be right; the tenth is the problem.
 *
 * So Field derives the attributes and hands them to the control instead:
 *
 *     <Field for="acc-email" label={…} hint={…}>
 *         {#snippet children(control)}
 *             <Input id="acc-email" {...control} />
 *         {/snippet}
 *     </Field>
 *
 * A pure function rather than logic inside the component so the invariant is
 * covered by the unit suite — vitest runs in `node`, so a Svelte component
 * cannot be rendered there, but this can.
 */

/** What the control inside a Field must carry. Spread it onto the control. */
export interface FieldControlProps {
	'aria-describedby': string | undefined;
	'aria-invalid': 'true' | undefined;
}

export interface FieldControlInput {
	/** The Field's `for` — the control's id, and the stem of the hint's id. */
	for?: string;
	/** The hint text, or null/undefined when the Field renders none. */
	hint?: string | null;
	/** The error text, or null/undefined when the Field renders none. */
	error?: string | null;
	/** The id the caller gave the error element. */
	errorId?: string;
}

/** The id Field gives its hint element, or undefined when it renders none. */
export function fieldHintId(forId: string | undefined): string | undefined {
	return forId ? `${forId}-hint` : undefined;
}

/**
 * Both ids when both elements render, in reading order: the hint describes the
 * field in general, the error says what is wrong with it right now.
 *
 * A hint without `for` and an error without `errorId` are both dropped rather
 * than guessed at — Field renders those elements without an id, so there is
 * nothing to point at, and inventing one would name an element that does not
 * exist.
 */
export function fieldControlProps({
	for: forId,
	hint,
	error,
	errorId
}: FieldControlInput): FieldControlProps {
	const ids = [hint ? fieldHintId(forId) : undefined, error ? errorId : undefined].filter(
		(id): id is string => Boolean(id)
	);
	return {
		'aria-describedby': ids.length > 0 ? ids.join(' ') : undefined,
		'aria-invalid': error ? 'true' : undefined
	};
}
