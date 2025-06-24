package io.modelcontextprotocol.server.auth;

import io.modelcontextprotocol.auth.OAuthAuthorizationServerProvider;
import io.modelcontextprotocol.auth.OAuthMetadata;
import io.modelcontextprotocol.server.auth.handlers.AuthorizationHandler;
import io.modelcontextprotocol.server.auth.handlers.MetadataHandler;
import io.modelcontextprotocol.server.auth.handlers.RegistrationHandler;
import io.modelcontextprotocol.server.auth.handlers.RevocationHandler;
import io.modelcontextprotocol.server.auth.handlers.TokenHandler;
import io.modelcontextprotocol.server.auth.middleware.ClientAuthenticator;
import io.modelcontextprotocol.server.auth.settings.ClientRegistrationOptions;
import io.modelcontextprotocol.server.auth.settings.RevocationOptions;
import io.modelcontextprotocol.server.auth.util.UriUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for creating OAuth routes.
 */
public class OAuthRoutes {

	public static final String AUTHORIZATION_PATH = "/authorize";

	public static final String TOKEN_PATH = "/token";

	public static final String REGISTRATION_PATH = "/register";

	public static final String REVOCATION_PATH = "/revoke";

	public static final String METADATA_PATH = "/.well-known/oauth-authorization-server";

	/**
	 * Create OAuth metadata for the server.
	 * @param issuerUrl The issuer URL
	 * @param serviceDocumentationUrl The service documentation URL
	 * @param clientRegistrationOptions The client registration options
	 * @param revocationOptions The revocation options
	 * @return The OAuth metadata
	 */
	public static OAuthMetadata buildMetadata(URI issuerUrl, URI serviceDocumentationUrl,
			ClientRegistrationOptions clientRegistrationOptions, RevocationOptions revocationOptions) {

		UriUtils.validateIssuerUrl(issuerUrl);

		URI authorizationUrl = UriUtils.buildEndpointUrl(issuerUrl, AUTHORIZATION_PATH);
		URI tokenUrl = UriUtils.buildEndpointUrl(issuerUrl, TOKEN_PATH);

		OAuthMetadata metadata = new OAuthMetadata();
		metadata.setIssuer(issuerUrl);
		metadata.setAuthorizationEndpoint(authorizationUrl);
		metadata.setTokenEndpoint(tokenUrl);
		metadata.setScopesSupported(clientRegistrationOptions.getValidScopes());
		metadata.setResponseTypesSupported(List.of("code"));
		metadata.setGrantTypesSupported(List.of("authorization_code", "refresh_token"));
		metadata.setTokenEndpointAuthMethodsSupported(List.of("client_secret_post"));
		metadata.setServiceDocumentation(serviceDocumentationUrl);
		metadata.setCodeChallengeMethodsSupported(List.of("S256"));

		// Add registration endpoint if supported
		if (clientRegistrationOptions.isEnabled()) {
			metadata.setRegistrationEndpoint(UriUtils.buildEndpointUrl(issuerUrl, REGISTRATION_PATH));
		}

		// Add revocation endpoint if supported
		if (revocationOptions.isEnabled()) {
			metadata.setRevocationEndpoint(UriUtils.buildEndpointUrl(issuerUrl, REVOCATION_PATH));
			metadata.setRevocationEndpointAuthMethodsSupported(List.of("client_secret_post"));
		}

		return metadata;
	}

	/**
	 * Create handlers for OAuth routes.
	 * @param provider The OAuth authorization server provider
	 * @param metadata The OAuth metadata
	 * @param clientRegistrationOptions The client registration options
	 * @param revocationOptions The revocation options
	 * @return A map of route handlers
	 */
	public static OAuthHandlers createHandlers(OAuthAuthorizationServerProvider provider, OAuthMetadata metadata,
			ClientRegistrationOptions clientRegistrationOptions, RevocationOptions revocationOptions) {

		ClientAuthenticator clientAuthenticator = new ClientAuthenticator(provider);

		OAuthHandlers handlers = new OAuthHandlers();
		handlers.setMetadataHandler(new MetadataHandler(metadata));
		handlers.setAuthorizationHandler(new AuthorizationHandler(provider));
		handlers.setTokenHandler(new TokenHandler(provider, clientAuthenticator));

		if (clientRegistrationOptions.isEnabled()) {
			handlers.setRegistrationHandler(new RegistrationHandler(provider, clientRegistrationOptions));
		}

		if (revocationOptions.isEnabled()) {
			handlers.setRevocationHandler(new RevocationHandler(provider, clientAuthenticator));
		}

		return handlers;
	}

	/**
	 * Container for OAuth route handlers.
	 */
	public static class OAuthHandlers {

		private MetadataHandler metadataHandler;

		private AuthorizationHandler authorizationHandler;

		private TokenHandler tokenHandler;

		private RegistrationHandler registrationHandler;

		private RevocationHandler revocationHandler;

		public MetadataHandler getMetadataHandler() {
			return metadataHandler;
		}

		public void setMetadataHandler(MetadataHandler metadataHandler) {
			this.metadataHandler = metadataHandler;
		}

		public AuthorizationHandler getAuthorizationHandler() {
			return authorizationHandler;
		}

		public void setAuthorizationHandler(AuthorizationHandler authorizationHandler) {
			this.authorizationHandler = authorizationHandler;
		}

		public TokenHandler getTokenHandler() {
			return tokenHandler;
		}

		public void setTokenHandler(TokenHandler tokenHandler) {
			this.tokenHandler = tokenHandler;
		}

		public RegistrationHandler getRegistrationHandler() {
			return registrationHandler;
		}

		public void setRegistrationHandler(RegistrationHandler registrationHandler) {
			this.registrationHandler = registrationHandler;
		}

		public RevocationHandler getRevocationHandler() {
			return revocationHandler;
		}

		public void setRevocationHandler(RevocationHandler revocationHandler) {
			this.revocationHandler = revocationHandler;
		}

	}

}