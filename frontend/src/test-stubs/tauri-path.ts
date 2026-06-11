export async function join(...segments: string[]): Promise<string> {
	return segments.filter(Boolean).join('/');
}

export async function appLocalDataDir(): Promise<string> {
	return '/tmp/test-local-data';
}

export async function resolveResource(resourcePath: string): Promise<string> {
	return `/tmp/test-resources/${resourcePath}`;
}
