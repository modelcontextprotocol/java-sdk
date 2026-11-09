/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.io.IOException;
import java.time.Duration;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.InputRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientExceptionsTests {

	@Test
	void missingHandlerNamesTheInputKind() {
		var ex = new McpInputHandlerMissingException("roots", InputRequest.listRoots());

		assertThat(ex.inputKey()).isEqualTo("roots");
		assertThat(ex.inputRequest().method()).isEqualTo(McpSchema.METHOD_ROOTS_LIST);
		assertThat(ex).hasMessageContaining("'roots'").hasMessageContaining(McpSchema.METHOD_ROOTS_LIST);
	}

	@Test
	void missingCapabilityIsTheServersError() {
		JSONRPCError error = new JSONRPCError(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing capability");
		ClientCapabilities required = ClientCapabilities.builder().sampling().build();

		var ex = new McpMissingClientCapabilityException(error, required);

		assertThat(ex).isInstanceOf(McpException.class).hasMessage("Missing capability");
		assertThat(ex.error()).isSameAs(error);
		assertThat(ex.requiredCapabilities()).isSameAs(required);
	}

	@Test
	void timeoutSaysWhichTimeoutExpired() {
		var ex = new McpTimeoutException(McpTimeoutException.Kind.ROUND, Duration.ofSeconds(20),
				McpSchema.METHOD_TOOLS_CALL);

		assertThat(ex.kind()).isEqualTo(McpTimeoutException.Kind.ROUND);
		assertThat(ex.timeout()).isEqualTo(Duration.ofSeconds(20));
		assertThat(ex).hasMessage("tools/call exceeded its round timeout of PT20S");
	}

	@Test
	void lostResponseKeepsItsCause() {
		IOException cause = new IOException("connection reset");

		var ex = new McpResponseLostException(McpSchema.METHOD_TOOLS_CALL, cause);

		assertThat(ex.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
		assertThat(ex).hasCause(cause).hasMessageContaining("outcome is unknown");
	}

}
