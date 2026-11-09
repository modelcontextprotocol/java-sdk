/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests whose {@code _meta} comes from {@link RequestMeta}, sent through a transport
 * to the modern server.
 */
class RequestMetaServerTests {

	private static final Implementation CLIENT_INFO = Implementation.builder("test-client", "1.0.0").build();

	private static final Implementation SERVER_INFO = Implementation.builder("test-server", "1.0.0").build();

	private static final String METHOD = "example/echo";

	private static McpFeature feature(Mono<? extends McpAsyncResponse<? extends Result>> answer,
			AtomicReference<McpRequestContext> seen) {
		return new McpFeature() {
			@Override
			public Set<String> methods() {
				return Set.of(METHOD);
			}

			@Override
			public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
				seen.set(ctx);
				return answer;
			}
		};
	}

	private static CallToolResult ok() {
		return CallToolResult.builder().addContent(TextContent.builder("ok").build()).build();
	}

	private static MockMcpClientTransport transport(McpFeature feature) {
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.jsonMapper(McpJsonDefaults.getMapper())
			.feature(feature)
			.build();
		return new MockMcpClientTransport(server, McpJsonDefaults.getMapper(), false);
	}

	private static McpTransportRequest request(String version, Object progressToken) {
		ClientCapabilities capabilities = ClientCapabilities.builder()
			.elicitation(new ClientCapabilities.Elicitation(new ClientCapabilities.Elicitation.Form(), null))
			.build();
		Map<String, Object> meta = RequestMeta.of(version, capabilities, CLIENT_INFO, progressToken);
		return McpTransportRequest.builder(new JSONRPCRequest(METHOD, 1, new PaginatedRequest(null, meta)), version)
			.build();
	}

	@Test
	void serverReadsEveryMetaEntry() {
		AtomicReference<McpRequestContext> seen = new AtomicReference<>();
		MockMcpClientTransport transport = transport(feature(Mono.just(McpAsyncResponse.result(ok())), seen));

		StepVerifier.create(transport.exchange(request(McpSchema.LATEST_PROTOCOL_VERSION, "tok-1")))
			.assertNext(message -> assertThat(((JSONRPCResponse) message).error()).isNull())
			.verifyComplete();

		McpRequestContext ctx = seen.get();
		assertThat(ctx.protocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(ctx.clientCapabilities().supportsElicitationForm()).isTrue();
		assertThat(ctx.clientCapabilities().supportsElicitationUrl()).isFalse();
		assertThat(ctx.clientCapabilities().supportsSampling()).isFalse();
		assertThat(ctx.clientInfo()).isEqualTo(CLIENT_INFO);
		assertThat(ctx.progressToken()).isEqualTo("tok-1");
	}

	@Test
	void progressPrecedesTheResponse() {
		AtomicReference<McpRequestContext> seen = new AtomicReference<>();
		MockMcpClientTransport transport = transport(feature(
				Mono.just(McpAsyncResponse.streaming(notifier -> notifier.progress(1.0, 2.0, "half").thenReturn(ok()))),
				seen));

		StepVerifier.create(transport.exchange(request(McpSchema.LATEST_PROTOCOL_VERSION, 3L)))
			.assertNext(message -> assertThat(((JSONRPCNotification) message).method())
				.isEqualTo(McpSchema.METHOD_NOTIFICATION_PROGRESS))
			.assertNext(message -> assertThat(message).isInstanceOf(JSONRPCResponse.class))
			.verifyComplete();
	}

	@Test
	void serverErrorIsAnErrorResponse() {
		MockMcpClientTransport transport = transport(
				feature(Mono.just(McpAsyncResponse.result(ok())), new AtomicReference<>()));

		StepVerifier.create(transport.exchange(request("1999-01-01", null)))
			.assertNext(message -> assertThat(((JSONRPCResponse) message).error().code())
				.isEqualTo(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION))
			.verifyComplete();
	}

	@Test
	void subscriberContextReachesTheServer() {
		AtomicReference<McpRequestContext> seen = new AtomicReference<>();
		MockMcpClientTransport transport = transport(feature(Mono.just(McpAsyncResponse.result(ok())), seen));
		McpTransportContext identity = McpTransportContext.create(Map.of("user", "alice"));

		StepVerifier
			.create(transport.exchange(request(McpSchema.LATEST_PROTOCOL_VERSION, null))
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, identity)))
			.expectNextCount(1)
			.verifyComplete();

		assertThat(seen.get().transportContext().get("user")).isEqualTo("alice");
	}

	@Test
	void cancellingTheExchangeCancelsTheServersWork() {
		AtomicBoolean cancelled = new AtomicBoolean();
		MockMcpClientTransport transport = transport(feature(Mono.just(McpAsyncResponse.streaming(
				notifier -> notifier.progress(1.0, null, null).then(Mono.<CallToolResult>never()).doOnCancel(() -> {
					cancelled.set(true);
				}))), new AtomicReference<>()));

		StepVerifier.create(transport.exchange(request(McpSchema.LATEST_PROTOCOL_VERSION, "tok-1")))
			.expectNextCount(1)
			.thenCancel()
			.verify(Duration.ofSeconds(5));

		assertThat(cancelled).isTrue();
	}

	@Test
	void toolCallMetaTravelsInsideTheRequestRecord() {
		AtomicReference<McpRequestContext> seen = new AtomicReference<>();
		McpFeature feature = new McpFeature() {
			@Override
			public Set<String> methods() {
				return Set.of(McpSchema.METHOD_TOOLS_CALL);
			}

			@Override
			public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
				seen.set(ctx);
				return Mono.just(McpAsyncResponse.result(ok()));
			}
		};
		MockMcpClientTransport transport = transport(feature);
		Map<String, Object> meta = RequestMeta.of(McpSchema.LATEST_PROTOCOL_VERSION, ClientCapabilities.NONE,
				CLIENT_INFO, null);
		CallToolRequest call = CallToolRequest.builder("echo").arguments(Map.of("text", "hi")).meta(meta).build();

		StepVerifier.create(transport.exchange(McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, 1, call), McpSchema.LATEST_PROTOCOL_VERSION)
			.name("echo")
			.build())).expectNextCount(1).verifyComplete();

		assertThat(seen.get().primitiveName()).isEqualTo("echo");
		assertThat(seen.get().clientCapabilities()).isEqualTo(ClientCapabilities.NONE);
	}

}
