/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpServerTransport;
import reactor.core.publisher.Mono;

/**
 * A mock implementation of the {@link McpServerTransport} interfaces.
 */
public class MockMcpServerTransport implements McpServerTransport {

	private final List<McpSchema.JSONRPCMessage> sent = new ArrayList<>();

	private final BiConsumer<MockMcpServerTransport, McpSchema.JSONRPCMessage> interceptor;

	private volatile String awaitedResponseId;

	private volatile CountDownLatch responseLatch;

	public MockMcpServerTransport() {
		this((t, msg) -> {
		});
	}

	public MockMcpServerTransport(BiConsumer<MockMcpServerTransport, McpSchema.JSONRPCMessage> interceptor) {
		this.interceptor = interceptor;
	}

	/**
	 * Register a latch to count down when the server sends a response with the given
	 * request ID. Useful for awaiting handler completion after
	 * {@link MockMcpServerTransportProvider#simulateIncomingMessage}.
	 */
	public void setInterceptorForNextResponse(String requestId, CountDownLatch latch) {
		this.awaitedResponseId = requestId;
		this.responseLatch = latch;
	}

	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		sent.add(message);
		interceptor.accept(this, message);
		if (message instanceof McpSchema.JSONRPCResponse r && r.id() != null
				&& r.id().toString().equals(awaitedResponseId)) {
			CountDownLatch latch = responseLatch;
			if (latch != null) {
				awaitedResponseId = null;
				responseLatch = null;
				latch.countDown();
			}
		}
		return Mono.empty();
	}

	public McpSchema.JSONRPCRequest getLastSentMessageAsRequest() {
		return (JSONRPCRequest) getLastSentMessage();
	}

	public McpSchema.JSONRPCNotification getLastSentMessageAsNotification() {
		return (JSONRPCNotification) getLastSentMessage();
	}

	public McpSchema.JSONRPCMessage getLastSentMessage() {
		return !sent.isEmpty() ? sent.get(sent.size() - 1) : null;
	}

	public void clearSentMessages() {
		sent.clear();
	}

	public List<McpSchema.JSONRPCMessage> getAllSentMessages() {
		return new ArrayList<>(sent);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return McpJsonDefaults.getMapper().convertValue(data, typeRef);
	}

}
