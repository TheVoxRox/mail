#!/usr/bin/env bash
# check-translation-whitelist.sh
#
# Find Java source files that still contain Czech text (diacritics) and
# verify they are listed in backend/docs/translation-whitelist.txt.
#
# Modes:
#   --mode=report   List non-whitelisted offenders with counts. Exit 0.
#                   Use during the migration to track progress.
#   --mode=strict   Exit 1 if any non-whitelisted file contains diacritics.
#                   Wire into CI once Phase 6 of the translation migration
#                   is reached.
#
# Default mode is 'report'.
#
# Uses grep -F (fixed strings) rather than a Unicode character class because
# grep -P on some platforms (Git Bash / MSYS) interprets the class as a byte
# range and produces false positives such as '×' (U+00D7). Fixed-string match
# against the exact UTF-8 byte sequences is portable.
#
# Run from repo root:
#   backend/scripts/check-translation-whitelist.sh
#   backend/scripts/check-translation-whitelist.sh --mode=strict

set -euo pipefail

mode="report"
for arg in "$@"; do
  case "$arg" in
    --mode=report|--mode=strict)
      mode="${arg#--mode=}"
      ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Use --mode=report or --mode=strict." >&2
      exit 2
      ;;
  esac
done

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
whitelist_file="$repo_root/backend/docs/translation-whitelist.txt"

if [[ ! -f "$whitelist_file" ]]; then
  echo "Whitelist file not found: $whitelist_file" >&2
  exit 2
fi

# Build set of whitelisted paths (strip comments, blank lines, trailing reasons).
whitelist=()
while IFS= read -r line; do
  trimmed="${line%%#*}"
  trimmed="${trimmed%% --*}"
  trimmed="$(echo "$trimmed" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ -z "$trimmed" ]] && continue
  whitelist+=("$trimmed")
done < "$whitelist_file"

is_whitelisted() {
  local candidate="$1"
  local entry
  for entry in "${whitelist[@]}"; do
    if [[ "$candidate" == "$entry" ]]; then
      return 0
    fi
  done
  return 1
}

# Czech-specific diacritic characters. Matched as fixed strings (grep -F)
# against the exact UTF-8 byte sequence of each letter, so unrelated U+00xx
# punctuation that happens to share a leading byte (e.g. '×' U+00D7) does
# not produce false positives.
diacritic_chars=(á é í ó ú ý č ď ě ň ř š ť ů ž Á É Í Ó Ú Ý Č Ď Ě Ň Ř Š Ť Ů Ž)
grep_patterns=()
for ch in "${diacritic_chars[@]}"; do
  grep_patterns+=(-e "$ch")
done

scan_dir() {
  local dir="$1"
  local label="$2"
  local total_files=0
  local total_hits=0
  local offending_files=0
  local offending_hits=0

  while IFS= read -r -d '' file; do
    rel="${file#"$repo_root/"}"
    rel="${rel//\\//}"
    count=$(grep -cF "${grep_patterns[@]}" "$file" || true)
    [[ "$count" -eq 0 ]] && continue
    total_files=$((total_files + 1))
    total_hits=$((total_hits + count))
    if is_whitelisted "$rel"; then
      continue
    fi
    offending_files=$((offending_files + 1))
    offending_hits=$((offending_hits + count))
    printf '  %4d  %s\n' "$count" "$rel"
  done < <(find "$dir" -type f -name '*.java' -print0 | sort -z)

  echo
  printf '%s summary: %d file(s) with diacritics, %d line(s) total. Non-whitelisted: %d file(s), %d line(s).\n' \
    "$label" "$total_files" "$total_hits" "$offending_files" "$offending_hits"
  return "$offending_files"
}

main_dir="$repo_root/backend/src/main/java"
test_dir="$repo_root/backend/src/test/java"

main_offenders=0
test_offenders=0

if [[ -d "$main_dir" ]]; then
  echo "== backend/src/main/java =="
  set +e
  scan_dir "$main_dir" "main"
  main_offenders=$?
  set -e
  echo
fi

if [[ -d "$test_dir" ]]; then
  echo "== backend/src/test/java =="
  set +e
  scan_dir "$test_dir" "test"
  test_offenders=$?
  set -e
  echo
fi

total_offenders=$((main_offenders + test_offenders))

if [[ "$mode" == "strict" ]]; then
  if [[ "$total_offenders" -gt 0 ]]; then
    echo "FAIL (strict): $total_offenders non-whitelisted file(s) contain Czech diacritics." >&2
    echo "Either translate the file or add it to backend/docs/translation-whitelist.txt with a justification." >&2
    exit 1
  fi
  echo "OK (strict): all files with Czech diacritics are whitelisted."
else
  echo "Mode: report. Non-whitelisted offenders: $total_offenders (exit code suppressed)."
fi
