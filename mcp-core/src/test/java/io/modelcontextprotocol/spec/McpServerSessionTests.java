/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpInitRequestHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for {@link McpServerSession} request bookkeeping: pending response entries
 * must not leak when a request times out or the subscriber cancels.
 */
class McpServerSessionTests {

	private static final Duration TIMEOUT = Duration.ofMillis(50);

	private static final String TEST_METHOD = "test.method";

	TypeRef<String> responseType = new TypeRef<>() {
	};

	private static McpServerTransport neverRespondingTransport() {
		return new McpServerTransport() {
			@Override
			public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
				// Accept the message but never deliver a response.
				return Mono.empty();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
				return (T) data;
			}
		};
	}

	private static McpServerSession newSession(Duration requestTimeout) {
		McpInitRequestHandler initHandler = params -> Mono
			.error(new UnsupportedOperationException("not used in this test"));
		JsonSchemaValidator validator = new JsonSchemaValidator() {
			@Override
			public ValidationResponse validate(Map<String, Object> schema, Object content) {
				return ValidationResponse.asValid(null);
			}

			@Override
			public ValidationResponse validateSchema(Map<String, Object> schema) {
				return ValidationResponse.asValid(null);
			}
		};
		return new McpServerSession("test-session", requestTimeout, neverRespondingTransport(), initHandler, Map.of(),
				Map.of(), () -> Mono.empty(), validator);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object, Object> pendingResponses(McpServerSession session) throws Exception {
		var field = McpServerSession.class.getDeclaredField("pendingResponses");
		field.setAccessible(true);
		return (Map<Object, Object>) field.get(session);
	}

	@Test
	void testRequestTimeoutRemovesPendingResponse() throws Exception {
		var session = newSession(TIMEOUT);

		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		StepVerifier.create(responseMono).expectError(java.util.concurrent.TimeoutException.class).verify();

		assertThat(pendingResponses(session)).isEmpty();

		session.close();
	}

	@Test
	void testRequestCancellationRemovesPendingResponse() throws Exception {
		var session = newSession(Duration.ofSeconds(5));

		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		var subscription = responseMono.subscribe();
		assertThat(pendingResponses(session)).hasSize(1);

		subscription.dispose();

		assertThat(pendingResponses(session)).isEmpty();

		session.close();
	}

}
