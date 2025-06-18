# OAuth 2.0 Client Implementation

This package provides an OAuth 2.0 client implementation for the MCP Java SDK, supporting the Authorization Code flow with PKCE (Proof Key for Code Exchange).

## Components

- `OAuthClientProvider`: Main class that handles the OAuth 2.0 flow
- `TokenStorage`: Interface for storing OAuth tokens and client information
- `HttpClientAuthenticator`: Authenticator for HTTP requests using OAuth
- `PkceUtils`: Utility class for PKCE operations
- `AuthCallbackResult`: Class to hold the result of an OAuth authorization callback

## Usage Example

```java
// Create client metadata
OAuthClientMetadata clientMetadata = new OAuthClientMetadata();
clientMetadata.setRedirectUris(List.of(URI.create("http://localhost:8080/callback")));
clientMetadata.setScope("read write");

// Create token storage
TokenStorage storage = new InMemoryTokenStorage();

// Create redirect handler (e.g., open browser)
Function<String, CompletableFuture<Void>> redirectHandler = url -> {
    try {
        Desktop.getDesktop().browse(URI.create(url));
        return CompletableFuture.completedFuture(null);
    } catch (IOException e) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(e);
        return future;
    }
};

// Create callback handler (e.g., start local server to receive callback)
Function<Void, CompletableFuture<AuthCallbackResult>> callbackHandler = v -> {
    // Implementation to start a local server and wait for callback
    // Return CompletableFuture<AuthCallbackResult> with code and state
};

// Create OAuth client provider
OAuthClientProvider provider = new OAuthClientProvider(
    "https://api.example.com",
    clientMetadata,
    storage,
    redirectHandler,
    callbackHandler,
    Duration.ofMinutes(5)
);

// Initialize provider
provider.initialize()
    .thenCompose(v -> provider.ensureToken())
    .thenRun(() -> {
        // Now you have a valid token
        String accessToken = provider.getAccessToken();
        System.out.println("Access token: " + accessToken);
    })
    .exceptionally(ex -> {
        System.err.println("Authentication failed: " + ex.getMessage());
        return null;
    });
```

## HTTP Client Integration

```java
HttpClientAuthenticator authenticator = new HttpClientAuthenticator(provider);

HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/resource"))
    .GET();

authenticator.authenticate(requestBuilder)
    .thenCompose(builder -> {
        HttpRequest request = builder.build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    })
    .thenCompose(response -> {
        // Handle 401 responses by refreshing the token
        return authenticator.handleResponse(response.statusCode())
            .thenApply(v -> response);
    })
    .thenAccept(response -> {
        System.out.println("Response: " + response.body());
    });
```

## Security Features

- PKCE support to prevent authorization code interception attacks
- State parameter to prevent CSRF attacks
- Automatic token refresh
- Thread-safe token management
- Scope validation