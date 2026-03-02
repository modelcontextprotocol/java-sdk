/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.net.URI;
import java.util.Objects;

/**
 * Context passed to the assertion callback in {@link EnterpriseAuthProvider}.
 * <p>
 * Contains the resource URL of the MCP server and the URL of the authorization server
 * that was discovered for that resource. The callback uses this context to obtain a
 * suitable assertion (e.g., an OIDC ID token) from the enterprise IdP.
 *
 * @author MCP SDK Contributors
 */
public class EnterpriseAuthAssertionContext {

	private final URI resourceUrl;

	private final URI authorizationServerUrl;

	/**
	 * Creates a new {@link EnterpriseAuthAssertionContext}.
	 * @param resourceUrl the URL of the MCP resource being accessed (must not be
	 * {@code null})
	 * @param authorizationServerUrl the URL of the MCP authorization server discovered
	 * for the resource (must not be {@code null})
	 */
	public EnterpriseAuthAssertionContext(URI resourceUrl, URI authorizationServerUrl) {
		this.resourceUrl = Objects.requireNonNull(resourceUrl, "resourceUrl must not be null");
		this.authorizationServerUrl = Objects.requireNonNull(authorizationServerUrl,
				"authorizationServerUrl must not be null");
	}

	/**
	 * Returns the URL of the MCP resource being accessed.
	 */
	public URI getResourceUrl() {
		return resourceUrl;
	}

	/**
	 * Returns the URL of the MCP authorization server for the resource.
	 */
	public URI getAuthorizationServerUrl() {
		return authorizationServerUrl;
	}

}
