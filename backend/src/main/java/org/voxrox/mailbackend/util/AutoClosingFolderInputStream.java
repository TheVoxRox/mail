package org.voxrox.mailbackend.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import jakarta.mail.Folder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoClosingFolderInputStream extends FilterInputStream {

    private static final Logger log = LoggerFactory.getLogger(AutoClosingFolderInputStream.class);
    private final Folder folder;
    private boolean closed = false;

    public AutoClosingFolderInputStream(InputStream in, Folder folder) {
        super(in);
        this.folder = folder;
    }

    @Override
    public int read() throws IOException {
        try {
            int b = super.read();
            if (b == -1)
                close();
            return b;
        } catch (IOException e) {
            log.debug("{} Stream read error, closing folder: {}", LogCategory.IMAP, e.getMessage());
            closeSilently();
            throw e;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        try {
            int result = super.read(b, off, len);
            if (result == -1)
                close();
            return result;
        } catch (IOException e) {
            log.debug("{} Error while reading data block, closing folder: {}", LogCategory.IMAP, e.getMessage());
            closeSilently();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed)
            return;
        closed = true;
        try {
            super.close();
        } finally {
            releaseResources();
        }
    }

    private void closeSilently() {
        try {
            close();
        } catch (Exception e) {
            // Best-effort cleanup on the error path — the original read
            // exception is already propagating to the caller.
            log.debug("{} Secondary close after stream error failed: {}", LogCategory.IMAP, e.getMessage());
        }
    }

    private void releaseResources() {
        try {
            if (folder != null && folder.isOpen()) {
                String folderName = folder.getFullName();
                folder.close(false);
                log.debug("{} Folder '{}' automatically closed after stream release.", LogCategory.IMAP, folderName);
            }
        } catch (Exception e) {
            log.warn("{} Failed to close Folder cleanly: {}", LogCategory.IMAP, e.getMessage());
        }
    }
}
