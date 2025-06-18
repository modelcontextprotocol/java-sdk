package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.auth.AccessToken;
import io.modelcontextprotocol.auth.OAuthAuthorizationServerProvider;
import io.modelcontextprotocol.auth.OAuthClientMetadata;
import io.modelcontextprotocol.auth.OAuthMetadata;
import io.modelcontextprotocol.server.auth.handlers.AuthorizationHandler;
import io.modelcontextprotocol.server.auth.handlers.MetadataHandler;
import io.modelcontextprotocol.server.auth.handlers.RegistrationHandler;
import io.modelcontextprotocol.server.auth.handlers.RevocationHandler;
import io.modelcontextprotocol.server.auth.handlers.TokenHandler;
import io.modelcontextprotocol.server.auth.middleware.AuthContext;
import io.modelcontextprotocol.server.auth.middleware.BearerAuthenticator;
import io.modelcontextprotocol.server.auth.middleware.ClientAuthenticator;
import io.modelcontextprotocol.server.auth.settings.ClientRegistrationOptions;
import io.modelcontextprotocol.server.auth.settings.RevocationOptions;
import io.modelcontextprotocol.spec.McpError;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Extended transport provider that handles both MCP messages and OAuth routes. This class
 * integrates OAuth authentication routes directly into the transport layer It also adds
 * authentication middleware to validate requests for SSE and message endpoints.
 */
public class OAuthHttpServletSseServerTransportProvider extends HttpServletSseServerTransportProvider {

	/** Logger for this class */
	private static final Logger logger = LoggerFactory.getLogger(OAuthHttpServletSseServerTransportProvider.class);

	private final MetadataHandler metadataHandler;

	private final AuthorizationHandler authorizationHandler;

	private final TokenHandler tokenHandler;

	private final RegistrationHandler registrationHandler;

	private final RevocationHandler revocationHandler;

	private final ClientAuthenticator clientAuthenticator;

	private final BearerAuthenticator bearerAuthenticator;

	private final ObjectMapper objectMapper;

	/**
	 * Creates a new OAuthHttpServletSseServerTransportProvider.
	 * @param objectMapper The JSON object mapper
	 * @param mcpEndpoint The MCP endpoint path
	 * @param authProvider The OAuth authorization server provider
	 * @param issuerUrl The issuer URL for OAuth metadata
	 * @param registrationOptions The client registration options
	 * @param revocationOptions The token revocation options
	 */
	public OAuthHttpServletSseServerTransportProvider(ObjectMapper objectMapper, String mcpEndpoint, String baseUrl,
			OAuthAuthorizationServerProvider authProvider, URI issuerUrl, ClientRegistrationOptions registrationOptions,
			RevocationOptions revocationOptions) {
		super(objectMapper, baseUrl, mcpEndpoint, "/sse");
		// HttpServletSseServerTransportProvider.builder().baseUrl(baseUrl).messageEndpoint(mcpEndpoint).build();
		this.objectMapper = objectMapper;

		logger.info("Initializing OAuthHttpServletSseServerTransportProvider with base URL: " + baseUrl);

		// Create authenticators
		this.clientAuthenticator = new ClientAuthenticator(authProvider);
		this.bearerAuthenticator = new BearerAuthenticator(authProvider);

		// Create metadata
		OAuthMetadata metadata = new OAuthMetadata();
		metadata.setIssuer(issuerUrl);
		metadata.setAuthorizationEndpoint(URI.create(issuerUrl + "/authorize"));
		metadata.setTokenEndpoint(URI.create(issuerUrl + "/token"));
		metadata.setScopesSupported(registrationOptions.getValidScopes());
		metadata.setResponseTypesSupported(java.util.Arrays.asList("code"));
		metadata.setGrantTypesSupported(java.util.Arrays.asList("authorization_code", "refresh_token"));
		metadata.setTokenEndpointAuthMethodsSupported(java.util.Arrays.asList("client_secret_post"));
		metadata.setCodeChallengeMethodsSupported(java.util.Arrays.asList("S256"));

		if (registrationOptions.isEnabled()) {
			metadata.setRegistrationEndpoint(URI.create(issuerUrl + "/register"));
		}

		if (revocationOptions.isEnabled()) {
			metadata.setRevocationEndpoint(URI.create(issuerUrl + "/revoke"));
			metadata.setRevocationEndpointAuthMethodsSupported(java.util.Arrays.asList("client_secret_post"));
		}

		// Create handlers
		this.metadataHandler = new MetadataHandler(metadata);
		this.authorizationHandler = new AuthorizationHandler(authProvider);
		this.tokenHandler = new TokenHandler(authProvider, clientAuthenticator);
		this.registrationHandler = registrationOptions.isEnabled()
				? new RegistrationHandler(authProvider, registrationOptions) : null;
		this.revocationHandler = revocationOptions.isEnabled()
				? new RevocationHandler(authProvider, clientAuthenticator) : null;

		logger.info("OAuthHttpServletSseServerTransportProvider initialized with base URL: " + baseUrl);
	}

	/**
	 * Gets the object mapper.
	 * @return The object mapper
	 */
	protected ObjectMapper getObjectMapper() {
		return objectMapper;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		logger.info("Handling OAuth GET request: " + request.getRequestURI());
		String path = request.getRequestURI();

		// Handle OAuth GET routes
		if (path.endsWith("/.well-known/oauth-authorization-server")) {
			handleMetadataRequest(request, response);
		}
		else if (path.endsWith("/authorize")) {
			handleAuthorizeRequest(request, response);
		}
		else {
			// Handle other GET requests using the parent class
			super.doGet(request, response);
		}
	}

	/**
	 * Authenticates a request using the Bearer token in the Authorization header.
	 * @param request The HTTP request
	 * @param response The HTTP response
	 * @return true if authentication succeeded, false otherwise
	 */
	@Override
	protected boolean authenticateRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String authHeader = request.getHeader("Authorization");

		try {
			// Use the BearerAuthenticator to validate the token
			AccessToken token = bearerAuthenticator.authenticate(authHeader).join();

			// Create auth context and store it in request attributes and thread-local
			AuthContext authContext = new AuthContext(token);
			request.setAttribute("authContext", authContext);
			AuthContext.setCurrent(authContext);

			return true;
		}
		catch (Exception e) {
			// Clear auth context in case of failure
			AuthContext.clearCurrent();
			// Extract the root cause message
			String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
			sendAuthError(response, message, HttpServletResponse.SC_UNAUTHORIZED);
			return false;
		}
	}

	@Override
	protected HttpServletMcpSessionTransport createSessionTransport(String sessionId, AsyncContext asyncContext,
			PrintWriter writer) {
		HttpServletMcpSessionTransport transport = super.createSessionTransport(sessionId, asyncContext, writer);

		AuthContext authContext = AuthContext.getCurrent();
		if (authContext != null) {
			transport.setAuthContext(authContext);
		}

		return transport;
	}

	/**
	 * Sends an authentication error response.
	 * @param response The HTTP response
	 * @param message The error message
	 * @param statusCode The HTTP status code
	 */
	private void sendAuthError(HttpServletResponse response, String message, int statusCode) throws IOException {
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setStatus(statusCode);

		McpError error = new McpError(message);
		String jsonError = getObjectMapper().writeValueAsString(error);

		PrintWriter writer = response.getWriter();
		writer.write(jsonError);
		writer.flush();
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		try {
			String path = request.getRequestURI();

			// Handle OAuth POST routes
			if (path.endsWith("/token")) {
				handleTokenRequest(request, response);
			}
			else if (path.endsWith("/register") && registrationHandler != null) {
				handleRegisterRequest(request, response);
			}
			else if (path.endsWith("/revoke") && revocationHandler != null) {
				handleRevokeRequest(request, response);
			}
			else if (path.endsWith("/authorize")) {
				handleAuthorizeRequest(request, response);
			}
			else {
				// Handle other POST requests using the parent class
				super.doPost(request, response);
			}
		}
		finally {
			// Clear thread-local auth context after request is processed
			AuthContext.clearCurrent();
		}

	}

	private void handleMetadataRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		try {
			OAuthMetadata metadata = metadataHandler.handle().join();
			response.setContentType("application/json");
			response.setStatus(200);
			getObjectMapper().writeValue(response.getOutputStream(), metadata);
		}
		catch (CompletionException ex) {
			response.setStatus(500);
		}
	}

	private void handleAuthorizeRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// Extract parameters
		Map<String, String> params = new HashMap<>();
		request.getParameterMap().forEach((key, values) -> {
			if (values.length > 0) {
				params.put(key, values[0]);
			}
		});

		try {
			String redirectUrl = authorizationHandler.handle(params).join().getRedirectUrl();
			response.setHeader("Location", redirectUrl);
			response.setHeader("Cache-Control", "no-store");
			response.setStatus(302); // Found
		}
		catch (CompletionException ex) {
			response.setStatus(400);
		}
	}

	// private void handleAuthorizeRequest(HttpServletRequest request, HttpServletResponse
	// response) throws IOException {
	// if ("GET".equalsIgnoreCase(request.getMethod())) {
	// // Render consent page
	// String clientId = request.getParameter("client_id");
	// String redirectUri = request.getParameter("redirect_uri");
	// String scope = request.getParameter("scope");
	// String state = request.getParameter("state");
	// String codeChallenge = request.getParameter("code_challenge");
	// String codeChallengeMethod = request.getParameter("code_challenge_method");
	// String responseType = request.getParameter("response_type");

	// response.setContentType("text/html");
	// response.getWriter()
	// .write("<html><body>" + "<h1>Authorize Access</h1>" + "<p>Client <b>" + clientId
	// + "</b> is requesting access with scope: <b>" + scope + "</b></p>"
	// + "<form method='post' action='/authorize'>" + "<input type='hidden'
	// name='client_id' value='"
	// + clientId + "'/>" + "<input type='hidden' name='redirect_uri' value='" +
	// redirectUri + "'/>"
	// + "<input type='hidden' name='scope' value='" + scope + "'/>"
	// + "<input type='hidden' name='state' value='" + state + "'/>"
	// + "<input type='hidden' name='code_challenge' value='" + codeChallenge + "'/>"
	// + "<input type='hidden' name='code_challenge_method' value='" + codeChallengeMethod
	// + "'/>"
	// + "<input type='hidden' name='response_type' value='" + responseType + "'/>"
	// + "<button type='submit'>Authorize</button>" + "</form>" + "</body></html>");
	// }
	// else if ("POST".equalsIgnoreCase(request.getMethod())) {
	// // Extract parameters from form
	// Map<String, String> params = new HashMap<>();
	// request.getParameterMap().forEach((key, values) -> {
	// if (values.length > 0) {
	// params.put(key, values[0]);
	// }
	// });

	// try {
	// String redirectUrl = authorizationHandler.handle(params).join().getRedirectUrl();
	// response.setHeader("Location", redirectUrl);
	// response.setHeader("Cache-Control", "no-store");
	// response.setStatus(302); // Found
	// }
	// catch (CompletionException ex) {
	// response.setStatus(400);
	// }
	// }
	// }

	private void handleTokenRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// Extract parameters
		Map<String, String> params = new HashMap<>();
		request.getParameterMap().forEach((key, values) -> {
			if (values.length > 0) {
				params.put(key, values[0]);
			}
		});

		try {
			io.modelcontextprotocol.auth.OAuthToken token = tokenHandler.handle(params).join();
			response.setContentType("application/json");
			response.setHeader("Cache-Control", "no-store");
			response.setHeader("Pragma", "no-cache");
			response.setStatus(200);
			getObjectMapper().writeValue(response.getOutputStream(), token);
		}
		catch (CompletionException ex) {
			response.setStatus(400);
		}
	}

	private void handleRegisterRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// Read request body
		BufferedReader reader = request.getReader();
		StringBuilder body = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			body.append(line);
		}

		try {
			OAuthClientMetadata clientMetadata = getObjectMapper().readValue(body.toString(),
					OAuthClientMetadata.class);

			Object clientInfo = registrationHandler.handle(clientMetadata).join();
			response.setContentType("application/json");
			response.setStatus(201); // Created
			getObjectMapper().writeValue(response.getOutputStream(), clientInfo);
		}
		catch (CompletionException ex) {
			response.setStatus(400);
		}
	}

	private void handleRevokeRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// Extract parameters
		Map<String, String> params = new HashMap<>();
		request.getParameterMap().forEach((key, values) -> {
			if (values.length > 0) {
				params.put(key, values[0]);
			}
		});

		try {
			revocationHandler.handle(params).join();
			response.setStatus(200);
		}
		catch (CompletionException ex) {
			response.setStatus(400);
		}
	}

}