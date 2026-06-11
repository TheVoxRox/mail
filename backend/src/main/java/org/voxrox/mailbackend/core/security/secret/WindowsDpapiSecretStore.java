package org.voxrox.mailbackend.core.security.secret;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * {@link SecretStore} backed by the Windows Data Protection API (DPAPI).
 *
 * <p>
 * {@code CryptProtectData} / {@code CryptUnprotectData} (Crypt32.dll) encrypt a
 * blob with a key derived from the logged-in user's credentials and managed by
 * Windows — the key is never exposed to this process. We use <b>user scope</b>
 * (no {@code CRYPTPROTECT_LOCAL_MACHINE}) so a {@code crypto.bin} copied to a
 * different user account or machine cannot be unprotected, plus an app-specific
 * <i>entropy</i> blob so another process running as the same user cannot
 * unprotect it without knowing that constant. This is the same primitive Chrome
 * used for its password store; it does not defend against malware running <i>as
 * the same user</i> (that is out of the threat model).
 *
 * <p>
 * Pure Java 25 FFM ({@code java.lang.foreign}) — no JNI, no bundled native
 * library. The jpackage launcher already passes
 * {@code --enable-native-access=ALL-UNNAMED}. This class is only loaded on
 * Windows (via {@link SecretStore#forCurrentOs()}); its static initialiser
 * resolves Crypt32/Kernel32 and would fail on other platforms, which is why it
 * must never be referenced off-Windows.
 */
public final class WindowsDpapiSecretStore implements SecretStore {

    /** Do not prompt the user under any circumstance. */
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    /**
     * App-specific secondary entropy (RFC-style domain separation). Mixed into the
     * DPAPI key so a sibling process running as the same user cannot unprotect the
     * blob without it. Must stay stable — changing it makes existing
     * {@code crypto.bin} files unreadable.
     */
    private static final byte[] ENTROPY = "voxrox-mail-crypto-v1".getBytes(StandardCharsets.UTF_8);

    /**
     * {@code typedef struct { DWORD cbData; BYTE* pbData; } DATA_BLOB;} (x64: 4 + 4
     * pad + 8).
     */
    private static final StructLayout DATA_BLOB = MemoryLayout.structLayout(ValueLayout.JAVA_INT.withName("cbData"),
            MemoryLayout.paddingLayout(4), ValueLayout.ADDRESS.withName("pbData"));
    private static final VarHandle CB_DATA = DATA_BLOB.varHandle(PathElement.groupElement("cbData"));
    private static final VarHandle PB_DATA = DATA_BLOB.varHandle(PathElement.groupElement("pbData"));

    private static final MethodHandle CRYPT_PROTECT_DATA;
    private static final MethodHandle CRYPT_UNPROTECT_DATA;
    private static final MethodHandle LOCAL_FREE;

    static {
        Linker linker = Linker.nativeLinker();
        Arena library = Arena.global();
        SymbolLookup crypt32 = SymbolLookup.libraryLookup("Crypt32", library);
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("Kernel32", library);

        // BOOL CryptProtect/UnprotectData(DATA_BLOB*, LPCWSTR, DATA_BLOB*, PVOID,
        // PROMPT*, DWORD, DATA_BLOB*)
        FunctionDescriptor cryptDesc = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        CRYPT_PROTECT_DATA = linker
                .downcallHandle(crypt32.find("CryptProtectData").orElseThrow(missing("CryptProtectData")), cryptDesc);
        CRYPT_UNPROTECT_DATA = linker.downcallHandle(
                crypt32.find("CryptUnprotectData").orElseThrow(missing("CryptUnprotectData")), cryptDesc);
        LOCAL_FREE = linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(missing("LocalFree")),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    private static java.util.function.Supplier<RuntimeException> missing(String symbol) {
        return () -> new SecretStoreException("Windows symbol not found: " + symbol);
    }

    @Override
    public byte[] protect(byte[] plaintext) {
        return call(CRYPT_PROTECT_DATA, plaintext, "protect");
    }

    @Override
    public byte[] unprotect(byte[] blob) {
        return call(CRYPT_UNPROTECT_DATA, blob, "unprotect");
    }

    private static byte[] call(MethodHandle fn, byte[] input, String op) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = toBlob(arena, input);
            MemorySegment entropy = toBlob(arena, ENTROPY);
            MemorySegment out = arena.allocate(DATA_BLOB);

            int ok = (int) fn.invokeExact(in, MemorySegment.NULL, entropy, MemorySegment.NULL, MemorySegment.NULL,
                    CRYPTPROTECT_UI_FORBIDDEN, out);
            if (ok == 0) {
                throw new SecretStoreException("DPAPI " + op + " failed (CryptoAPI returned FALSE — likely a "
                        + "crypto.bin from a different Windows user/machine, or tampered data).");
            }

            int length = (int) CB_DATA.get(out, 0L);
            MemorySegment outData = (MemorySegment) PB_DATA.get(out, 0L);
            try {
                return outData.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(outData);
            }
        } catch (SecretStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw new SecretStoreException("DPAPI " + op + " native call failed", t);
        }
    }

    /** Allocates a {@code DATA_BLOB} pointing at a native copy of {@code data}. */
    private static MemorySegment toBlob(Arena arena, byte[] data) {
        MemorySegment buffer = arena.allocate(Math.max(1, data.length));
        MemorySegment.copy(data, 0, buffer, ValueLayout.JAVA_BYTE, 0, data.length);
        MemorySegment blob = arena.allocate(DATA_BLOB);
        CB_DATA.set(blob, 0L, data.length);
        PB_DATA.set(blob, 0L, buffer);
        return blob;
    }
}
