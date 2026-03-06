/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryToolsRepositoryTests {

	private final Logger logger = (Logger) LoggerFactory.getLogger(InMemoryToolsRepository.class);

	private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		logAppender.start();
		logger.addAppender(logAppender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(logAppender);
		logAppender.stop();
	}

	@Test
	void addToolShouldReplaceExistingToolAndLogWarning() {
		InMemoryToolsRepository repository = new InMemoryToolsRepository();

		repository.addTool(toolSpec("duplicate-tool", "first"));
		repository.addTool(toolSpec("duplicate-tool", "second"));

		ToolsListResult result = repository.listTools(null, null).block();
		assertThat(result).isNotNull();
		assertThat(result.tools()).hasSize(1);
		assertThat(result.tools().get(0).description()).isEqualTo("second");
		assertThat(logAppender.list).hasSize(1);
		assertThat(logAppender.list.get(0).getFormattedMessage())
			.contains("Replace existing Tool with name 'duplicate-tool'");
	}

	@Test
	void addToolShouldNotLogWarningForFirstRegistration() {
		InMemoryToolsRepository repository = new InMemoryToolsRepository();

		repository.addTool(toolSpec("new-tool", "desc"));

		assertThat(logAppender.list).isEmpty();
	}

	@Test
	void removeToolShouldReturnTrueOnlyWhenToolExists() {
		InMemoryToolsRepository repository = new InMemoryToolsRepository();
		repository.addTool(toolSpec("remove-me", "desc"));

		assertThat(repository.removeTool("remove-me")).isTrue();
		assertThat(repository.removeTool("remove-me")).isFalse();
	}

	private McpServerFeatures.AsyncToolSpecification toolSpec(String name, String description) {
		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(name)
			.description(description)
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		return McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> Mono
				.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build();
	}

}
