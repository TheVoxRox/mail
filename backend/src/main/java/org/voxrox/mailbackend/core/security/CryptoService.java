package org.voxrox.mailbackend.core.security;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * AES/GCM credential encryption with a per-account derived key.
 *
 * <p>
 * Single concrete class — no interface. Mockito mocks concrete classes fine,
 * and there is no alternative implementation in this project (PBKDF2 + AES/GCM
 * is part of the on-disk ciphertext format).
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /*
     * Crypto format constants. These are not configuration — every stored
     * ciphertext is tied to this exact AES/GCM layout. Changing any of them would
     * silently break decryption with no migration path. Treat them as part of the
     * on-disk data format.
     */
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BIT = 128;
    private static final int GCM_IV_LENGTH_BYTE = 12;
    private static final int DERIVED_KEY_LENGTH_BIT = 256;

    private final Map<Long, SecretKey> keyCache;
    private final CryptoProperties cryptoProperties;
    private final StartupTimingService startupTimingService;
    private final AtomicBoolean selfTestPassed = new AtomicBoolean(false);
    private final Object selfTestLock = new Object();

    private char[] mainSecret;
    private byte[] globalSalt;

    public CryptoService(CryptoProperties cryptoProperties, StartupTimingService startupTimingService) {
        this.cryptoProperties = cryptoProperties;
        this.startupTimingService = startupTimingService;
        LinkedHashMap<Long, SecretKey> lruMap = new LinkedHashMap<>(cryptoProperties.maxCacheSize() + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, SecretKey> eldest) {
                return size() > cryptoProperties.maxCacheSize();
            }
        };
        this.keyCache = Collections.synchronizedMap(lruMap);
    }

    @PostConstruct
    public void init() {
        long started = startupTimingService.start();
        this.mainSecret = cryptoProperties.key().toCharArray();
        this.globalSalt = cryptoProperties.salt().getBytes(StandardCharsets.UTF_8);
        log.info("{} Crypto subsystem ready. Application start sped up (lazy self-test).", LogCategory.SECURITY);
        startupTimingService.record("crypto.service-init", started);
    }

    /**
     * Warms the one-time self-test off the request path.
     *
     * <p>
     * The self-test (a PBKDF2 key derivation plus an AES/GCM round trip) is
     * otherwise triggered lazily by the first {@link #encrypt}/{@link #decrypt} —
     * in practice the first OAuth2 login, where it added a few seconds to the
     * callback while the refresh token was encrypted. A slow loopback callback
     * invites the external browser to retry the request, producing a duplicate
     * callback and a misleading {@code authorization_request_not_found} error page
     * even though the login succeeded. Running the self-test on
     * {@link ApplicationReadyEvent}, off the boot thread on
     * {@code mailEventExecutor}, keeps the lazy-init cold-start speed-up while
     * making the first real login fast. {@link #ensureSelfTest()} is idempotent and
     * thread-safe, so a concurrent first {@code encrypt} simply no-ops.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async("mailEventExecutor")
    public void warmUpSelfTest() {
        ensureSelfTest();
    }

    /** Pass-through contract: blank/null input maps to {@code null} ciphertext. */
    public @Nullable String encrypt(@Nullable String plainText, Long accountId) {
        if (plainText == null || plainText.isBlank())
            return null;

        ensureSelfTest();

        try {
            SecretKey accountKey = getOrDeriveKey(accountId);
            return encryptInternal(plainText, accountId, accountKey);
        } catch (Exception e) {
            log.error("{} Encryption error for account {}", LogCategory.SECURITY, accountId, e);
            throw new CryptoOperationException("Encryption failed", e);
        }
    }

    /** Pass-through contract: blank/null ciphertext maps to {@code null}. */
    public @Nullable String decrypt(@Nullable String encryptedBase64, Long accountId) {
        if (encryptedBase64 == null || encryptedBase64.isBlank())
            return null;

        ensureSelfTest();

        try {
            return decryptInternal(encryptedBase64, accountId, getOrDeriveKey(accountId));
        } catch (AEADBadTagException e) {
            /*
             * GCM tag failure = ciphertext tampering or wrong key/AAD. Always
             * security-relevant — escalates to CRITICAL.
             */
            log.warn("{} Data integrity failure for account {}", LogCategory.SECURITY, accountId, e);
            AuditLog.critical("decrypt", "account=" + accountId, "aead_tag_mismatch");
            throw new CryptoOperationException("Data cannot be decrypted - integrity invalid", e);
        } catch (Exception e) {
            log.error("{} General decryption error for account {}", LogCategory.SECURITY, accountId, e);
            AuditLog.failure("decrypt", "account=" + accountId, e.getClass().getSimpleName());
            throw new CryptoOperationException("Decryption failed", e);
        }
    }

    public void evictCache(Long accountId) {
        keyCache.remove(accountId);
    }

    private String encryptInternal(String plainText, Long accountId, SecretKey key) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LENGTH_BYTE];
        SECURE_RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv));
        cipher.updateAAD(ByteBuffer.allocate(8).putLong(accountId).array());

        byte[] encryptedText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] combined = ByteBuffer.allocate(iv.length + encryptedText.length).put(iv).put(encryptedText).array();

        return Base64.getEncoder().encodeToString(combined);
    }

    private String decryptInternal(String encryptedBase64, Long accountId, SecretKey key)
            throws GeneralSecurityException {
        byte[] combined = Base64.getDecoder().decode(encryptedBase64);
        ByteBuffer bb = ByteBuffer.wrap(combined);

        byte[] iv = new byte[GCM_IV_LENGTH_BYTE];
        bb.get(iv);
        byte[] encryptedText = new byte[bb.remaining()];
        bb.get(encryptedText);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv));
        cipher.updateAAD(ByteBuffer.allocate(8).putLong(accountId).array());

        return new String(cipher.doFinal(encryptedText), StandardCharsets.UTF_8);
    }

    private void ensureSelfTest() {
        if (!selfTestPassed.get()) {
            synchronized (selfTestLock) {
                if (!selfTestPassed.get()) {
                    performSelfTest();
                    selfTestPassed.set(true);
                }
            }
        }
    }

    private void performSelfTest() {
        try {
            log.debug("{} Running cryptographic self-test...", LogCategory.SECURITY);
            String testPlain = "encryption-integrity-check";
            SecretKey testKey = getOrDeriveKey(-1L);

            String encrypted = encryptInternal(testPlain, -1L, testKey);
            String decrypted = decryptInternal(encrypted, -1L, testKey);

            if (!testPlain.equals(decrypted)) {
                throw new IllegalStateException("Self-test failed: decrypted text does not match.");
            }
            log.info("{} Cryptographic self-test completed successfully.", LogCategory.SECURITY);
        } catch (Exception e) {
            log.error("{} CRITICAL ERROR: crypto subsystem is non-functional!", LogCategory.SECURITY, e);
            throw new IllegalStateException("Crypto self-test failed", e);
        }
    }

    private SecretKey getOrDeriveKey(Long accountId) {
        SecretKey cachedKey = keyCache.get(accountId);
        if (cachedKey != null)
            return cachedKey;

        synchronized (keyCache) {
            if (keyCache.containsKey(accountId))
                return keyCache.get(accountId);

            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] accountSpecificSalt = ByteBuffer.allocate(globalSalt.length + 8).put(globalSalt)
                        .putLong(accountId).array();

                KeySpec spec = new PBEKeySpec(mainSecret, accountSpecificSalt, cryptoProperties.pbkdf2Iterations(),
                        DERIVED_KEY_LENGTH_BIT);

                SecretKey derived = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
                keyCache.put(accountId, derived);
                return derived;
            } catch (Exception e) {
                log.error("{} Critical error while generating key for account {}", LogCategory.SECURITY, accountId, e);
                throw new CryptoOperationException("Critical error while generating key", e);
            }
        }
    }

    @PreDestroy
    public void cleanUp() {
        if (mainSecret != null)
            Arrays.fill(mainSecret, '\0');
        /*
         * Note on the cached SecretKeys: SecretKeySpec.getEncoded() returns a defensive
         * COPY, so "zeroing" it would only wipe the copy while the real key material
         * lives until GC — deliberately not attempted (it would be security theater).
         * Clearing the cache drops the references; the JVM heap is inside the process
         * boundary, which matches the threat model (DPAPI protects the at-rest secrets,
         * not process memory).
         */
        keyCache.clear();
    }
}
