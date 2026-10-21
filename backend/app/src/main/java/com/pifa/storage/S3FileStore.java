package com.pifa.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * {@link FileStore} on S3-compatible object storage (AWS S3 in production, MinIO locally). Active when
 * {@code pifa.storage.type=s3}. Required as soon as more than one API/worker replica runs, because local
 * disk is not shared between pods.
 *
 * <p>Credentials come from the standard AWS chain (environment variables, web identity / IRSA on EKS,
 * instance profile), never from application configuration. Objects are written with server-side
 * encryption; bucket versioning, lifecycle rules and replication are set on the bucket (Terraform).
 */
@Component
@ConditionalOnProperty(name = "pifa.storage.type", havingValue = "s3")
public class S3FileStore implements FileStore {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final boolean kms;

    /**
     * @param bucket   bucket name
     * @param prefix   key prefix inside the bucket (e.g. {@code pifa/}), may be empty
     * @param region   AWS region (MinIO accepts any)
     * @param endpoint custom endpoint for MinIO / other S3-compatible stores, empty for AWS
     * @param kms      true to request SSE-KMS (AWS), false for SSE-S3 / AES256 (MinIO supports this)
     */
    public S3FileStore(@Value("${pifa.storage.s3.bucket}") String bucket, @Value("${pifa.storage.s3.prefix:}") String prefix,
                       @Value("${pifa.storage.s3.region:eu-west-1}") String region, @Value("${pifa.storage.s3.endpoint:}") String endpoint,
                       @Value("${pifa.storage.s3.kms:true}") boolean kms) {
        var builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            // MinIO and most S3-compatible servers need path-style addressing (http://host/bucket/key).
            builder = builder.endpointOverride(URI.create(endpoint)).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        this.s3 = builder.build();
        this.bucket = bucket;
        this.prefix = prefix;
        this.kms = kms;
    }

    /** Package-private constructor for tests with a prepared client. */
    S3FileStore(S3Client s3, String bucket, String prefix, boolean kms) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix;
        this.kms = kms;
    }

    private String key(String key) {
        if (key.contains("..")) throw new IllegalArgumentException("Invalid storage key");
        return prefix + key;
    }

    @Override
    public void put(String key, byte[] data) {
        s3.putObject(b -> b.bucket(bucket).key(key(key)).serverSideEncryption(kms ? ServerSideEncryption.AWS_KMS : ServerSideEncryption.AES256),
            RequestBody.fromBytes(data));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key(key))).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new IllegalStateException("Cannot read " + key + ": not found", e);
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key(key)));
    }
}
