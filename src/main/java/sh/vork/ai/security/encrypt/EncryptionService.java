package sh.vork.ai.security.encrypt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class EncryptionService {

	private static Logger log = LoggerFactory.getLogger(EncryptionService.class);
	private static final String LEGACY_RSA_TAG = "!!ENC!!";
	
	private final Map<String,EncryptionProvider> encryptionProviders = new HashMap<>();
	
	@Autowired
	private ApplicationContext context;

	private EncryptionProvider defaultProvider;
	
	@PostConstruct
	private void postConstruct() {
		log.info("Looking for encryption providers ..");
		
		for(var provider : context.getBeansOfType(EncryptionProvider.class).values()) {
			try {
				provider.init();
				if(provider.getTag() == null || provider.getTag().length() != 7) {
					log.warn("  {} - Ignoring. Tag {} as it does not confirm to strict requirements.", provider.getClass().getName(), provider.getTag());
					continue;
				}
				encryptionProviders.put(provider.getTag(), provider);
				log.info("  {} - Activated.", provider.getClass().getName());
				continue;
			}
			catch(Exception e) {
				log.error("  {} - Ignoring. {}", provider.getClass().getName(), e.getMessage());
				if(log.isDebugEnabled()) {
					log.error("Failed to init provider.", e);
				}
			}
		}
		
		if(encryptionProviders.isEmpty()) {
			throw new IllegalStateException("No encryption provider. Cannot continue.");
		}
		
		List<EncryptionProvider> providers = encryptionProviders.values()
				.stream()
				.sorted((a, b) -> Integer.compare(a.priority(), b.priority())).toList();
		
		defaultProvider = providers.get(0);
	}
	
	public boolean isEncrypted(String value) {
		return encryptionProviders.containsKey(StringUtils.substring(value, 0, 7));
	}

	public String encrypt(String value) {

		if(isEncrypted(value)) {
			return value;
		}
		
		return encryptAndTag(value, defaultProvider);
	}

	public String encrypt(String value, String privateKeyPath, String publicKeyPath) {
		if(privateKeyPath == null && publicKeyPath == null) {
			return encrypt(value);
		}
		validateCustomKeyPaths(privateKeyPath, publicKeyPath);
		try {
			byte[] privateKeyBytes = Files.readAllBytes(Path.of(privateKeyPath));
			byte[] publicKeyBytes = Files.readAllBytes(Path.of(publicKeyPath));
			return encryptWithLegacyKeys(value, privateKeyBytes, publicKeyBytes);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to encrypt using custom key paths", e);
		}
	}

	public String encryptWithLegacyKeys(String value, byte[] privateKeyBytes, byte[] publicKeyBytes) {
		validateLegacyKeyMaterial(privateKeyBytes, publicKeyBytes);
		if(isEncrypted(value)) {
			return value;
		}
		try {
			EncryptionProvider provider = new LegacyRsaEncryptionProvider(privateKeyBytes, publicKeyBytes);
			provider.init();
			return encryptAndTag(value, provider);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to encrypt using legacy keys", e);
		}
	}

	private String encryptAndTag(String value, EncryptionProvider provider) {
    try {
        int keyLength = Math.min(Cipher.getMaxAllowedKeyLength("AES"), 256) / 8;
        
        SecureRandom rnd = new SecureRandom();
        byte[] rawkey = new byte[keyLength];
        rnd.nextBytes(rawkey);

        // GCM optimizes around a 12-byte (96-bit) IV
        byte[] iv = new byte[12]; 
        rnd.nextBytes(iv);

        StringBuilder buffer = new StringBuilder();
        buffer.append(Base64.getEncoder().encodeToString(rawkey));
        buffer.append("|");
        buffer.append(Base64.getEncoder().encodeToString(iv));
        buffer.append("|");
        
        // Pass to the authenticated GCM runner
        byte[] ciphertext = encryptAES(value, rawkey, iv);
        buffer.append(Base64.getEncoder().encodeToString(ciphertext));
        
        return provider.getTag().concat(provider.encrypt(buffer.toString()));
    } catch (Exception e) {
        throw new IllegalStateException("GCM Envelope build failed", e);
    }
}

private byte[] encryptAES(String value, byte[] key, byte[] iv) throws Exception {
    // 1. Authenticated Counter Mode with NO Padding
    Cipher aesCipherForEncryption = Cipher.getInstance("AES/GCM/NoPadding", "BC");

    SecretKey secretKeySpec = new SecretKeySpec(key, "AES");
    
    // 2. Specify a 128-bit Authentication Tag length alongside the 12-byte IV
    GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
    
    aesCipherForEncryption.init(Cipher.ENCRYPT_MODE, secretKeySpec, parameterSpec);

    byte[] byteDataToEncrypt = value.getBytes(StandardCharsets.UTF_8);
    return aesCipherForEncryption.doFinal(byteDataToEncrypt);
}

	public String decrypt(String value) {
		
		if(!isEncrypted(value)) {
			return value;
		}
		
		String tag = StringUtils.substring(value, 0, 7);
		EncryptionProvider encryptionProvider = encryptionProviders.get(tag);

		try {
			String data = encryptionProvider.decrypt(value.substring(7));
			String[] elements = data.split("\\|");
			byte[] key = Base64.getDecoder().decode(elements[0]);
			byte[] iv = Base64.getDecoder().decode(elements[1]);
			byte[] encrypted = Base64.getDecoder().decode(elements[2]);
			
			String tmp = new String(decryptAES(encrypted, key, iv), "UTF-8");
			return tmp;
		} catch(IllegalArgumentException  iae) {
			throw new IllegalStateException(
					"Detected likely key change. An attempt has been made to decrypt a piece of information that was encrypted "
					+ "with a different key. This may be due to the current encrypt service configuration being changed, or  "
					+ "the private key has been corrupted. It may also occur if this nodes Mongo database is shared with other "
					+ "nodes in a cluster, but the encryption service configuration on each node does not match. There is no "
					+ "recovery from this error other than correcting the encryption configuration or recovering the encryption "
					+ "key. ", iae);
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}	
	}

	public String decrypt(String value, String privateKeyPath, String publicKeyPath) {
		if(privateKeyPath == null && publicKeyPath == null) {
			return decrypt(value);
		}
		validateCustomKeyPaths(privateKeyPath, publicKeyPath);
		try {
			byte[] privateKeyBytes = Files.readAllBytes(Path.of(privateKeyPath));
			byte[] publicKeyBytes = Files.readAllBytes(Path.of(publicKeyPath));
			return decryptWithLegacyKeys(value, privateKeyBytes, publicKeyBytes);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to decrypt using custom key paths", e);
		}
	}

	public String decryptWithLegacyKeys(String value, byte[] privateKeyBytes, byte[] publicKeyBytes) {
		validateLegacyKeyMaterial(privateKeyBytes, publicKeyBytes);

		if(!isEncrypted(value) && !value.startsWith(LEGACY_RSA_TAG)) {
			return value;
		}

		if(value.startsWith(LEGACY_RSA_TAG)) {
			try {
				EncryptionProvider provider = new LegacyRsaEncryptionProvider(privateKeyBytes, publicKeyBytes);
				provider.init();
				return decryptWithProvider(value, provider);
			} catch (Exception e) {
				throw new IllegalStateException("Failed to decrypt using legacy keys", e);
			}
		}

		return decrypt(value);
	}

	private String decryptWithProvider(String value, EncryptionProvider encryptionProvider) {
		try {
			String data = encryptionProvider.decrypt(value.substring(7));
			String[] elements = data.split("\\|");
			byte[] key = Base64.getDecoder().decode(elements[0]);
			byte[] iv = Base64.getDecoder().decode(elements[1]);
			byte[] encrypted = Base64.getDecoder().decode(elements[2]);

			String tmp = new String(decryptAES(encrypted, key, iv), StandardCharsets.UTF_8);
			return tmp;
		} catch(IllegalArgumentException iae) {
			throw new IllegalStateException(
					"Detected likely key change. An attempt has been made to decrypt a piece of information that was encrypted "
					+ "with a different key. This may be due to the current encrypt service configuration being changed, or  "
					+ "the private key has been corrupted. It may also occur if this nodes Mongo database is shared with other "
					+ "nodes in a cluster, but the encryption service configuration on each node does not match. There is no "
					+ "recovery from this error other than correcting the encryption configuration or recovering the encryption "
					+ "key. ", iae);
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	private void validateCustomKeyPaths(String privateKeyPath, String publicKeyPath) {
		if(privateKeyPath == null || privateKeyPath.isBlank() || publicKeyPath == null || publicKeyPath.isBlank()) {
			throw new IllegalArgumentException("privateKeyPath and publicKeyPath must both be provided for legacy key-path mode");
		}
	}

	private void validateLegacyKeyMaterial(byte[] privateKeyBytes, byte[] publicKeyBytes) {
		if(privateKeyBytes == null || privateKeyBytes.length == 0 || publicKeyBytes == null || publicKeyBytes.length == 0) {
			throw new IllegalArgumentException("privateKeyBytes and publicKeyBytes are required for legacy key mode");
		}
	}
	
	private byte[] decryptAES(byte[] value, byte[] key, byte[] iv) throws Exception {
	    // 1. Authenticated Counter Mode with NO Padding
	    Cipher aesCipherForDecryption = Cipher.getInstance("AES/GCM/NoPadding", "BC");
	
	    SecretKey secretKeySpec = new SecretKeySpec(key, "AES");
	    
	    // 2. Specify a 128-bit Authentication Tag length alongside the 12-byte IV
	    GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
	    
	    aesCipherForDecryption.init(Cipher.DECRYPT_MODE, secretKeySpec, parameterSpec);
	
	    return aesCipherForDecryption.doFinal(value);
	}

	private static final class LegacyRsaEncryptionProvider extends AbstractEncryptionProvider {

		private final byte[] privateKeyBytes;
		private final byte[] publicKeyBytes;

		private LegacyRsaEncryptionProvider(byte[] privateKeyBytes, byte[] publicKeyBytes) {
			this.privateKeyBytes = privateKeyBytes;
			this.publicKeyBytes = publicKeyBytes;
		}

		@Override
		public int priority() {
			return Integer.MAX_VALUE;
		}

		@Override
		public void init() throws Exception {
			KeyFactory keyFactory = KeyFactory.getInstance("RSA");

			privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
			publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
		}

		@Override
		public String getTag() {
			return LEGACY_RSA_TAG;
		}

		@Override
		public int getLength() {
			return 128;
		}
	}
}
