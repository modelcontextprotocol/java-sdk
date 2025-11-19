/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerSession.Factory;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;

/**
 * @author Christian Tzolov
 */
public class MockMcpServerTransportProvider implements McpServerTransportProvider {

	private McpServerSession session;

	private final MockMcpServerTransport transport;

	public MockMcpServerTransportProvider(MockMcpServerTransport transport) {
		this.transport = transport;
	}

	public MockMcpServerTransport getTransport() {
		return transport;
	}

	@Override
	public void setSessionFactory(Factory sessionFactory) {

		session = sessionFactory.create(null, transport);
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		return session.sendNotification(method, params);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return session.closeGracefully();
	}

	public void simulateIncomingMessage(McpSchema.JSONRPCMessage message) {
		session.handle(message).subscribe();
	}

}
