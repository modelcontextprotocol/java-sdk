package io.modelcontextprotocol.transport.inmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public class InMemoryServerTransportProvider implements McpServerTransportProvider {

	private final Sinks.Many<McpSchema.JSONRPCMessage> toClientSink;

	private final Sinks.Many<McpSchema.JSONRPCMessage> toServerSink;

	private final ObjectMapper objectMapper;

	public InMemoryServerTransportProvider(Sinks.Many<McpSchema.JSONRPCMessage> toClientSink,
			Sinks.Many<McpSchema.JSONRPCMessage> toServerSink, ObjectMapper objectMapper) {
		this.toClientSink = toClientSink;
		this.toServerSink = toServerSink;
		this.objectMapper = objectMapper;
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		var session = sessionFactory.create(new InMemoryServerTransport(toClientSink, toServerSink, objectMapper));
		toServerSink.asFlux().subscribe(message -> {
			session.handle(message).subscribe();
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		// Not implemented for in-memory transport
		return Mono.empty();
	}

}
