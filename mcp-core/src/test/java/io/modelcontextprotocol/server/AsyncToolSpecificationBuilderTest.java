/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.ToolNameValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link McpServerFeatures.AsyncToolSpecification.Builder}.
 *
 * @author Christian Tzolov
 */
class AsyncToolSpecificationBuilderTest {

	@Test
	void builderShouldCreateValidAsyncToolSpecification() {

		Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.title("A test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		McpServerFeatures.AsyncToolSpecification specification = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> Mono
				.just(CallToolResult.builder().content(List.of(new TextContent("Test result"))).isError(false).build()))
			.build();

		assertThat(specification).isNotNull();
		assertThat(specification.tool()).isEqualTo(tool);
		assertThat(specification.callHandler()).isNotNull();
	}

	@Test
	void builderShouldThrowExceptionWhenToolIsNull() {
		assertThatThrownBy(() -> McpServerFeatures.AsyncToolSpecification.builder()
			.callHandler((exchange, request) -> Mono
				.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessage("Tool must not be null");
	}

	@Test
	void builderShouldThrowExceptionWhenCallToolIsNull() {
		Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.title("A test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		assertThatThrownBy(() -> McpServerFeatures.AsyncToolSpecification.builder().tool(tool).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Call handler function must not be null");
	}

	@Test
	void builderShouldAllowMethodChaining() {
		Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.title("A test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		McpServerFeatures.AsyncToolSpecification.Builder builder = McpServerFeatures.AsyncToolSpecification.builder();

		// Then - verify method chaining returns the same builder instance
		assertThat(builder.tool(tool)).isSameAs(builder);
		assertThat(builder.callHandler(
				(exchange, request) -> Mono.just(CallToolResult.builder().content(List.of()).isError(false).build())))
			.isSameAs(builder);
	}

	@Test
	void builtSpecificationShouldExecuteCallToolCorrectly() {
		Tool tool = McpSchema.Tool.builder()
			.name("calculator")
			.title("Simple calculator")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		String expectedResult = "42";

		McpServerFeatures.AsyncToolSpecification specification = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> Mono.just(
					CallToolResult.builder().content(List.of(new TextContent(expectedResult))).isError(false).build()))
			.build();

		CallToolRequest request = new CallToolRequest("calculator", Map.of());
		Mono<CallToolResult> resultMono = specification.callHandler().apply(null, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedResult);
			assertThat(result.isError()).isFalse();
		}).verifyComplete();
	}

	@Test
	void fromSyncShouldConvertSyncToolSpecificationCorrectly() {
		Tool tool = McpSchema.Tool.builder()
			.name("sync-tool")
			.title("A sync tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		String expectedResult = "sync result";

		// Create a sync tool specification
		McpServerFeatures.SyncToolSpecification syncSpec = McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> CallToolResult.builder()
				.content(List.of(new TextContent(expectedResult)))
				.isError(false)
				.build())
			.build();

		// Convert to async using fromSync
		McpServerFeatures.AsyncToolSpecification asyncSpec = McpServerFeatures.AsyncToolSpecification
			.fromSync(syncSpec);

		assertThat(asyncSpec).isNotNull();
		assertThat(asyncSpec.tool()).isEqualTo(tool);
		assertThat(asyncSpec.callHandler()).isNotNull();

		// Test that the converted async specification works correctly
		CallToolRequest request = new CallToolRequest("sync-tool", Map.of("param", "value"));
		Mono<CallToolResult> resultMono = asyncSpec.callHandler().apply(null, request);

		StepVerifier.create(resultMono).assertNext(result -> {
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

	@Nested
	class ToolNameValidation {

		private McpServerTransportProvider transportProvider;

		private final Logger logger = (Logger) LoggerFactory.getLogger(ToolNameValidator.class);

		private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

		@BeforeEach
		void setUp() {
			transportProvider = mock(McpServerTransportProvider.class);
			System.clearProperty(ToolNameValidator.STRICT_VALIDATION_PROPERTY);
			logAppender.start();
			logger.addAppender(logAppender);
		}

		@AfterEach
		void tearDown() {
			System.clearProperty(ToolNameValidator.STRICT_VALIDATION_PROPERTY);
			logger.detachAppender(logAppender);
			logAppender.stop();
		}

		@Test
		void defaultShouldThrowOnInvalidName() {
			Tool invalidTool = Tool.builder().name("invalid tool name").build();

			assertThatThrownBy(
					() -> McpServer.async(transportProvider).toolCall(invalidTool, (exchange, request) -> null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid characters");
		}

		@Test
		void lenientDefaultShouldLogOnInvalidName() {
			System.setProperty(ToolNameValidator.STRICT_VALIDATION_PROPERTY, "false");
			Tool invalidTool = Tool.builder().name("invalid tool name").build();

			assertThatCode(() -> McpServer.async(transportProvider).toolCall(invalidTool, (exchange, request) -> null))
				.doesNotThrowAnyException();
			assertThat(logAppender.list).hasSize(1);
		}

		@Test
		void lenientConfigurationShouldLogOnInvalidName() {
			Tool invalidTool = Tool.builder().name("invalid tool name").build();

			assertThatCode(() -> McpServer.async(transportProvider)
				.strictToolNameValidation(false)
				.toolCall(invalidTool, (exchange, request) -> null)).doesNotThrowAnyException();
			assertThat(logAppender.list).hasSize(1);
		}

		@Test
		void serverConfigurationShouldOverrideDefault() {
			System.setProperty(ToolNameValidator.STRICT_VALIDATION_PROPERTY, "false");
			Tool invalidTool = Tool.builder().name("invalid tool name").build();

			assertThatThrownBy(() -> McpServer.async(transportProvider)
				.strictToolNameValidation(true)
				.toolCall(invalidTool, (exchange, request) -> null)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid characters");
		}

	}

}
