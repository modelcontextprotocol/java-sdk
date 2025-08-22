package io.modelcontextprotocol.transport.inmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Sinks;

import static java.util.Objects.requireNonNull;

public record InMemoryTransport(
		Sinks.Many<McpSchema.JSONRPCMessage> clientSink,
		Sinks.Many<McpSchema.JSONRPCMessage> serverSink,
		ObjectMapper objectMapper
){
	public InMemoryTransport {
		requireNonNull(clientSink,"clientSink cannot be null!");
		requireNonNull(serverSink,"serverSink cannot be null!");
		requireNonNull(objectMapper,"objectMapper cannot be null!");
	}
	public InMemoryTransport() {
		this(	Sinks.many().multicast().onBackpressureBuffer(),
				Sinks.many().multicast().onBackpressureBuffer(),
				new ObjectMapper() );
	}
}
