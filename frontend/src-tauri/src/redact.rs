//! The safety net a log line passes on its way into the logs ZIP (logs.rs).
//!
//! The same rules as the backend's `DiagnosticLogRedactor`
//! (backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticLogRedactor.java),
//! so the ZIP saved without the backend carries what the diagnostic dump does:
//! the logs as they are on disk, folder names and paths included, less what no
//! log may hold. That is an address the app did not mask itself (text a server
//! wrote into an exception, such as an SMTP `550 <someone@example.org>`), a
//! token shape, and the session API key. Keep the two in step: a rule added to
//! one and not the other makes the two ZIPs answer to different policies.
//!
//! Rust's regex has no lookaround, so what the Java patterns say with
//! lookbehind and lookahead is checked here on the characters around a match.

use std::sync::OnceLock;

use regex::{Captures, Regex};

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

/// The token shapes of frontend/scripts/lib/log-scan.mjs, in one pass, but for
/// the Google refresh token, which needs the character in front of it.
fn tokens() -> &'static Regex {
    static TOKENS: OnceLock<Regex> = OnceLock::new();
    TOKENS.get_or_init(|| {
        Regex::new(
            &[
                r"\bya29\.[\w-]{20,}",
                r"\beyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]{8,}",
                r"\bEw[A-Za-z0-9+/]{40,}={0,2}",
                r"\bBearer\s+[\w.~+/-]{16,}=*",
                r"\bdXNlcj[0-3][A-Za-z0-9+/]{16,}={0,2}",
                r#"\b(?:access_token|refresh_token|id_token|client_secret|code_verifier)["']?\s*[=:]\s*["']?[\w.~+/-]{8,}"#,
            ]
            .join("|"),
        )
        .expect("token pattern")
    })
}

fn refresh_token() -> &'static Regex {
    static REFRESH: OnceLock<Regex> = OnceLock::new();
    REFRESH.get_or_init(|| Regex::new(r"(^|[^\w/])1//[\w-]{20,}").expect("refresh token pattern"))
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

        let mut replaced = 0u64;
        result = tokens()
            .replace_all(&result, |_: &Captures| {
                replaced += 1;
                TOKEN.to_string()
            })
            .into_owned();
        result = refresh_token()
            .replace_all(&result, |caps: &Captures| {
                replaced += 1;
                format!("{}{TOKEN}", &caps[1])
            })
            .into_owned();
        self.tokens += replaced;

        let (masked, count) = mask_addresses(&result);
        self.addresses += count;
        masked
    }
}

/// Every address in `text` that is not masked yet, masked as `LogMasker` masks
/// one. A match right after a '*' is the tail of a masked address, and one
/// followed by a letter, digit, '_' or '-' is not a whole address: both are
/// left as they are, which is what the Java pattern's lookaround does.
fn mask_addresses(text: &str) -> (String, u64) {
    let mut result = String::with_capacity(text.len());
    let mut copied_up_to = 0;
    let mut count = 0;
    for found in address().find_iter(text) {
        let before = text[..found.start()].chars().next_back();
        let after = text[found.end()..].chars().next();
        if before == Some('*') || after.is_some_and(|c| c.is_alphanumeric() || c == '_' || c == '-')
        {
            continue;
        }
        result.push_str(&text[copied_up_to..found.start()]);
        result.push_str(&mask_email(found.as_str()));
        copied_up_to = found.end();
        count += 1;
    }
    result.push_str(&text[copied_up_to..]);
    (result, count)
}

/// `jan.novak@seznam.cz` -> `j***k@seznam.cz`, as `LogMasker.maskEmail` writes it.
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
}
