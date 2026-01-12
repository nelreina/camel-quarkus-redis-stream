package tech.nelreina.camel.quarkus.redis.stream.encryption;

import tech.nelreina.camel.quarkus.redis.stream.exception.RedisEncryptorException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class RedisEncryptor {

    private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String ALGORITHM = "AES";

    private final String key;
    private final String iv;
    private final boolean enabled;

    public RedisEncryptor(String key, String iv, boolean enabled) {
        this.validate(key, iv, enabled);
        this.key = key;
        this.iv = iv;
        this.enabled = enabled;
    }

    private void validate(String key, String iv, boolean enabled) {
        if(enabled) {
            if(nullOrBlank(key) || nullOrBlank(iv)) {
                throw new IllegalArgumentException("key or iv cannot be empty when encryption is enabled");
            }

            if(key.length() != 32) {
                throw new IllegalArgumentException("key must be str 32 characters");
            }

            if(iv.length() != 16) {
                throw new IllegalArgumentException("iv must be str 16 characters");
            }
        }
    }

    private boolean nullOrBlank(String val) {
        return val == null || val.isBlank();
    }

    public String encrypt(String toEncrypt) {
        String result = toEncrypt;

        if(enabled) {
            try {
                Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
                SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), ALGORITHM);
                IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes());

                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
                byte[] encrypted = cipher.doFinal(toEncrypt.getBytes());
                result = Base64.getEncoder().encodeToString(encrypted);
            } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                     InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
                throw new RedisEncryptorException(e);
            }
        }

        return result;
    }

    public String decrypt(String toDecrypt) {
        String result = toDecrypt;

        if(enabled) {
            try {
                Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);

                SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), ALGORITHM);
                IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes());

                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
                byte[] decoded = Base64.getDecoder().decode(toDecrypt);
                byte[] decrypted = cipher.doFinal(decoded);

                result = new String(decrypted);
            } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
                throw new RedisEncryptorException(e);
            }
        }

        return result;
    }
}
