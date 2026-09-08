/**
 * The documents that record what the repository used to be: both changelogs and
 * the archived todo.
 *
 * Shared because two gates have to agree about them and neither can tell that
 * the other disagreed. `check:refs` skips them so a changelog entry may name
 * `V2__add_threading.sql` after the file is gone; `check:npm-callers` skips
 * them so a retired `npm run` named in a changelog does not read as a caller.
 * The lists were written twice, and the day a module changelog is added the
 * gate that misses it starts believing the wrong thing in silence.
 *
 * Documents that are historical for one gate only stay in that gate's own list
 * — an audit snapshot names dead paths but no npm scripts.
 */
export const RECORDS_OF_THE_PAST = ['CHANGELOG.md', 'backend/CHANGELOG.md', 'todo-archive.md'];
