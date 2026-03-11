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
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCMessage;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCNotification;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpServerTransport;
import reactor.core.publisher.Mono;

/**
 * A mock implementation of the {@link McpServerTransport} interfaces.
 */
public class MockMcpServerTransport implements McpServerTransport {

	private final List<JSONRPCMessage> sent = new ArrayList<>();

	private final BiConsumer<MockMcpServerTransport, JSONRPCMessage> interceptor;

	public MockMcpServerTransport() {
		this((t, msg) -> {
		});
	}

	public MockMcpServerTransport(BiConsumer<MockMcpServerTransport, JSONRPCMessage> interceptor) {
		this.interceptor = interceptor;
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		sent.add(message);
		interceptor.accept(this, message);
		return Mono.empty();
	}

	public JSONRPCRequest getLastSentMessageAsRequest() {
		return (JSONRPCRequest) getLastSentMessage();
	}

	public JSONRPCNotification getLastSentMessageAsNotification() {
		return (JSONRPCNotification) getLastSentMessage();
	}

	public JSONRPCMessage getLastSentMessage() {
		return !sent.isEmpty() ? sent.get(sent.size() - 1) : null;
	}

	public void clearSentMessages() {
		sent.clear();
	}

	public List<JSONRPCMessage> getAllSentMessages() {
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
