package io.modelcontextprotocol.transport.inmemory;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public class InMemoryClientTransport implements McpClientTransport {

	private final InMemoryTransport transport;

	private Disposable disposable;

	public InMemoryClientTransport( InMemoryTransport transport ) {
		this.transport = requireNonNull(transport, "transport cannot be null");
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		disposable = transport.clientSink().asFlux()
			.flatMap(message -> handler.apply(Mono.just(message)))
			.subscribe( message -> sendMessage( message ).subscribe() );
		return Mono.empty();
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		var result = ofNullable(transport.serverSink())
				.map( s -> s.tryEmitNext(message))
				.orElse( Sinks.EmitResult.FAIL_TERMINATED );
		return switch( result ) {
			case OK -> Mono.empty();
			case FAIL_TERMINATED,
				 FAIL_NON_SERIALIZED,
				 FAIL_OVERFLOW,
				 FAIL_CANCELLED,
				 FAIL_ZERO_SUBSCRIBER -> Mono.error( () -> new Sinks.EmissionException(result) );
		};
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return transport.objectMapper().convertValue(data, typeRef);
	}

	@Override
	public Mono<Void> closeGracefully() {
		if( disposable!=null && !disposable.isDisposed() ) {
			disposable.dispose();
		}
		return Mono.empty();
	}

}
