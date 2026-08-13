/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.List;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that each server transport abstraction advertises only the protocol versions
 * it can actually serve.
 */
class ServerTransportProtocolVersionsTests {

	private static final List<String> STREAMABLE_HTTP_VERSIONS = List.of(ProtocolVersions.MCP_2025_03_26,
			ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25);

	private static final McpStreamableServerTransportProvider STREAMABLE_PROVIDER = new McpStreamableServerTransportProvider() {
		@Override
		public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
		}

		@Override
		public Mono<Void> notifyClients(String method, Object params) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}
	};

	private static final McpStatelessServerTransport STATELESS_TRANSPORT = new McpStatelessServerTransport() {
		@Override
		public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}
	};

	@Test
	void streamableProviderDoesNotAdvertiseVersionsPredatingStreamableHttp() {
		assertThat(STREAMABLE_PROVIDER.protocolVersions()).doesNotContain(ProtocolVersions.MCP_2024_11_05)
			.containsExactlyElementsOf(STREAMABLE_HTTP_VERSIONS);
	}

	@Test
	void statelessTransportDoesNotAdvertiseVersionsPredatingStreamableHttp() {
		assertThat(STATELESS_TRANSPORT.protocolVersions()).doesNotContain(ProtocolVersions.MCP_2024_11_05)
			.containsExactlyElementsOf(STREAMABLE_HTTP_VERSIONS);
	}

	@Test
	void transportsWithoutStreamableHttpConstraintKeepTheFullRange() {
		McpServerTransportProviderBase base = new McpServerTransportProviderBase() {
			@Override
			public Mono<Void> notifyClients(String method, Object params) {
				return Mono.empty();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}
		};

		assertThat(base.protocolVersions()).containsExactly(ProtocolVersions.MCP_2024_11_05,
				ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25);
	}

}
