package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpRequest;
import reactor.util.annotation.Nullable;

/**
 * Customize {@link HttpRequest.Builder} before sending out SSE or Streamable HTTP
 * transport.
 */
public interface SyncHttpRequestCustomizer {

	void customize(HttpRequest.Builder builder, String method, URI endpoint, @Nullable String body);

}
