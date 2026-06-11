export function resolve(path: string, params?: Record<string, string>): string {
	if (!params) return path;
	return Object.entries(params).reduce(
		(acc, [key, value]) => acc.replaceAll(`[${key}]`, value),
		path
	);
}

export const base = '';
export const assets = '';
