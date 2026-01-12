package tech.nelreina.camel.quarkus.redis.stream.exception;

import java.security.GeneralSecurityException;

public class RedisEncryptorException extends RuntimeException {
    public RedisEncryptorException(GeneralSecurityException e) {
        super(e);
    }
}
