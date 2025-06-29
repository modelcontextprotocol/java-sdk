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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Tests for {@link McpServerFeatures.AsyncToolSpecification.Builder}.
 *
 * @author Christian Tzolov
 */
class AsyncToolSpecificationBuilderTest {

	String emptyJsonSchema = """
			{
				"type": "object"
			}
			""";

	@Test
	void builderShouldCreateValidAsyncToolSpecification() {

		Tool tool = new Tool("test-tool", "A test tool", emptyJsonSchema);

		McpServerFeatures.AsyncToolSpecification specification = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callTool((exchange, request) -> Mono
				.just(new CallToolResult(List.of(new TextContent("Test result")), false)))
			.build();

		assertThat(specification).isNotNull();
		assertThat(specification.tool()).isEqualTo(tool);
		assertThat(specification.callTool()).isNotNull();
		assertThat(specification.call()).isNull(); // deprecated field should be null
	}

	@Test
	void builderShouldThrowExceptionWhenToolIsNull() {
		assertThatThrownBy(() -> McpServerFeatures.AsyncToolSpecification.builder()
			.callTool((exchange, request) -> Mono.just(new CallToolResult(List.of(), false)))
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessage("Tool must not be null");
	}

	@Test
	void builderShouldThrowExceptionWhenCallToolIsNull() {
		Tool tool = new Tool("test-tool", "A test tool", emptyJsonSchema);

		assertThatThrownBy(() -> McpServerFeatures.AsyncToolSpecification.builder().tool(tool).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("CallTool function must not be null");
	}

	@Test
	void builderShouldAllowMethodChaining() {
		Tool tool = new Tool("test-tool", "A test tool", emptyJsonSchema);
		McpServerFeatures.AsyncToolSpecification.Builder builder = McpServerFeatures.AsyncToolSpecification.builder();

		// Then - verify method chaining returns the same builder instance
		assertThat(builder.tool(tool)).isSameAs(builder);
		assertThat(builder.callTool((exchange, request) -> Mono.just(new CallToolResult(List.of(), false))))
			.isSameAs(builder);
	}

	@Test
	void builtSpecificationShouldExecuteCallToolCorrectly() {
		Tool tool = new Tool("calculator", "Simple calculator", emptyJsonSchema);
		String expectedResult = "42";

		McpServerFeatures.AsyncToolSpecification specification = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callTool((exchange, request) -> {
				return Mono.just(new CallToolResult(List.of(new TextContent(expectedResult)), false));
			})
			.build();

		CallToolRequest request = new CallToolRequest("calculator", Map.of());
		Mono<CallToolResult> resultMono = specification.callTool().apply(null, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedResult);
			assertThat(result.isError()).isFalse();
		}).verifyComplete();
	}

	@Test
	@SuppressWarnings("deprecation")
	void deprecatedConstructorShouldWorkCorrectly() {
		Tool tool = new Tool("deprecated-tool", "A deprecated tool", emptyJsonSchema);
		String expectedResult = "deprecated result";

		// Test the deprecated constructor that takes a 'call' function
		McpServerFeatures.AsyncToolSpecification specification = new McpServerFeatures.AsyncToolSpecification(tool,
				(exchange, arguments) -> Mono
					.just(new CallToolResult(List.of(new TextContent(expectedResult)), false)));

		assertThat(specification).isNotNull();
		assertThat(specification.tool()).isEqualTo(tool);
		assertThat(specification.call()).isNotNull(); // deprecated field should be set
		assertThat(specification.callTool()).isNotNull(); // should be automatically
															// created

		// Test that the callTool function works (it should delegate to the call function)
		CallToolRequest request = new CallToolRequest("deprecated-tool", Map.of("arg1", "value1"));
		Mono<CallToolResult> resultMono = specification.callTool().apply(null, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedResult);
			assertThat(result.isError()).isFalse();
		}).verifyComplete();

		// Test that the deprecated call function also works directly
		Mono<CallToolResult> callResultMono = specification.call().apply(null, request.arguments());

		StepVerifier.create(callResultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedResult);
			assertThat(result.isError()).isFalse();
		}).verifyComplete();
	}

	@Test
	void fromSyncShouldConvertSyncToolSpecificationCorrectly() {
		Tool tool = new Tool("sync-tool", "A sync tool", emptyJsonSchema);
		String expectedResult = "sync result";

		// Create a sync tool specification
		McpServerFeatures.SyncToolSpecification syncSpec = McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callTool((exchange, request) -> new CallToolResult(List.of(new TextContent(expectedResult)), false))
			.build();

		// Convert to async using fromSync
		McpServerFeatures.AsyncToolSpecification asyncSpec = McpServerFeatures.AsyncToolSpecification
			.fromSync(syncSpec);

		assertThat(asyncSpec).isNotNull();
		assertThat(asyncSpec.tool()).isEqualTo(tool);
		assertThat(asyncSpec.callTool()).isNotNull();
		assertThat(asyncSpec.call()).isNull(); // should be null since sync spec doesn't
												// have deprecated call

		// Test that the converted async specification works correctly
		CallToolRequest request = new CallToolRequest("sync-tool", Map.of("param", "value"));
		Mono<CallToolResult> resultMono = asyncSpec.callTool().apply(null, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedResult);
			assertThat(result.isError()).isFalse();
		}).verifyComplete();
	}

	@Test
	@SuppressWarnings("deprecation")
	void fromSyncShouldConvertSyncToolSpecificationWithDeprecatedCallCorrectly() {
		Tool tool = new Tool("sync-deprecated-tool", "A sync tool with deprecated call", emptyJsonSchema);
		String expectedResult = "sync deprecated result";
		McpAsyncServerExchange nullExchange = null; // Mock or create a suitable exchange
													// if needed

		// Create a sync tool specification using the deprecated constructor
		McpServerFeatures.SyncToolSpecification syncSpec = new McpServerFeatures.SyncToolSpecification(tool,
				(exchange, arguments) -> new CallToolResult(List.of(new TextContent(expectedResult)), false));

		// Convert to async using fromSync
		McpServerFeatures.AsyncToolSpecification asyncSpec = McpServerFeatures.AsyncToolSpecification
			.fromSync(syncSpec);

		assertThat(asyncSpec).isNotNull();
		assertThat(asyncSpec.tool()).isEqualTo(tool);
		assertThat(asyncSpec.callTool()).isNotNull();
		assertThat(asyncSpec.call()).isNotNull(); // should be set since sync spec has
													// deprecated call

		// Test that the converted async specification works correctly via callTool
		CallToolRequest request = new CallToolRequest("sync-deprecated-tool", Map.of("param", "value"));
		Mono<CallToolResult> resultMono = asyncSpec.callTool().apply(nullExchange, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedResult);
			assertThat(result.isError()).isFalse();
		}).verifyComplete();

		// Test that the deprecated call function also works
		Mono<CallToolResult> callResultMono = asyncSpec.call().apply(nullExchange, request.arguments());

		StepVerifier.create(callResultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedResult);
			assertThat(result.isError()).isFalse();
		}).verifyComplete();
	}

	@Test
	void fromSyncShouldReturnNullWhenSyncSpecIsNull() {
		assertThat(McpServerFeatures.AsyncToolSpecification.fromSync(null)).isNull();
	}

}
