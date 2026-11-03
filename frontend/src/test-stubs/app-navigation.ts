/*
 * `$app/navigation` under vitest, so that a mounted route component resolves
 * it. Navigating is the framework's job and none runs here: a test that cares
 * where a component goes mocks `goto` and asserts on the mock.
 */
export async function goto(): Promise<void> {}
