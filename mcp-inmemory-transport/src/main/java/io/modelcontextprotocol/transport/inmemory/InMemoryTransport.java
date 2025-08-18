package io.modelcontextprotocol.transport.inmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Sinks;

public class InMemoryTransport {

	final Sinks.Many<McpSchema.JSONRPCMessage> toClientSink = Sinks.many().multicast().onBackpressureBuffer();

	final Sinks.Many<McpSchema.JSONRPCMessage> toServerSink = Sinks.many().multicast().onBackpressureBuffer();

	final ObjectMapper objectMapper = new ObjectMapper();

}
