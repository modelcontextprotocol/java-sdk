package io.modelcontextprotocol.server.auth.handlers;

import io.modelcontextprotocol.auth.OAuthMetadata;

import java.util.concurrent.CompletableFuture;

/**
 * Handler for OAuth metadata requests.
 */
public class MetadataHandler {

	private final OAuthMetadata metadata;

	public MetadataHandler(OAuthMetadata metadata) {
		this.metadata = metadata;
	}

	/**
	 * Handle a metadata request.
	 * @return A CompletableFuture that resolves to the OAuth metadata
	 */
	public CompletableFuture<OAuthMetadata> handle() {
		return CompletableFuture.completedFuture(metadata);
	}

}