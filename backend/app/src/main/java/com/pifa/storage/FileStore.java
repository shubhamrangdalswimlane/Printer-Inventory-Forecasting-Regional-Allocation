package com.pifa.storage;

/**
 * Where original uploaded files are kept. Phase 1 ships a local-disk implementation; an S3/MinIO
 * implementation can replace it without touching callers (architecture.md ADR-008).
 */
public interface FileStore {

    /**
     * Stores bytes under a key, replacing any existing object with that key.
     *
     * @param key  relative path-like key, e.g. {@code raw/FORECAST_UPLOAD/2026-11/v1/original.xlsx}
     * @param data the file content
     */
    void put(String key, byte[] data);

    /**
     * @param key a key previously passed to {@link #put}
     * @return the stored bytes
     * @throws IllegalStateException if the key does not exist
     */
    byte[] get(String key);

    /**
     * Deletes the object; does nothing if it does not exist.
     *
     * @param key the key to remove
     */
    void delete(String key);
}
