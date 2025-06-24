package io.modelcontextprotocol.auth;

/**
 * Exception thrown when a redirect URI is invalid.
 */
public class InvalidRedirectUriException extends Exception {

	public InvalidRedirectUriException(String message) {
		super(message);
	}

}