/*
* Copyright 2025 - 2025 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package io.modelcontextprotocol;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.session.McpServerSession;
import io.modelcontextprotocol.spec.ServerSessionFactory;
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
	public void setSessionFactory(ServerSessionFactory sessionFactory) {

		session = (McpServerSession) sessionFactory.create(transport);
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		return session.sendNotification(method, params);
	}

	@Override
	public void close() {
		this.closeGracefully().subscribe();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return session.closeGracefully();
	}

	public void simulateIncomingMessage(McpSchema.JSONRPCMessage message) {
		session.handle(message).subscribe();
	}

}
