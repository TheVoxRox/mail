const ADDRESS_SEPARATOR = /[,;\n]+/;

export function parseAddressList(value: string): string[] {
	return value
		.split(ADDRESS_SEPARATOR)
		.map((item) => item.trim())
		.filter(Boolean);
}

export function serializeAddressList(addresses: string[]): string {
	return addresses.join(', ');
}

export function isValidEmailAddress(value: string): boolean {
	return /^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/.test(value.trim());
}

export function invalidAddressList(value: string): string[] {
	return parseAddressList(value).filter((address) => !isValidEmailAddress(address));
}
