package com.ledgerflow.mockbank.settlement;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3SettlementStore implements SettlementStore {

    private final S3Client s3;
    private final SettlementProperties properties;

    public S3SettlementStore(S3Client s3, SettlementProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public void put(String key, String content, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromString(content));
    }
}
