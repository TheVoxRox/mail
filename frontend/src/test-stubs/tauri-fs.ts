export async function exists(_path: string): Promise<boolean> {
	return false;
}

export async function readTextFile(_path: string): Promise<string> {
	throw new Error('tauri-fs stub: readTextFile not implemented');
}

export async function writeTextFile(_path: string, _contents: string): Promise<void> {
	throw new Error('tauri-fs stub: writeTextFile not implemented');
}
