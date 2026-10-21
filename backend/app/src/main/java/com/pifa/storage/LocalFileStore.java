package com.pifa.storage;

import com.pifa.config.PifaProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * {@link FileStore} that keeps files under one root folder on local disk. Default for development and
 * tests ({@code pifa.storage.type=local}); production uses {@link S3FileStore} because disk is not shared
 * between replicas.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "pifa.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStore implements FileStore {

    private final Path root;

    /** @param props supplies {@code pifa.storage.dir}, the root folder (created on demand) */
    public LocalFileStore(PifaProperties props) {
        this.root = Path.of(props.storage().dir()).toAbsolutePath().normalize();
    }

    /**
     * Resolves a key to a path and refuses keys that would escape the root (e.g. {@code ../../x}).
     *
     * @param key the object key
     * @return an absolute path inside the root
     * @throws IllegalArgumentException if the key leaves the root folder
     */
    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return p;
    }

    @Override
    public void put(String key, byte[] data) {
        try {
            Path p = resolve(key);
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot store " + key, e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete " + key, e);
        }
    }
}
