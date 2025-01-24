/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modelcontextprotocol;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.modelcontextprotocol.spec.ClientMcpTransport;
import org.modelcontextprotocol.spec.McpSchema;
import org.modelcontextprotocol.spec.ServerMcpTransport;
import org.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import org.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * A mock implementation of the {@link ClientMcpTransport} and {@link ServerMcpTransport}
 * interfaces.
 */
public class MockMcpTransport implements ClientMcpTransport, ServerMcpTransport {

	private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

	private final Sinks.Many<McpSchema.JSONRPCMessage> outgoing = Sinks.many().multicast().onBackpressureBuffer();

	private final Sinks.Many<McpSchema.JSONRPCMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

	private final Flux<McpSchema.JSONRPCMessage> outboundView = outgoing.asFlux().cache(1);

	public void simulateIncomingMessage(McpSchema.JSONRPCMessage message) {
		if (inbound.tryEmitNext(message).isFailure()) {
			throw new RuntimeException("Failed to emit message " + message);
		}
		inboundMessageCount.incrementAndGet();
	}

	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		if (outgoing.tryEmitNext(message).isFailure()) {
			return Mono.error(new RuntimeException("Can't emit outgoing message " + message));
		}
		return Mono.empty();
	}

	public McpSchema.JSONRPCRequest getLastSentMessageAsRequest() {
		return (JSONRPCRequest) outboundView.blockFirst();
	}

	public McpSchema.JSONRPCNotification getLastSentMessageAsNotifiation() {
		return (JSONRPCNotification) outboundView.blockFirst();
	}

	public McpSchema.JSONRPCMessage getLastSentMessage() {
		return outboundView.blockFirst();
	}

	private volatile boolean connected = false;

	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		if (connected) {
			return Mono.error(new IllegalStateException("Already connected"));
		}
		connected = true;
		return inbound.asFlux()
			.publishOn(Schedulers.boundedElastic())
			.flatMap(message -> Mono.just(message).transform(handler))
			.doFinally(signal -> connected = false)
			.then();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			connected = false;
			outgoing.tryEmitComplete();
			inbound.tryEmitComplete();
			// Wait for all subscribers to complete
			return Mono.empty();
		});
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return new ObjectMapper().convertValue(data, typeRef);
	}

}
