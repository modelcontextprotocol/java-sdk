package io.modelcontextprotocol.server.auth.settings;

import java.util.List;

/**
 * Options for OAuth client registration.
 */
public class ClientRegistrationOptions {

	private boolean enabled = true;

	private boolean allowLocalhostRedirect = true;

	private List<String> validScopes;

	/**
	 * Check if client registration is enabled.
	 * @return true if client registration is enabled, false otherwise
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Set whether client registration is enabled.
	 * @param enabled true to enable client registration, false to disable
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Check if localhost redirect URIs are allowed.
	 * @return true if localhost redirect URIs are allowed, false otherwise
	 */
	public boolean isAllowLocalhostRedirect() {
		return allowLocalhostRedirect;
	}

	/**
	 * Set whether localhost redirect URIs are allowed.
	 * @param allowLocalhostRedirect true to allow localhost redirect URIs, false to
	 * disallow
	 */
	public void setAllowLocalhostRedirect(boolean allowLocalhostRedirect) {
		this.allowLocalhostRedirect = allowLocalhostRedirect;
	}

	/**
	 * Get the list of valid scopes.
	 * @return the list of valid scopes
	 */
	public List<String> getValidScopes() {
		return validScopes;
	}

	/**
	 * Set the list of valid scopes.
	 * @param validScopes the list of valid scopes
	 */
	public void setValidScopes(List<String> validScopes) {
		this.validScopes = validScopes;
	}

}