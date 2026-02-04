/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Tests for {@link McpServerFeatures.SyncToolSpecification.Builder}.
 *
 * @author Christian Tzolov
 */
class SyncToolSpecificationBuilderTest {

	@Test
	void builderShouldCreateValidSyncToolSpecification() {

		Tool tool = Tool.builder().name("test-tool").title("A test tool").inputSchema(Map.of()).build();

		McpServerFeatures.SyncToolSpecification specification = McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> CallToolResult.builder()
				.content(List.of(new TextContent("Test result")))
				.isError(false)
				.build())
			.build();

		assertThat(specification).isNotNull();
		assertThat(specification.tool()).isEqualTo(tool);
		assertThat(specification.callHandler()).isNotNull();
		assertThat(specification.call()).isNull(); // deprecated field should be null
	}

	@Test
	void builderShouldThrowExceptionWhenToolIsNull() {
		assertThatThrownBy(() -> McpServerFeatures.SyncToolSpecification.builder()
			.callHandler((exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build())
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessage("Tool must not be null");
	}

	@Test
	void builderShouldThrowExceptionWhenCallToolIsNull() {
		Tool tool = Tool.builder().name("test-tool").description("A test tool").inputSchema(Map.of()).build();

		assertThatThrownBy(() -> McpServerFeatures.SyncToolSpecification.builder().tool(tool).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("CallTool function must not be null");
	}

	@Test
	void builderShouldAllowMethodChaining() {
		Tool tool = Tool.builder().name("test-tool").description("A test tool").inputSchema(Map.of()).build();
		McpServerFeatures.SyncToolSpecification.Builder builder = McpServerFeatures.SyncToolSpecification.builder();

		// Then - verify method chaining returns the same builder instance
		assertThat(builder.tool(tool)).isSameAs(builder);
		assertThat(builder
			.callHandler((exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build()))
			.isSameAs(builder);
	}

	@Test
	void builtSpecificationShouldExecuteCallToolCorrectly() {
		Tool tool = Tool.builder().name("calculator").description("Simple calculator").inputSchema(Map.of()).build();
		String expectedResult = "42";

		McpServerFeatures.SyncToolSpecification specification = McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> {
				// Simple test implementation
				return CallToolResult.builder()
					.content(List.of(new TextContent(expectedResult)))
					.isError(false)
					.build();
			})
			.build();

		CallToolRequest request = new CallToolRequest("calculator", Map.of());
		CallToolResult result = specification.callHandler().apply(null, request);

		assertThat(result).isNotNull();
		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
		assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedResult);
		assertThat(result.isError()).isFalse();
	}

}
