package io.modelcontextprotocol.client.auth;

/**
 * Result of an OAuth authorization callback.
 */
public class AuthCallbackResult {

	private final String code;

	private final String state;

	/**
	 * Creates a new AuthCallbackResult.
	 * @param code The authorization code.
	 * @param state The state parameter.
	 */
	public AuthCallbackResult(String code, String state) {
		this.code = code;
		this.state = state;
	}

	/**
	 * Get the authorization code.
	 * @return The authorization code.
	 */
	public String getCode() {
		return code;
	}

	/**
	 * Get the state parameter.
	 * @return The state parameter.
	 */
	public String getState() {
		return state;
	}

}