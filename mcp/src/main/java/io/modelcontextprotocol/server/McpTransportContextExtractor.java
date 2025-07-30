package io.modelcontextprotocol.server;

public interface McpTransportContextExtractor<T> {

	McpTransportContext extract(T request, McpTransportContext transportContext);

}
