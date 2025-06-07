package io.modelcontextprotocol.auth;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * RFC 8414 OAuth 2.0 Authorization Server Metadata. See
 * https://datatracker.ietf.org/doc/html/rfc8414#section-2
 */
public class OAuthMetadata {

	private URI issuer;

	private URI authorizationEndpoint;

	private URI tokenEndpoint;

	private URI registrationEndpoint;

	private List<String> scopesSupported;

	private List<String> responseTypesSupported;

	private List<String> responseModesSupported;

	private List<String> grantTypesSupported;

	private List<String> tokenEndpointAuthMethodsSupported;

	private List<String> tokenEndpointAuthSigningAlgValuesSupported;

	private URI serviceDocumentation;

	private List<String> uiLocalesSupported;

	private URI opPolicyUri;

	private URI opTosUri;

	private URI revocationEndpoint;

	private List<String> revocationEndpointAuthMethodsSupported;

	private List<String> revocationEndpointAuthSigningAlgValuesSupported;

	private URI introspectionEndpoint;

	private List<String> introspectionEndpointAuthMethodsSupported;

	private List<String> introspectionEndpointAuthSigningAlgValuesSupported;

	private List<String> codeChallengeMethodsSupported;

	public OAuthMetadata() {
		this.responseTypesSupported = Arrays.asList("code");
	}

	// Getters and setters
	public URI getIssuer() {
		return issuer;
	}

	public void setIssuer(URI issuer) {
		this.issuer = issuer;
	}

	public URI getAuthorizationEndpoint() {
		return authorizationEndpoint;
	}

	public void setAuthorizationEndpoint(URI authorizationEndpoint) {
		this.authorizationEndpoint = authorizationEndpoint;
	}

	public URI getTokenEndpoint() {
		return tokenEndpoint;
	}

	public void setTokenEndpoint(URI tokenEndpoint) {
		this.tokenEndpoint = tokenEndpoint;
	}

	public URI getRegistrationEndpoint() {
		return registrationEndpoint;
	}

	public void setRegistrationEndpoint(URI registrationEndpoint) {
		this.registrationEndpoint = registrationEndpoint;
	}

	public List<String> getScopesSupported() {
		return scopesSupported;
	}

	public void setScopesSupported(List<String> scopesSupported) {
		this.scopesSupported = scopesSupported;
	}

	public List<String> getResponseTypesSupported() {
		return responseTypesSupported;
	}

	public void setResponseTypesSupported(List<String> responseTypesSupported) {
		this.responseTypesSupported = responseTypesSupported;
	}

	public List<String> getResponseModesSupported() {
		return responseModesSupported;
	}

	public void setResponseModesSupported(List<String> responseModesSupported) {
		this.responseModesSupported = responseModesSupported;
	}

	public List<String> getGrantTypesSupported() {
		return grantTypesSupported;
	}

	public void setGrantTypesSupported(List<String> grantTypesSupported) {
		this.grantTypesSupported = grantTypesSupported;
	}

	public List<String> getTokenEndpointAuthMethodsSupported() {
		return tokenEndpointAuthMethodsSupported;
	}

	public void setTokenEndpointAuthMethodsSupported(List<String> tokenEndpointAuthMethodsSupported) {
		this.tokenEndpointAuthMethodsSupported = tokenEndpointAuthMethodsSupported;
	}

	public List<String> getTokenEndpointAuthSigningAlgValuesSupported() {
		return tokenEndpointAuthSigningAlgValuesSupported;
	}

	public void setTokenEndpointAuthSigningAlgValuesSupported(List<String> tokenEndpointAuthSigningAlgValuesSupported) {
		this.tokenEndpointAuthSigningAlgValuesSupported = tokenEndpointAuthSigningAlgValuesSupported;
	}

	public URI getServiceDocumentation() {
		return serviceDocumentation;
	}

	public void setServiceDocumentation(URI serviceDocumentation) {
		this.serviceDocumentation = serviceDocumentation;
	}

	public List<String> getUiLocalesSupported() {
		return uiLocalesSupported;
	}

	public void setUiLocalesSupported(List<String> uiLocalesSupported) {
		this.uiLocalesSupported = uiLocalesSupported;
	}

	public URI getOpPolicyUri() {
		return opPolicyUri;
	}

	public void setOpPolicyUri(URI opPolicyUri) {
		this.opPolicyUri = opPolicyUri;
	}

	public URI getOpTosUri() {
		return opTosUri;
	}

	public void setOpTosUri(URI opTosUri) {
		this.opTosUri = opTosUri;
	}

	public URI getRevocationEndpoint() {
		return revocationEndpoint;
	}

	public void setRevocationEndpoint(URI revocationEndpoint) {
		this.revocationEndpoint = revocationEndpoint;
	}

	public List<String> getRevocationEndpointAuthMethodsSupported() {
		return revocationEndpointAuthMethodsSupported;
	}

	public void setRevocationEndpointAuthMethodsSupported(List<String> revocationEndpointAuthMethodsSupported) {
		this.revocationEndpointAuthMethodsSupported = revocationEndpointAuthMethodsSupported;
	}

	public List<String> getRevocationEndpointAuthSigningAlgValuesSupported() {
		return revocationEndpointAuthSigningAlgValuesSupported;
	}

	public void setRevocationEndpointAuthSigningAlgValuesSupported(
			List<String> revocationEndpointAuthSigningAlgValuesSupported) {
		this.revocationEndpointAuthSigningAlgValuesSupported = revocationEndpointAuthSigningAlgValuesSupported;
	}

	public URI getIntrospectionEndpoint() {
		return introspectionEndpoint;
	}

	public void setIntrospectionEndpoint(URI introspectionEndpoint) {
		this.introspectionEndpoint = introspectionEndpoint;
	}

	public List<String> getIntrospectionEndpointAuthMethodsSupported() {
		return introspectionEndpointAuthMethodsSupported;
	}

	public void setIntrospectionEndpointAuthMethodsSupported(List<String> introspectionEndpointAuthMethodsSupported) {
		this.introspectionEndpointAuthMethodsSupported = introspectionEndpointAuthMethodsSupported;
	}

	public List<String> getIntrospectionEndpointAuthSigningAlgValuesSupported() {
		return introspectionEndpointAuthSigningAlgValuesSupported;
	}

	public void setIntrospectionEndpointAuthSigningAlgValuesSupported(
			List<String> introspectionEndpointAuthSigningAlgValuesSupported) {
		this.introspectionEndpointAuthSigningAlgValuesSupported = introspectionEndpointAuthSigningAlgValuesSupported;
	}

	public List<String> getCodeChallengeMethodsSupported() {
		return codeChallengeMethodsSupported;
	}

	public void setCodeChallengeMethodsSupported(List<String> codeChallengeMethodsSupported) {
		this.codeChallengeMethodsSupported = codeChallengeMethodsSupported;
	}

}