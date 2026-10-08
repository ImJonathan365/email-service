package com.emailservice.sending;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.emailservice.common.config.AppProperties;

/**
 * email_hash = HMAC-SHA256(SUPPRESSION_HASH_KEY, normalized address) (docs/06 §3.7). Suppressions
 * are looked up by hash, so they keep working after a data subject's address is erased (FR-29).
 */
@Component
public class SuppressionHashes {

	private final SecretKeySpec key;

	public SuppressionHashes(AppProperties properties) {
		this.key = new SecretKeySpec(properties.sending().suppressionHashKey().getBytes(StandardCharsets.UTF_8),
				"HmacSHA256");
	}

	public byte[] hash(String address) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return mac.doFinal(EmailAddresses.normalized(address).getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
