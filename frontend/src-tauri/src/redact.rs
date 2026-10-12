//! The safety net a log line passes on its way into the logs ZIP (logs.rs).
//!
//! The same rules as the backend's `DiagnosticLogRedactor`
//! (backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticLogRedactor.java),
//! so the ZIP saved without the backend carries what the diagnostic dump does:
//! the logs as they are on disk, folder names and paths included, less what no
//! log may hold. That is an address the app did not mask itself (text a server
//! wrote into an exception, such as an SMTP `550 <someone@example.org>`), a
//! token shape, and the session API key. Keep the two in step: a rule added to
//! one and not the other makes the two ZIPs answer to different policies. The
//! vectors in backend/src/test/resources/diagnostic/redaction-vectors.json run
//! against both, so a step out of line fails a build.
//!
//! Same rules means the same output, not similar patterns, and two things stand
//! between the Java patterns and Rust's regex. Rust's regex has no lookaround,
//! so the lookbehind and lookahead are done by hand around a match, the way
//! Java's backtracking arrives at the same match: an address whose end runs on
//! into a letter, digit, '_' or '-' falls back to the longest shorter domain
//! that does not, and a match refused at its start lets the next one begin
//! after its '@'. And Java reads `\w`, `\b` and `\s` as ASCII where Rust reads
//! them as Unicode, so the token patterns spell the ASCII classes out; Java's
//! `\b` with one exception, a non-spacing mark on a letter or digit, which it
//! counts as part of the word and `java_word_before` does too.

use std::sync::OnceLock;

use regex::Regex;

/// The characters RFC 5322 allows in a local part, Unicode letters included,
/// less '/' (which would take a whole path in front of an address) and '*'
/// (which keeps an address `LogMasker` already masked, `j***k@seznam.cz`, from
/// matching again).
const LOCAL_PART_CHARACTER: &str = r"\p{L}\p{N}._%+!#$&'=?^`{|}~-";

const TOKEN: &str = "[token]";
const API_KEY: &str = "[api-key]";

fn address() -> &'static Regex {
    static ADDRESS: OnceLock<Regex> = OnceLock::new();
    ADDRESS.get_or_init(|| {
        Regex::new(
            &(String::from("[")
                + LOCAL_PART_CHARACTER
                + r"]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.\p{L}{2,}"),
        )
        .expect("address pattern")
    })
}

/// A character no address starts right after: a '*', or one an address may
/// hold. The Java pattern's lookbehind; '*' first, because after the '-' that
/// ends `LOCAL_PART_CHARACTER` it would make a range.
fn blocks_a_start() -> &'static Regex {
    static BLOCKS: OnceLock<Regex> = OnceLock::new();
    BLOCKS.get_or_init(|| {
        Regex::new(&(String::from("^[*") + LOCAL_PART_CHARACTER + "]$")).expect("start pattern")
    })
}

/// A character an address may not run on into. The Java pattern's lookahead:
/// letters and digits by general category, which `char::is_alphanumeric` is
/// not (it also takes combining marks such as U+0345).
fn runs_on() -> &'static Regex {
    static RUNS_ON: OnceLock<Regex> = OnceLock::new();
    RUNS_ON.get_or_init(|| Regex::new(r"^[\p{L}\p{N}_-]$").expect("end pattern"))
}

/// A top-level domain: every letter there is, at least two.
fn top_level() -> &'static Regex {
    static TOP_LEVEL: OnceLock<Regex> = OnceLock::new();
    TOP_LEVEL.get_or_init(|| Regex::new(r"^\p{L}{2,}").expect("top-level pattern"))
}

/// A non-spacing mark (general category Mn).
fn non_spacing_mark() -> &'static Regex {
    static MARK: OnceLock<Regex> = OnceLock::new();
    MARK.get_or_init(|| Regex::new(r"^\p{Mn}$").expect("mark pattern"))
}

/// What Java's `Character.isLetterOrDigit` takes.
fn letter_or_digit() -> &'static Regex {
    static LETTER_OR_DIGIT: OnceLock<Regex> = OnceLock::new();
    LETTER_OR_DIGIT.get_or_init(|| Regex::new(r"^[\p{L}\p{Nd}]$").expect("letter pattern"))
}

/// The token shapes of frontend/scripts/lib/log-scan.mjs, in one pass and in
/// its order, with Java's ASCII `\w` ([0-9A-Za-z_]), `\b` and `\s`
/// ([\t\n\x0B\f\r ]). The Google refresh token is group 1: its lookbehind is
/// checked by hand, and so is the one way Java's `\b` is not ASCII.
fn tokens() -> &'static Regex {
    static TOKENS: OnceLock<Regex> = OnceLock::new();
    TOKENS.get_or_init(|| {
        Regex::new(
            &[
                r"(?-u:\b)ya29\.[0-9A-Za-z_-]{20,}",
                r"(1//[0-9A-Za-z_-]{20,})",
                r"(?-u:\b)eyJ[0-9A-Za-z_-]{8,}\.[0-9A-Za-z_-]{8,}\.[0-9A-Za-z_-]{8,}",
                r"(?-u:\b)Ew[A-Za-z0-9+/]{40,}={0,2}",
                r"(?-u:\b)Bearer[\t\n\x0B\x0C\r ]+[0-9A-Za-z_.~+/-]{16,}=*",
                r"(?-u:\b)dXNlcj[0-3][A-Za-z0-9+/]{16,}={0,2}",
                r#"(?-u:\b)(?:access_token|refresh_token|id_token|client_secret|code_verifier)["']?[\t\n\x0B\x0C\r ]*[=:][\t\n\x0B\x0C\r ]*["']?[0-9A-Za-z_.~+/-]{8,}"#,
            ]
            .join("|"),
        )
        .expect("token pattern")
    })
}

/// Replaces what no log may hold, line by line, and counts what it replaced.
pub struct Redactor {
    api_key: Option<String>,
    pub addresses: u64,
    pub tokens: u64,
    pub api_keys: u64,
}

impl Redactor {
    /// `api_key` is the session's key, when there is one to know.
    pub fn new(api_key: Option<String>) -> Self {
        Redactor {
            api_key: api_key.filter(|key| !key.trim().is_empty()),
            addresses: 0,
            tokens: 0,
            api_keys: 0,
        }
    }

    /// The line as the ZIP carries it.
    pub fn redact(&mut self, line: &str) -> String {
        let mut result = line.to_string();
        if let Some(key) = &self.api_key {
            if result.contains(key.as_str()) {
                self.api_keys += 1;
                result = result.replace(key.as_str(), API_KEY);
            }
        }

        let (result, tokens) = replace_tokens(&result);
        self.tokens += tokens;

        let (masked, addresses) = mask_addresses(&result);
        self.addresses += addresses;
        masked
    }
}

/// Every token shape in `text` replaced. A refresh token right after an ASCII
/// word character or a '/' is not one, and no other shape is one right after a
/// character Java's `\b` takes for part of a word. Either way no other shape
/// starts at the same character (they start with a letter, the refresh token
/// with '1'), so the search goes on from the next one, as Java's does.
fn replace_tokens(text: &str) -> (String, u64) {
    let mut result = String::with_capacity(text.len());
    let mut copied_up_to = 0;
    let mut count = 0;
    let mut from = 0;
    while let Some(caps) = tokens().captures_at(text, from) {
        let found = caps.get(0).expect("a match has its whole");
        let refused = if caps.get(1).is_some() {
            text[..found.start()]
                .chars()
                .next_back()
                .is_some_and(|c| c.is_ascii_alphanumeric() || c == '_' || c == '/')
        } else {
            java_word_before(text, found.start())
        };
        if refused {
            from = found.start() + 1;
            continue;
        }
        result.push_str(&text[copied_up_to..found.start()]);
        result.push_str(TOKEN);
        copied_up_to = found.end();
        count += 1;
        from = found.end();
    }
    result.push_str(&text[copied_up_to..]);
    (result, count)
}

/// Whether Java's `\b` takes the character before `index` for part of a word:
/// an ASCII word character, or a non-spacing mark on a letter or digit, which
/// `Pattern` counts with its base whatever `\w` means.
fn java_word_before(text: &str, index: usize) -> bool {
    let mut before = text[..index].chars().rev();
    match before.next() {
        Some(c) if c.is_ascii_alphanumeric() || c == '_' => true,
        Some(c) if is_one_of(non_spacing_mark(), c) => {
            for c in before {
                if is_one_of(letter_or_digit(), c) {
                    return true;
                }
                if !is_one_of(non_spacing_mark(), c) {
                    return false;
                }
            }
            false
        }
        _ => false,
    }
}

/// Every address in `text` that is not masked yet, masked as `LogMasker` masks
/// one, where Java's pattern finds it.
///
/// The regex finds the leftmost address with the longest domain that has the
/// shape of one. A start right after a '*' or a local-part character is
/// refused, which Java's lookbehind does to every start inside that local
/// part, so the next one to try is the first after its '@'. An end that runs on
/// into a letter, digit, '_' or '-' falls back to an earlier dot, latest first,
/// which is the order Java's backtracking gives the labels up in; with no dot
/// left to fall back to, the address is refused too.
fn mask_addresses(text: &str) -> (String, u64) {
    let mut result = String::with_capacity(text.len());
    let mut copied_up_to = 0;
    let mut count = 0;
    let mut from = 0;
    while let Some(found) = address().find_at(text, from) {
        let start = found.start();
        let at = start + found.as_str().find('@').expect("an address has an '@'");
        let refused_start = text[..start]
            .chars()
            .next_back()
            .is_some_and(|c| is_one_of(blocks_a_start(), c));
        let end = if refused_start {
            None
        } else {
            whole_address_end(text, at, found.end())
        };
        let Some(end) = end else {
            from = at + 1;
            continue;
        };
        result.push_str(&text[copied_up_to..start]);
        result.push_str(&mask_email(&text[start..end]));
        copied_up_to = end;
        count += 1;
        from = end;
    }
    result.push_str(&text[copied_up_to..]);
    (result, count)
}

/// Where an address whose shape ends at `end` really ends: there, unless it
/// runs on; else at the top-level domain after an earlier dot that does not.
/// The dots before the last are the only places Java's backtracking can end a
/// match: a shorter top-level domain leaves a letter after it.
fn whole_address_end(text: &str, at: usize, end: usize) -> Option<usize> {
    if !runs_on_at(text, end) {
        return Some(end);
    }
    let domain = &text[at + 1..end];
    for (dot, _) in domain.rmatch_indices('.').skip(1) {
        let label = at + 1 + dot + 1;
        if let Some(top) = top_level().find(&text[label..]) {
            let candidate = label + top.end();
            if !runs_on_at(text, candidate) {
                return Some(candidate);
            }
        }
    }
    None
}

fn runs_on_at(text: &str, index: usize) -> bool {
    text[index..]
        .chars()
        .next()
        .is_some_and(|c| is_one_of(runs_on(), c))
}

fn is_one_of(class: &Regex, c: char) -> bool {
    let mut buffer = [0u8; 4];
    class.is_match(c.encode_utf8(&mut buffer))
}

/// `jan.novak@seznam.cz` -> `j***k@seznam.cz`, as `LogMasker.maskEmail` writes it.
/// Counted in characters where Java counts UTF-16 units, which differ only for a
/// local part with a character outside the Basic Multilingual Plane.
fn mask_email(address: &str) -> String {
    let Some(at) = address.find('@') else {
        return "***".to_string();
    };
    let user: Vec<char> = address[..at].chars().collect();
    let domain = &address[at..];
    match user.len() {
        0 => format!("***{domain}"),
        1 => format!("{}***{domain}", user[0]),
        2 => format!("{}***{domain}", user[0]),
        n => format!("{}***{}{domain}", user[0], user[n - 1]),
    }
}

#[cfg(test)]
mod tests {
    use super::Redactor;

    const HEAD: &str = "2026-10-12T10:00:00.000+02:00  INFO 1 --- [mail] [main] o.v.m.X : ";

    fn redact(line: &str) -> String {
        Redactor::new(Some("canary-api-key-4417-abcdefghij".into()))
            .redact(&format!("{HEAD}{line}"))
    }

    #[test]
    fn a_line_that_keeps_the_rules_is_unchanged() {
        let line = "Initial sync of folder Lawyer/Divorce for j***k@seznam.cz in C:\\Users\\Lukas\\AppData";
        assert_eq!(redact(line), format!("{HEAD}{line}"));
    }

    #[test]
    fn an_address_a_server_wrote_is_masked_whole() {
        assert_eq!(
            redact("550 <jürgen.müller@example.de> unknown"),
            format!("{HEAD}550 <j***r@example.de> unknown")
        );
        assert_eq!(
            redact("550 o'brien.jan@firma.cz: unknown"),
            format!("{HEAD}550 o***n@firma.cz: unknown")
        );
        assert_eq!(
            redact("References <CAFoo=Xy+abc@mail.gmail.com>"),
            format!("{HEAD}References <C***c@mail.gmail.com>")
        );
        // An address the app already masked is left as it is.
        assert_eq!(
            redact("Starting account sync: j***k@seznam.cz"),
            format!("{HEAD}Starting account sync: j***k@seznam.cz")
        );
    }

    #[test]
    fn tokens_and_the_api_key_are_replaced() {
        let mut redactor = Redactor::new(Some("canary-api-key-4417-abcdefghij".into()));
        assert_eq!(
            redactor.redact("Authorization: Bearer abcdefghijklmnopqrstuvwxyz"),
            "Authorization: [token]"
        );
        assert_eq!(
            redactor
                .redact("got ya29.a0AfH6SMBabcdefghijklmnop and refresh 1//0abcdefghijklmnopqrst"),
            "got [token] and refresh [token]"
        );
        assert_eq!(
            redactor.redact("X-API-KEY canary-api-key-4417-abcdefghij rejected"),
            "X-API-KEY [api-key] rejected"
        );
        assert_eq!(
            (redactor.tokens, redactor.api_keys, redactor.addresses),
            (3, 1, 0)
        );
    }

    /// The vectors `DiagnosticLogRedactorTest` runs too: the same line comes out
    /// of both redactors the same, with the same replacements counted.
    #[test]
    fn the_shared_vectors_come_out_as_the_backend_writes_them() {
        let file: serde_json::Value = serde_json::from_str(include_str!(
            "../../../backend/src/test/resources/diagnostic/redaction-vectors.json"
        ))
        .expect("the vectors are JSON");
        let key = file["apiKey"].as_str().expect("apiKey").to_string();
        let vectors = file["vectors"].as_array().expect("vectors");
        assert!(!vectors.is_empty());
        for vector in vectors {
            let line = vector["line"].as_str().expect("line");
            let redacted = vector["redacted"].as_str().expect("redacted");
            let why = vector["why"].as_str().expect("why");
            let counts = ["addresses", "tokens", "apiKeys"]
                .map(|kind| vector[kind].as_u64().expect("a count"));
            let mut redactor = Redactor::new(Some(key.clone()));
            assert_eq!(redactor.redact(line), redacted, "{why}");
            assert_eq!(
                [redactor.addresses, redactor.tokens, redactor.api_keys],
                counts,
                "counts: {why}"
            );
        }
    }
}
