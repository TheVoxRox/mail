//! The logs when the backend cannot give them.
//!
//! The diagnostic bundle comes from the backend, so it is out of reach exactly
//! when the backend does not start. Two things cover that case from here. The
//! frontend reports what the sidecar wrote to stderr and how it exited, and
//! both go into `mail-frontend.log`, the log this process writes; before, they
//! went to the webview's console, which a release build keeps nowhere, so a
//! JVM that died before its own log was open left no trace at all. And
//! `export_logs` packs the logs folder into a ZIP in the user's Downloads
//! folder without asking the backend for anything: the files as they are on
//! disk, rotated `.gz` files unpacked, through the same safety net the
//! diagnostic dump uses (redact.rs).

use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use flate2::read::GzDecoder;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipWriter};

use crate::redact::Redactor;

/// Bytes of log text the ZIP takes at most, newest files first. Rotation keeps
/// the folder near 100 MB for `mail.log` and lets `audit.log` grow for a year.
const BUDGET_BYTES: u64 = 64 * 1024 * 1024;

const GZ: &str = ".gz";
/// A gzip header (10 bytes) and trailer (8 bytes) around an empty body.
const GZIP_MINIMUM_BYTES: u64 = 18;
/// The most deflate can shrink data by (zlib technical details: 1032:1).
const DEFLATE_MAX_RATIO: u64 = 1032;

/// stderr lines of the sidecar written per run of the app; a JVM that loops on
/// an error must not rotate the shell's own log away.
const MAX_SIDECAR_LINES: usize = 500;
const MAX_LINE_CHARS: usize = 2000;

static SIDECAR_LINES: AtomicUsize = AtomicUsize::new(0);

/// Where the logs folder is, handed over from `run` as managed state.
pub struct LogsDir(pub PathBuf);

/// One line the sidecar wrote to stderr.
#[tauri::command]
pub fn log_sidecar_stderr(line: String) {
    let count = SIDECAR_LINES.fetch_add(1, Ordering::Relaxed);
    if count < MAX_SIDECAR_LINES {
        log::warn!("Sidecar stderr: {}", one_line(&line));
    } else if count == MAX_SIDECAR_LINES {
        log::warn!(
            "Sidecar stderr: {MAX_SIDECAR_LINES} lines written this run, the rest are dropped"
        );
    }
}

/// How the sidecar ended: its exit code, or the signal that ended it, and
/// whether the app asked it to stop, so a crash reads apart from a quit.
#[tauri::command]
pub fn log_sidecar_exit(code: Option<i32>, signal: Option<i32>, requested: bool) {
    if requested {
        log::info!("Sidecar stopped as asked: code {code:?}, signal {signal:?}");
    } else {
        log::warn!("Sidecar exited on its own: code {code:?}, signal {signal:?}");
    }
}

/// Packs the logs folder into a ZIP in the Downloads folder (the data root when
/// there is none) and returns the ZIP's path.
///
/// Async, with the copying on a blocking thread: a plain command runs on the
/// main thread, and tens of megabytes of logs would freeze the window, the
/// screen reader's view of it included, for as long as they take.
#[tauri::command]
pub async fn export_logs(logs: tauri::State<'_, LogsDir>) -> Result<String, String> {
    let logs_dir = logs.0.clone();
    let destination = dirs::download_dir()
        .or_else(|| logs_dir.parent().map(Path::to_path_buf))
        .ok_or_else(|| "No folder to save the logs in".to_string())?;
    let written = tauri::async_runtime::spawn_blocking(move || {
        write_logs_zip(
            &logs_dir,
            &destination,
            SystemTime::now(),
            env!("CARGO_PKG_VERSION"),
            BUDGET_BYTES,
        )
    })
    .await
    .map_err(|err| err.to_string())?;
    let zip = written.map_err(|err| {
        log::error!("Saving the logs failed: {err}");
        err.to_string()
    })?;
    log::info!("Logs saved to {}", zip.display());
    Ok(zip.display().to_string())
}

/// The work of `export_logs`, apart from Tauri state so it can be tested.
fn write_logs_zip(
    logs_dir: &Path,
    destination: &Path,
    now: SystemTime,
    version: &str,
    budget: u64,
) -> io::Result<PathBuf> {
    let stamp = utc_stamp(now);
    let (file, path) = create_unique(destination, &format!("voxrox-mail-logs-{stamp}"))?;
    // A ZIP that fails halfway must not stay in Downloads looking like a result.
    fill_zip(file, logs_dir, now, version, budget).inspect_err(|_| {
        let _ = fs::remove_file(&path);
    })?;
    Ok(path)
}

fn fill_zip(
    file: File,
    logs_dir: &Path,
    now: SystemTime,
    version: &str,
    budget: u64,
) -> io::Result<()> {
    let mut zip = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    let mut redactor = Redactor::new(logs_dir.parent().and_then(session_api_key));

    let mut included = Vec::new();
    let mut left_out = Vec::new();
    let mut remaining = budget;
    let mut used_names = std::collections::HashSet::new();
    for (name, file_path, size) in newest_first(logs_dir)? {
        if remaining == 0 {
            left_out.push(format!("{name} (over the budget)"));
            continue;
        }
        let gzipped = name.ends_with(GZ);
        // Opened before its entry starts: an unreadable file must not leave an
        // empty entry that reads as an empty log.
        let reader = match open_text(&file_path, gzipped) {
            Ok(reader) => reader,
            Err(err) => {
                left_out.push(format!("{name} (unreadable: {err})"));
                continue;
            }
        };
        // A `.gz` goes in unpacked under its name without the suffix, unless a
        // plain file already took that name: one ZIP cannot hold the same entry
        // twice, and a duplicate would fail the whole save.
        let unpacked = name.strip_suffix(GZ).unwrap_or(&name);
        let entry = if used_names.insert(unpacked.to_string()) {
            unpacked.to_string()
        } else {
            name.clone()
        };
        zip.start_file(format!("logs/{entry}"), options)
            .map_err(io::Error::other)?;
        let estimate = if gzipped {
            gzip_text_size(&file_path)
        } else {
            Some(size)
        };
        let copy = copy_redacted(reader, &mut zip, &mut redactor, estimate, remaining)?;
        remaining -= copy.written;
        let state = match (&copy.failure, copy.truncated) {
            (Some(err), _) => format!(", unreadable after that: {err}"),
            (None, true) => ", oldest lines left out for the budget".to_string(),
            (None, false) => String::new(),
        };
        included.push(format!("{entry} ({} bytes{state})", copy.written));
        if copy.truncated {
            remaining = 0;
        }
    }

    zip.start_file("about.txt", options)
        .map_err(io::Error::other)?;
    let mut about = format!(
        "VoxRox Mail {version}, logs saved {} UTC without the backend.\n\n\
         The files are as they are on disk, rotated .gz files unpacked, except \
         that {} unmasked address(es), {} token(s) and {} copy(ies) of the \
         session API key were replaced.\n\nIncluded:\n",
        utc_text(now),
        redactor.addresses,
        redactor.tokens,
        redactor.api_keys
    );
    for line in &included {
        about.push_str(&format!("  {line}\n"));
    }
    if !left_out.is_empty() {
        about.push_str(&format!(
            "\nLeft out (over {budget} bytes of text, or unreadable):\n"
        ));
        for line in &left_out {
            about.push_str(&format!("  {line}\n"));
        }
    }
    zip.write_all(about.as_bytes())?;
    zip.finish().map_err(io::Error::other)?;
    Ok(())
}

/// The API key of the backend's last session, from `session.json` next to the
/// logs folder, so a line that carries it is caught. A key from an earlier
/// session is not known any more; none is known to reach a log.
fn session_api_key(data_root: &Path) -> Option<String> {
    let text = fs::read_to_string(data_root.join("session.json")).ok()?;
    let session: serde_json::Value = serde_json::from_str(&text).ok()?;
    session.get("apiKey")?.as_str().map(str::to_string)
}

fn open_text(path: &Path, gzipped: bool) -> io::Result<Box<dyn BufRead>> {
    let file = File::open(path)?;
    Ok(if gzipped {
        Box::new(BufReader::new(GzDecoder::new(file)))
    } else {
        Box::new(BufReader::new(file))
    })
}

struct CopyResult {
    written: u64,
    truncated: bool,
    failure: Option<io::Error>,
}

/// Copies one file's lines through the redactor. A file whose text is larger
/// than the budget keeps its newest lines: as many of its oldest are skipped as
/// make it fit, with a tenth of the budget to spare for a line coming out
/// longer than it went in, and what still does not fit is cut at the end. A
/// read that fails ends the entry where it got to (a `.gz` Logback is still
/// compressing ends early) and names the failure; a write that fails fails the
/// whole ZIP.
fn copy_redacted(
    mut reader: Box<dyn BufRead>,
    zip: &mut ZipWriter<File>,
    redactor: &mut Redactor,
    estimate: Option<u64>,
    budget: u64,
) -> io::Result<CopyResult> {
    let mut to_skip = match estimate {
        Some(size) if size > budget => size - (budget - budget / 10),
        _ => 0,
    };
    let mut truncated = to_skip > 0;
    let mut written = 0u64;
    let mut failure = None;
    let mut raw = Vec::new();
    loop {
        raw.clear();
        match reader.read_until(b'\n', &mut raw) {
            Ok(0) => break,
            Ok(read) => {
                if to_skip > 0 {
                    to_skip = to_skip.saturating_sub(read as u64);
                    continue;
                }
                let text = String::from_utf8_lossy(&raw);
                let line = text.trim_end_matches(['\n', '\r']);
                let out = redactor.redact(line) + "\n";
                if written + out.len() as u64 > budget {
                    truncated = true;
                    break;
                }
                zip.write_all(out.as_bytes())?;
                written += out.len() as u64;
            }
            Err(err) => {
                failure = Some(err);
                break;
            }
        }
    }
    Ok(CopyResult {
        written,
        truncated,
        failure,
    })
}

/// The size of a `.gz` file's text: the ISIZE field its trailer ends with (RFC
/// 1952), the uncompressed length mod 2^32 of the one member Logback writes.
/// A file Logback is still compressing has no trailer yet, and its last four
/// bytes are compressed data: a length deflate could not have produced from
/// this many bytes is that, not a length, and reads as unknown.
fn gzip_text_size(path: &Path) -> Option<u64> {
    let mut file = File::open(path).ok()?;
    let size = file.metadata().ok()?.len();
    if size < GZIP_MINIMUM_BYTES {
        return None;
    }
    file.seek(SeekFrom::End(-4)).ok()?;
    let mut trailer = [0u8; 4];
    file.read_exact(&mut trailer).ok()?;
    let isize = u64::from(u32::from_le_bytes(trailer));
    (isize >= size / 2 && isize <= size * DEFLATE_MAX_RATIO).then_some(isize)
}

/// The regular files of the logs folder, newest first. A folder that is not
/// there yet has no files.
fn newest_first(logs_dir: &Path) -> io::Result<Vec<(String, PathBuf, u64)>> {
    let entries = match fs::read_dir(logs_dir) {
        Ok(entries) => entries,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(err) => return Err(err),
    };
    let mut files = Vec::new();
    for entry in entries.flatten() {
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if !metadata.is_file() {
            continue;
        }
        let modified = metadata.modified().unwrap_or(UNIX_EPOCH);
        files.push((
            modified,
            entry.file_name().to_string_lossy().into_owned(),
            entry.path(),
            metadata.len(),
        ));
    }
    files.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
    Ok(files
        .into_iter()
        .map(|(_, name, path, size)| (name, path, size))
        .collect())
}

/// A new file `<stem>.zip`, or `<stem>-2.zip` and on when two saves land in the
/// same second; never one that is already there.
fn create_unique(destination: &Path, stem: &str) -> io::Result<(File, PathBuf)> {
    fs::create_dir_all(destination)?;
    for attempt in 1..=99 {
        let name = if attempt == 1 {
            format!("{stem}.zip")
        } else {
            format!("{stem}-{attempt}.zip")
        };
        let path = destination.join(name);
        match OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(file) => return Ok((file, path)),
            Err(err) if err.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(err) => return Err(err),
        }
    }
    Err(io::Error::new(
        io::ErrorKind::AlreadyExists,
        "Too many saved logs with the same name",
    ))
}

/// One line, at most `MAX_LINE_CHARS` characters: the log's own lines must not
/// be forged by a line break in what the sidecar wrote.
fn one_line(text: &str) -> String {
    let cleaned: String = text.trim_end().replace(['\r', '\n'], " ");
    if cleaned.chars().count() > MAX_LINE_CHARS {
        let cut: String = cleaned.chars().take(MAX_LINE_CHARS).collect();
        format!("{cut}...")
    } else {
        cleaned
    }
}

/// `20261012-083000`, for a file name.
fn utc_stamp(time: SystemTime) -> String {
    let (year, month, day, hour, minute, second) = utc_parts(time);
    format!("{year:04}{month:02}{day:02}-{hour:02}{minute:02}{second:02}")
}

/// `2026-10-12 08:30:00`, for a person.
fn utc_text(time: SystemTime) -> String {
    let (year, month, day, hour, minute, second) = utc_parts(time);
    format!("{year:04}-{month:02}-{day:02} {hour:02}:{minute:02}:{second:02}")
}

/// The UTC calendar date and time of `time`, by Howard Hinnant's
/// days-to-civil algorithm, so no date crate is needed for one file name.
fn utc_parts(time: SystemTime) -> (i64, u32, u32, u32, u32, u32) {
    let seconds = time
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0) as i64;
    let days = seconds.div_euclid(86_400);
    let of_day = seconds.rem_euclid(86_400);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let day_of_era = z.rem_euclid(146_097);
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_index = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * month_index + 2) / 5 + 1) as u32;
    let month = if month_index < 10 {
        month_index + 3
    } else {
        month_index - 9
    } as u32;
    let year = year_of_era + era * 400 + i64::from(month <= 2);
    (
        year,
        month,
        day,
        (of_day / 3_600) as u32,
        (of_day % 3_600 / 60) as u32,
        (of_day % 60) as u32,
    )
}

#[cfg(test)]
mod tests {
    use super::{one_line, utc_stamp, utc_text, write_logs_zip};
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::fs;
    use std::io::{Read, Write};
    use std::path::PathBuf;
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    fn temp_dir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("voxrox-logs-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn dates_are_utc_calendar_dates() {
        let time = UNIX_EPOCH + Duration::from_secs(1_791_793_800); // 2026-10-12 08:30:00 UTC
        assert_eq!(utc_stamp(time), "20261012-083000");
        assert_eq!(utc_text(time), "2026-10-12 08:30:00");
        // A leap day, and the first second of a year.
        assert_eq!(
            utc_text(UNIX_EPOCH + Duration::from_secs(1_709_164_800)),
            "2024-02-29 00:00:00"
        );
        assert_eq!(
            utc_text(UNIX_EPOCH + Duration::from_secs(1_767_225_600)),
            "2026-01-01 00:00:00"
        );
    }

    #[test]
    fn a_sidecar_line_stays_one_bounded_line() {
        assert_eq!(
            one_line("Error: boom\r\nforged line\n"),
            "Error: boom  forged line"
        );
        let long = "x".repeat(5_000);
        assert_eq!(one_line(&long).chars().count(), 2_000 + 3);
    }

    fn gzip(path: &std::path::Path, text: &str) {
        let mut encoder = GzEncoder::new(fs::File::create(path).unwrap(), Compression::default());
        encoder.write_all(text.as_bytes()).unwrap();
        encoder.finish().unwrap();
    }

    fn entry(archive: &mut zip::ZipArchive<fs::File>, name: &str) -> String {
        let mut text = String::new();
        archive
            .by_name(name)
            .unwrap()
            .read_to_string(&mut text)
            .unwrap();
        text
    }

    #[test]
    fn the_zip_carries_the_logs_as_on_disk_less_what_no_log_may_hold() {
        let root = temp_dir("export");
        let logs = root.join("logs");
        fs::create_dir_all(&logs).unwrap();
        fs::write(
            root.join("session.json"),
            r#"{ "apiKey": "canary-api-key-4417-abcdefghij" }"#,
        )
        .unwrap();
        fs::write(
            logs.join("mail.log"),
            "line naming the folder Lawyer/Divorce\n550 <jane.novak@example.org> unknown\nkey canary-api-key-4417-abcdefghij\n",
        )
        .unwrap();
        gzip(
            &logs.join("mail.log.2026-10-11.0.gz"),
            "older line with Bearer abcdefghijklmnopqrstuvwxyz\n",
        );
        let downloads = root.join("Downloads");
        let now = UNIX_EPOCH + Duration::from_secs(1_791_793_800);

        let first = write_logs_zip(&logs, &downloads, now, "9.8.7", 1 << 20).unwrap();
        let second = write_logs_zip(&logs, &downloads, now, "9.8.7", 1 << 20).unwrap();

        assert_eq!(
            first.file_name().unwrap(),
            "voxrox-mail-logs-20261012-083000.zip"
        );
        assert_eq!(
            second.file_name().unwrap(),
            "voxrox-mail-logs-20261012-083000-2.zip"
        );
        let mut archive = zip::ZipArchive::new(fs::File::open(&first).unwrap()).unwrap();
        assert_eq!(
            entry(&mut archive, "logs/mail.log"),
            "line naming the folder Lawyer/Divorce\n550 <j***k@example.org> unknown\nkey [api-key]\n"
        );
        // Rotated files go in unpacked, under the name without .gz.
        assert_eq!(
            entry(&mut archive, "logs/mail.log.2026-10-11.0"),
            "older line with [token]\n"
        );
        let about = entry(&mut archive, "about.txt");
        assert!(about.starts_with(
            "VoxRox Mail 9.8.7, logs saved 2026-10-12 08:30:00 UTC without the backend."
        ));
        assert!(about.contains("1 unmasked address(es), 1 token(s) and 1 copy(ies)"));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_file_over_the_budget_keeps_its_newest_lines() {
        let root = temp_dir("budget");
        let logs = root.join("logs");
        fs::create_dir_all(&logs).unwrap();
        fs::write(
            logs.join("mail.log"),
            "older 1\nolder 2\nolder 3\nolder 4\n",
        )
        .unwrap();

        // 32 bytes of text into 24: with a tenth spare, its newest two lines.
        let zip = write_logs_zip(&logs, &root, SystemTime::now(), "9.8.7", 24).unwrap();

        let mut archive = zip::ZipArchive::new(fs::File::open(&zip).unwrap()).unwrap();
        assert_eq!(entry(&mut archive, "logs/mail.log"), "older 3\nolder 4\n");
        assert!(entry(&mut archive, "about.txt").contains("oldest lines left out"));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_missing_logs_folder_gives_a_zip_with_only_its_note() {
        let root = temp_dir("missing");
        let zip = write_logs_zip(
            &root.join("logs"),
            &root,
            SystemTime::now(),
            "9.8.7",
            1 << 20,
        )
        .unwrap();

        let archive = zip::ZipArchive::new(fs::File::open(&zip).unwrap()).unwrap();
        assert_eq!(archive.file_names().collect::<Vec<_>>(), vec!["about.txt"]);
        let _ = fs::remove_dir_all(&root);
    }
}
