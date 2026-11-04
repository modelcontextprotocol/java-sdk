/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that {@link McpSchema#deserializeJsonRpcMessage} dispatches to the correct
 * concrete subtype for all four JSON-RPC message shapes, and that {@code params} /
 * {@code result} survive the round-trip.
 */
class JsonRpcDispatchTests {

	private final McpJsonMapper mapper = JSON_MAPPER;

	@Test
	void dispatchesRequest() throws IOException {
		String json = """
				{"jsonrpc":"2.0","id":"req-1","method":"tools/call","params":{"name":"echo","arguments":{"x":1}}}
				""";

		McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(mapper, json);

		assertThat(msg).isInstanceOf(McpSchema.JSONRPCRequest.class);
		McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) msg;
		assertThat(req.jsonrpc()).isEqualTo("2.0");
		assertThat(req.method()).isEqualTo("tools/call");
		assertThat(req.id()).isEqualTo("req-1");
		assertThat(req.params()).isNotNull();
	}

	@Test
	void dispatchesNotification() throws IOException {
		String json = """
				{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
				""";

		McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(mapper, json);

		assertThat(msg).isInstanceOf(McpSchema.JSONRPCNotification.class);
		McpSchema.JSONRPCNotification notif = (McpSchema.JSONRPCNotification) msg;
		assertThat(notif.method()).isEqualTo("notifications/initialized");
	}

	@Test
	void dispatchesSuccessResponse() throws IOException {
		String json = """
				{"jsonrpc":"2.0","id":"req-1","result":{"content":[{"type":"text","text":"hi"}]}}
				""";

		McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(mapper, json);

		assertThat(msg).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse resp = (McpSchema.JSONRPCResponse) msg;
		assertThat(resp.error()).isNull();
		assertThat(resp.result()).isNotNull();
	}

	@Test
	void dispatchesErrorResponse() throws IOException {
		String json = """
				{"jsonrpc":"2.0","id":"req-1","error":{"code":-32601,"message":"Method not found"}}
				""";

		McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(mapper, json);

		assertThat(msg).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse resp = (McpSchema.JSONRPCResponse) msg;
		assertThat(resp.error()).isNotNull();
		assertThat(resp.error().code()).isEqualTo(-32601);
		assertThat(resp.result()).isNull();
	}

	@Test
	void paramsMapSurvivesConvertValue() throws IOException {
		String json = """
				{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"x":42}}}
				""";

		McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) McpSchema.deserializeJsonRpcMessage(mapper, json);

		McpSchema.CallToolRequest call = mapper.convertValue(req.params(), new TypeRef<McpSchema.CallToolRequest>() {
		});
		assertThat(call.name()).isEqualTo("echo");
		@SuppressWarnings("unchecked")
		Map<String, Object> args = (Map<String, Object>) call.arguments();
		assertThat(((Number) args.get("x")).intValue()).isEqualTo(42);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", ",\"futureField\":true" })
	void errorWithoutIdRoundTrips(String extraField) throws IOException {
		String json = """
				{"jsonrpc":"2.0","error":{"code":-32600,"message":"Invalid request"}%s}
				""".formatted(extraField);
		var response = (McpSchema.JSONRPCResponse) McpSchema.deserializeJsonRpcMessage(mapper, json);
		assertThat(response.id()).isNull();
		assertThatJson(mapper.writeValueAsString(response)).isEqualTo("""
				{"jsonrpc":"2.0","error":{"code":-32600,"message":"Invalid request"}}
				""");
	}

	@Test
	void resultStillRequiresId() {
		assertThatIllegalArgumentException().isThrownBy(() -> McpSchema.JSONRPCResponse.result(null, Map.of()));
		assertThatThrownBy(() -> McpSchema.deserializeJsonRpcMessage(mapper, """
				{"jsonrpc":"2.0","result":{}}
				""")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void errorStillRequiresValidIdTypeAndExclusivePayload() {
		var error = new McpSchema.JSONRPCResponse.JSONRPCError(-32600, "Invalid request");
		assertThatIllegalArgumentException().isThrownBy(() -> McpSchema.JSONRPCResponse.error(true, error));
		assertThatIllegalArgumentException().isThrownBy(() -> McpSchema.JSONRPCResponse.error(1.5, error));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new McpSchema.JSONRPCResponse("2.0", null, Map.of(), error));
		assertThatIllegalArgumentException().isThrownBy(() -> McpSchema.JSONRPCResponse.error(null, null));
	}

}
