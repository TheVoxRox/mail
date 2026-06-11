package org.voxrox.mailbackend.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class FileSystemService {
    private static final Logger log = LoggerFactory.getLogger(FileSystemService.class);

    private final StorageProperties storageProperties;
    private final boolean isWindows;

    public FileSystemService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    }

    public void writeAtomic(Path target, byte[] content) throws IOException {
        /*
         * Resolve an absolute path — a relative target could otherwise have a null
         * parent and the temp file would land in the default temp dir, which prevents
         * ATOMIC_MOVE (must be on the same filesystem/volume as the destination).
         */
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        Path fileName = absoluteTarget.getFileName();
        if (parent == null || fileName == null) {
            throw new IOException("Target path is not valid: " + target);
        }
        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile(parent, ".tmp-", fileName.toString());
        try {
            Files.write(temp, content);
            Files.move(temp, absoluteTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("{} Atomic write to {} failed: {}", LogCategory.STORAGE, absoluteTarget, e.getMessage());
            throw e;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Sets access permissions so that only the owner can read the file/ directory.
     *
     * On Windows we rely on NTFS inheritance from the user profile. Rewriting ACLs
     * via Java NIO can easily strip practical permissions in a jpackage/Tauri run
     * from the process that later uses the same directory for WebView data and
     * Tauri logs.
     */
    public void applyPrivatePermissions(Path path) {
        try {
            if (isWindows) {
                return;
            } else {
                applyPosixPermissions(path);
            }
        } catch (IOException | SecurityException e) {
            log.warn("{} Failed to enforce private permissions for {}: {}", LogCategory.SECURITY, path, e.getMessage());
        }
    }

    private void applyPosixPermissions(Path path) throws IOException {
        var perms = Files.isDirectory(path)
                ? PosixFilePermissions.fromString("rwx------")
                : PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(path, perms);
    }

    public Path getDataPath() {
        return storageProperties.getDataPath();
    }

    public Path getDbPath() {
        return storageProperties.getDbPath();
    }

    public Path getAttachmentsPath() {
        return storageProperties.getAttachmentsPath();
    }

    public Path getTmpPath() {
        return storageProperties.getTmpPath();
    }
}
