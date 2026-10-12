package io.modelcontextprotocol.transport.inmemory;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public class InMemoryServerTransport implements McpServerTransport {

	private final InMemoryTransport transport;

	public InMemoryServerTransport(InMemoryTransport transport) {
		this.transport = requireNonNull(transport, "transport cannot be null");
	}

	public Sinks.Many<McpSchema.JSONRPCMessage> serverSink() {
		return transport.serverSink();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		var result = ofNullable(transport.clientSink()).map(s -> s.tryEmitNext(message))
			.orElse(Sinks.EmitResult.FAIL_TERMINATED);
		return switch (result) {
			case OK -> Mono.empty();
			case FAIL_TERMINATED, FAIL_NON_SERIALIZED, FAIL_OVERFLOW, FAIL_CANCELLED, FAIL_ZERO_SUBSCRIBER ->
				Mono.error(() -> new Sinks.EmissionException(result));
		};
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return transport.objectMapper().convertValue(data, typeRef);
	}

	@Override
	public List<String> protocolVersions() {
		return List.of(ProtocolVersions.MCP_2025_03_26);
	}

}
