import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { codeOnly, withoutAnnotations } from './lib/source-text.mjs';

/*
 * Fails a Java declaration in main sources that nothing calls, and names the
 * two weaker cases separately instead of lumping them in.
 *
 * knip does this for TypeScript and has since #313 also done it with the test
 * files out of the graph. Java had no equivalent: SpotBugs reports an uncalled
 * *private* method and an unread field, Error Prone unused locals, and neither
 * says a word about a public method whose last caller left. That gap is what
 * made #299 a manual sweep of every service and util class.
 *
 * Three verdicts, because collapsing them argues for the wrong fix:
 *
 *   - dead        nothing names it anywhere, tests included. Delete it.
 *   - test-only   only tests name it. The method survives because something
 *                 tests it, which is not the same as being used — the exact
 *                 shape of MailSyncService.getMessageOrThrow in #299.
 *   - internal    only its own file names it. Not dead at all: too visible.
 *                 MessageDownloader.getLatestUidFromServer came out of the
 *                 first prototype next to a genuinely dead method, and the
 *                 right fix was `private`, not deletion.
 *
 * Deliberate exceptions carry a `@callerless <reason>` line comment on the
 * declaration, with the reason required. A line comment rather than javadoc,
 * and not a block comment either: Error Prone rejects a javadoc block tag it
 * does not know (InvalidBlockTag) and flags a block comment that carries
 * javadoc tags (AlmostJavadoc), so both of those spellings buy a warning on
 * every build for a note addressed to this gate rather than to a caller.
 * ImapCapabilities.hasQresync is the
 * standing example: the probe records a capability the sync does not use yet,
 * and #299 had to write a paragraph hoping the next sweep would read it.
 *
 * What it cannot see, and why the skip list exists: a framework calling in by
 * reflection. Anything annotated @Bean, @Scheduled, @EventListener, a request
 * mapping, a lifecycle hook or a validation constraint is invoked by Spring or
 * Jakarta Validation, never by our code, so it is skipped wholesale.
 *
 * Ambiguity is resolved by staying quiet: a name declared in more than one
 * main file (getName, getId, close) cannot be attributed to one of them by
 * name alone, so it is only reported when *nothing* names it anywhere.
 *
 * Usage: node scripts/check-java-callers.mjs
 */

const repoRoot = path.join(process.cwd(), '..');

const MAIN = 'backend/src/main/';
const TEST = 'backend/src/test/';

/**
 * Annotations that mean "a framework calls this, not us". Matched on the
 * declaration's own annotations, not on the class.
 */
const FRAMEWORK_ANNOTATIONS = [
	'Override',
	'Bean',
	// Spring builds these itself; nothing in our code ever names the class.
	'Component',
	'Service',
	'Repository',
	'Controller',
	'RestController',
	'Configuration',
	'ControllerAdvice',
	'RestControllerAdvice',
	'SpringBootApplication',
	'ConfigurationProperties',
	// JPA lifecycle callbacks and the entities Hibernate maps.
	'Entity',
	'Embeddable',
	'PrePersist',
	'PreUpdate',
	'PreRemove',
	'PostPersist',
	'PostUpdate',
	'PostRemove',
	'PostLoad',
	'EventListener',
	'TransactionalEventListener',
	'Scheduled',
	'PostConstruct',
	'PreDestroy',
	'ExceptionHandler',
	'InitBinder',
	'ModelAttribute',
	'GetMapping',
	'PostMapping',
	'PutMapping',
	'PatchMapping',
	'DeleteMapping',
	'RequestMapping',
	'AssertTrue',
	'AssertFalse',
	'JsonProperty',
	'JsonValue',
	'JsonCreator',
	'JsonIgnore',
	'Autowired',
	'ConditionalOnMissingBean'
];

/** Names the JVM or a framework reaches by convention rather than by call. */
const CONVENTION_NAMES = new Set([
	'main',
	'equals',
	'hashCode',
	'toString',
	'compareTo',
	'values',
	'valueOf',
	'close',
	'run',
	'call',
	'get',
	'apply',
	'accept'
]);

const CONTROL_KEYWORDS = new Set([
	'if',
	'for',
	'while',
	'switch',
	'catch',
	'return',
	'new',
	'synchronized',
	'try',
	'do',
	'else',
	'yield',
	'assert',
	'throw',
	'case'
]);

const TYPE_DECLARATION = /\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)/;

const MODIFIERS =
	'(?:(?:public|protected|private|static|final|abstract|default|synchronized|native)\\s+)*';
/*
 * A type name, optionally with generic arguments and array brackets.
 *
 * The generic part is its own alternation rather than another character in the
 * class, because a type argument list contains spaces — `Map<String, Long>` —
 * and a character class cannot hold one without letting the type run into the
 * name after it. Written the flat way, `public Map<String, Long> counts()`
 * split as type `Map<String,` plus name `Long>` and matched nothing at all, so
 * the declaration was invisible to this gate: `AccountLastErrorJson.read` and
 * every other signature returning a parameterised type could lose its last
 * caller and still pass. One level of nesting is enough for `List<Map<K, V>>`;
 * deeper than that the gate goes quiet the way it does everywhere else.
 */
const TYPE_NAME = '[A-Za-z_$][\\w$.]*(?:\\s*<[^<>]*(?:<[^<>]*>[^<>]*)*>)?(?:\\s*\\[\\])*';
/*
 * A return type, then a name, then arguments — with the type captured too,
 * because that capture is what separates a declaration from a call. `return
 * foo(x);` has the exact shape of a declaration once `return` is allowed to
 * stand where a type belongs, which cost this gate 40-odd phantom findings on
 * its first run over the real tree.
 */
const METHOD_DECLARATION = new RegExp(
	`^[\\s}]*${MODIFIERS}(?:<[^>]+>\\s+)?(${TYPE_NAME})\\s+([A-Za-z_$][\\w$]*)\\s*\\(`
);
/*
 * A constructor: modifiers, then the enclosing type's own name, then arguments
 * — no return type, which is exactly why METHOD_DECLARATION cannot see one.
 *
 * It is matched only to be SUBTRACTED. A constructor is part of declaring the
 * type, not a use of it, but it does put the type's name in the file a second
 * time, and `internalUses` is a token count: `public class Foo` plus
 * `public Foo(...)` read as "Foo uses itself once", which routed every such
 * type into the `internal` bucket and then dropped it there, because a type
 * cannot be narrowed to private. The effect was that a class was reportable as
 * dead only while it declared no constructor — which is to say, almost never.
 */
const CONSTRUCTOR_DECLARATION = new RegExp(`^[\\s}]*${MODIFIERS}([A-Za-z_$][\\w$]*)\\s*[({]`);

function git(args) {
	return execFileSync('git', args, {
		cwd: repoRoot,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	});
}

const files = git(['ls-files', '--', 'backend/src/*.java'])
	.split('\n')
	.map((file) => file.trim())
	.filter(Boolean);

/** Identifier occurrences per file, comments and string literals excluded. */
const tokenCounts = new Map();
const sources = new Map();

for (const file of files) {
	const raw = readFileSync(path.join(repoRoot, file), 'utf8');
	sources.set(file, raw);
	const counts = new Map();
	for (const token of codeOnly(raw).match(/[A-Za-z_$][\w$]*/g) ?? []) {
		counts.set(token, (counts.get(token) ?? 0) + 1);
	}
	tokenCounts.set(file, counts);
}

/**
 * The javadoc and annotations immediately above a line, as raw text — where
 * both the skip rules and the `@callerless` waiver live.
 *
 * Walks up to the end of whatever came before (a line closing with `;`, `{` or
 * `}`) rather than collecting only lines that *look* like annotations. An
 * annotation wraps across lines as often as not, and its continuation starts
 * with a class name, not an `@` — which is how a `@Configuration` class with a
 * multi-line `@EnableConfigurationProperties` under it read as un-annotated.
 */
function preamble(lines, index) {
	const collected = [];
	for (let scan = index - 1; scan >= 0; scan -= 1) {
		const line = lines[scan].trim();
		if (/[;{}]$/.test(line)) break;
		collected.unshift(lines[scan]);
	}
	return collected.join('\n');
}

const declarations = [];
/** Files declaring a mapped type — see the @Entity note in the buckets below. */
const entityFiles = new Set();
/** `<file> <type>` → how many constructors that file declares for that type. */
const constructorsPerFile = new Map();

for (const file of files) {
	if (!file.startsWith(MAIN)) continue;
	if (/@(?:Entity|Embeddable|MappedSuperclass)\b/.test(sources.get(file))) entityFiles.add(file);
	const raw = sources.get(file);
	const rawLines = raw.split('\n');
	const codeLines = codeOnly(raw).split('\n');
	const enclosingTypes = new Set();

	for (const line of codeLines) {
		const type = TYPE_DECLARATION.exec(line);
		if (type) enclosingTypes.add(type[1]);
	}

	for (let index = 0; index < codeLines.length; index += 1) {
		const line = withoutAnnotations(codeLines[index]);
		const type = TYPE_DECLARATION.exec(line);
		const method = TYPE_DECLARATION.test(line) ? null : METHOD_DECLARATION.exec(line);

		/*
		 * Counted before any of the bailouts below, because a constructor reaches
		 * none of them: it carries no return type, so METHOD_DECLARATION never
		 * matches it and the line leaves this loop at `if (!name)`. The count is
		 * subtracted from the type's own-file token total further down.
		 */
		if (!type) {
			const constructor = CONSTRUCTOR_DECLARATION.exec(line);
			if (constructor && enclosingTypes.has(constructor[1])) {
				const key = `${file} ${constructor[1]}`;
				constructorsPerFile.set(key, (constructorsPerFile.get(key) ?? 0) + 1);
			}
		}

		if (method && CONTROL_KEYWORDS.has(method[1].trim())) continue;
		const name = type?.[1] ?? method?.[2];
		if (!name) continue;
		if (CONTROL_KEYWORDS.has(name) || CONVENTION_NAMES.has(name)) continue;
		// A constructor names its own type; it is reached through `new Type(`.
		if (!type && enclosingTypes.has(name)) continue;

		const above = preamble(rawLines, index);
		if (FRAMEWORK_ANNOTATIONS.some((tag) => new RegExp(`@${tag}\\b`).test(above))) continue;

		// The reason has to sit on the tag's own line: `\s+` swallows the line
		// break, and then whatever opens the next line — another `//`, a javadoc
		// `*` — reads as a reason, so a bare `@callerless` would waive a finding
		// while saying nothing.
		const waiver = /@callerless[^\S\n]+(\S[^\n]*)/.exec(above);
		declarations.push({
			file,
			line: index + 1,
			name,
			kind: type ? 'type' : 'method',
			visible: /\b(?:public|protected)\b/.test(codeLines[index]),
			waiver: waiver?.[1]?.trim()
		});
	}
}

/** How many main files declare a given name — >1 makes references ambiguous. */
const declaringFiles = new Map();
for (const declaration of declarations) {
	const seen = declaringFiles.get(declaration.name) ?? new Set();
	seen.add(declaration.file);
	declaringFiles.set(declaration.name, seen);
}

const declarationsPerFile = new Map();
for (const declaration of declarations) {
	const key = `${declaration.file} ${declaration.name}`;
	declarationsPerFile.set(key, (declarationsPerFile.get(key) ?? 0) + 1);
}

const findings = { dead: [], testOnly: [], internal: [] };
const waived = [];

/*
 * Overloads share a name, and nothing here can tell which one a call site
 * meant, so a (file, name) pair is one finding at the first declaration. The
 * alternative reports `performFullSyncCycle` twice for a two-line delegation
 * and invites deleting the half that is doing the work.
 */
const seenPairs = new Set();

for (const declaration of declarations) {
	const { file, name } = declaration;
	const pair = `${file} ${name}`;
	if (seenPairs.has(pair)) continue;
	seenPairs.add(pair);
	const own = tokenCounts.get(file).get(name) ?? 0;
	/*
	 * Everything in this file that spells the name without using it: the
	 * declarations themselves, plus — for a type — its constructors, which name
	 * it because they declare it, not because anything called it.
	 */
	const declaredHere = (declarationsPerFile.get(pair) ?? 1) + (constructorsPerFile.get(pair) ?? 0);
	const internalUses = own - declaredHere;

	let mainUses = 0;
	let testUses = 0;
	for (const other of files) {
		if (other === file) continue;
		const count = tokenCounts.get(other).get(name) ?? 0;
		if (count === 0) continue;
		if (other.startsWith(TEST)) testUses += count;
		else mainUses += count;
	}

	if (mainUses > 0) continue;

	// Two classes owning the same name: a reference cannot be attributed to
	// either, so only the case where nothing anywhere names it is reportable.
	const ambiguous = (declaringFiles.get(name)?.size ?? 1) > 1;
	if (ambiguous && (testUses > 0 || internalUses > 0)) continue;

	/*
	 * Order matters, and this is the order the first run got wrong: a symbol
	 * its own file uses is alive whatever the tests do, so "internal" has to
	 * be decided before "test-only". Reversed, every nested record a service
	 * uses three times read as kept alive by tests.
	 */
	let bucket;
	if (internalUses > 0) {
		/*
		 * Not dead, just wider than it needs to be — and only where *nothing*
		 * else names it. A method its own class uses and a test exercises
		 * directly is an ordinary service under test, and telling anyone to
		 * narrow that one is advice that breaks the test to satisfy a gate.
		 * Only a method can be narrowed in place, so types stay out of it.
		 */
		if (testUses > 0) continue;
		if (declaration.kind !== 'method' || !declaration.visible) continue;
		bucket = 'internal';
	} else if (testUses === 0 && !declaration.visible && declaration.kind === 'method') {
		// A private method nothing calls is SpotBugs' UPM_UNCALLED_PRIVATE_METHOD,
		// which already fails the build. Two gates on one finding just means two
		// places to silence it.
		continue;
	} else if (testUses > 0) {
		/*
		 * An entity's accessors are not production API and were never meant to
		 * be: production writes through a mapper and reads through queries,
		 * while a test asserts persisted state by calling the getter. That is
		 * the healthy shape, so "only tests" says nothing about an @Entity
		 * member. A setter nothing calls *at all* is still dead weight, which
		 * is why this exemption is scoped to this bucket alone.
		 */
		if (entityFiles.has(file)) continue;
		bucket = 'testOnly';
	} else bucket = 'dead';

	if (declaration.waiver) {
		waived.push({ ...declaration, bucket });
		continue;
	}
	findings[bucket].push(declaration);
}

const total = findings.dead.length + findings.testOnly.length + findings.internal.length;

if (total === 0) {
	console.log(
		`Java caller check OK: ${declarations.length} declaration(s) in main sources, ` +
			`every one reachable${waived.length > 0 ? `, ${waived.length} waived by @callerless` : ''}.`
	);
	process.exit(0);
}

const SECTIONS = [
	[
		'dead',
		'nothing names these, tests included — delete them',
		'Deleting is the default. If it has to stay, say why in a `@callerless` comment.'
	],
	[
		'testOnly',
		'only tests name these — the test is what keeps them alive',
		'A test of unused code is not coverage, it is what makes the code look used. Delete both, or record why the seam has to exist.'
	],
	[
		'internal',
		'only their own file names these — not dead, too visible',
		'Narrow to `private`. The caller is in the same class, and a wider signature promises a contract nobody asked for.'
	]
];

console.error('Java declarations in main sources that no production code calls:\n');
for (const [bucket, heading, advice] of SECTIONS) {
	const hits = findings[bucket];
	if (hits.length === 0) continue;
	console.error(`  ${heading} (${hits.length}):`);
	for (const hit of hits) console.error(`      ${hit.file}:${hit.line}  ${hit.name}`);
	console.error(`    ${advice}\n`);
}
console.error(
	'A deliberate exception carries the reason next to the code:\n' +
		'    // @callerless Kept because <reason>.'
);
process.exitCode = 1;
