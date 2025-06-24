package io.modelcontextprotocol.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 7591 OAuth 2.0 Dynamic Client Registration metadata. See
 * https://datatracker.ietf.org/doc/html/rfc7591#section-2 for the full specification.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthClientMetadata {

	@JsonProperty("redirect_uris")
	private List<URI> redirectUris;

	@JsonProperty("token_endpoint_auth_method")
	private String tokenEndpointAuthMethod;

	@JsonProperty("grant_types")
	private List<String> grantTypes;

	@JsonProperty("response_types")
	private List<String> responseTypes;

	@JsonProperty("scope")
	private String scope;

	// Optional metadata fields
	@JsonProperty("client_name")
	private String clientName;

	@JsonProperty("client_uri")
	private URI clientUri;

	@JsonProperty("logo_uri")
	private URI logoUri;

	@JsonProperty("contacts")
	private List<String> contacts;

	@JsonProperty("tos_uri")
	private URI tosUri;

	@JsonProperty("policy_uri")
	private URI policyUri;

	@JsonProperty("jwks_uri")
	private URI jwksUri;

	@JsonProperty("jwks")
	private Object jwks;

	@JsonProperty("software_id")
	private String softwareId;

	@JsonProperty("software_version")
	private String softwareVersion;

	public OAuthClientMetadata() {
		this.tokenEndpointAuthMethod = "client_secret_post";
		this.grantTypes = Arrays.asList("authorization_code", "refresh_token");
		this.responseTypes = Arrays.asList("code");
	}

	/**
	 * Validates the requested scope against the client's allowed scopes.
	 * @param requestedScope The scope requested by the client
	 * @return List of validated scopes or null if no scope was requested
	 * @throws InvalidScopeException if the requested scope is not allowed
	 */
	public List<String> validateScope(String requestedScope) throws InvalidScopeException {
		if (requestedScope == null) {
			return null;
		}

		List<String> requestedScopes = Arrays.asList(requestedScope.split(" "));
		List<String> allowedScopes = scope == null ? new ArrayList<>() : Arrays.asList(scope.split(" "));

		for (String scope : requestedScopes) {
			if (!allowedScopes.contains(scope)) {
				throw new InvalidScopeException("Client was not registered with scope " + scope);
			}
		}

		return requestedScopes;
	}

	/**
	 * Validates the redirect URI against the client's registered redirect URIs.
	 * @param redirectUri The redirect URI to validate
	 * @return The validated redirect URI
	 * @throws InvalidRedirectUriException if the redirect URI is invalid
	 */
	public URI validateRedirectUri(URI redirectUri) throws InvalidRedirectUriException {
		if (redirectUri != null) {
			if (!redirectUris.contains(redirectUri)) {
				throw new InvalidRedirectUriException("Redirect URI '" + redirectUri + "' not registered for client");
			}
			return redirectUri;
		}
		else if (redirectUris.size() == 1) {
			return redirectUris.get(0);
		}
		else {
			throw new InvalidRedirectUriException(
					"redirect_uri must be specified when client has multiple registered URIs");
		}
	}

	// Getters and setters
	public List<URI> getRedirectUris() {
		return redirectUris;
	}

	public void setRedirectUris(List<URI> redirectUris) {
		this.redirectUris = redirectUris;
	}

	public String getTokenEndpointAuthMethod() {
		return tokenEndpointAuthMethod;
	}

	public void setTokenEndpointAuthMethod(String tokenEndpointAuthMethod) {
		this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
	}

	public List<String> getGrantTypes() {
		return grantTypes;
	}

	public void setGrantTypes(List<String> grantTypes) {
		this.grantTypes = grantTypes;
	}

	public List<String> getResponseTypes() {
		return responseTypes;
	}

	public void setResponseTypes(List<String> responseTypes) {
		this.responseTypes = responseTypes;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public String getClientName() {
		return clientName;
	}

	public void setClientName(String clientName) {
		this.clientName = clientName;
	}

	public URI getClientUri() {
		return clientUri;
	}

	public void setClientUri(URI clientUri) {
		this.clientUri = clientUri;
	}

	public URI getLogoUri() {
		return logoUri;
	}

	public void setLogoUri(URI logoUri) {
		this.logoUri = logoUri;
	}

	public List<String> getContacts() {
		return contacts;
	}

	public void setContacts(List<String> contacts) {
		this.contacts = contacts;
	}

	public URI getTosUri() {
		return tosUri;
	}

	public void setTosUri(URI tosUri) {
		this.tosUri = tosUri;
	}

	public URI getPolicyUri() {
		return policyUri;
	}

	public void setPolicyUri(URI policyUri) {
		this.policyUri = policyUri;
	}

	public URI getJwksUri() {
		return jwksUri;
	}

	public void setJwksUri(URI jwksUri) {
		this.jwksUri = jwksUri;
	}

	public Object getJwks() {
		return jwks;
	}

	public void setJwks(Object jwks) {
		this.jwks = jwks;
	}

	public String getSoftwareId() {
		return softwareId;
	}

	public void setSoftwareId(String softwareId) {
		this.softwareId = softwareId;
	}

	public String getSoftwareVersion() {
		return softwareVersion;
	}

	public void setSoftwareVersion(String softwareVersion) {
		this.softwareVersion = softwareVersion;
	}

}