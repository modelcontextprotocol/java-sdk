package io.modelcontextprotocol.client.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Utility class for PKCE (Proof Key for Code Exchange) operations.
 */
public class PkceUtils {

	private static final SecureRandom secureRandom = new SecureRandom();

	private static final String ALLOWED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

	/**
	 * Generates a cryptographically random code verifier for PKCE.
	 * @return A random code verifier string.
	 */
	public static String generateCodeVerifier() {
		StringBuilder codeVerifier = new StringBuilder(128);
		for (int i = 0; i < 128; i++) {
			codeVerifier.append(ALLOWED_CHARS.charAt(secureRandom.nextInt(ALLOWED_CHARS.length())));
		}
		return codeVerifier.toString();
	}

	/**
	 * Generates a code challenge from a code verifier using SHA-256.
	 * @param codeVerifier The code verifier to hash.
	 * @return The code challenge string.
	 */
	public static String generateCodeChallenge(String codeVerifier) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		}
		catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 algorithm not available", e);
		}
	}

}