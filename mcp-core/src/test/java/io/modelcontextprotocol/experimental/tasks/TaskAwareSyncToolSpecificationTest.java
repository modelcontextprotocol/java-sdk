/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetTaskResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Task;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * Tests for {@link TaskAwareSyncToolSpecification} and its builder.
 */
class TaskAwareSyncToolSpecificationTest extends
		AbstractTaskAwareToolSpecificationTest<TaskAwareSyncToolSpecification, TaskAwareSyncToolSpecification.Builder> {

	@Override
	protected TaskAwareSyncToolSpecification.Builder createBuilder() {
		return TaskAwareSyncToolSpecification.builder();
	}

	@Override
	protected TaskAwareSyncToolSpecification.Builder withMinimalCreateTaskHandler(
			TaskAwareSyncToolSpecification.Builder builder) {
		return builder.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build());
	}

	@Override
	protected TaskAwareSyncToolSpecification build(TaskAwareSyncToolSpecification.Builder builder) {
		return builder.build();
	}

	@Override
	protected Tool getTool(TaskAwareSyncToolSpecification spec) {
		return spec.tool();
	}

	@Override
	protected JsonSchema getEmptyInputSchema() {
		return TaskDefaults.EMPTY_INPUT_SCHEMA;
	}

	// ------------------------------------------
	// Sync-Specific Builder Tests
	// ------------------------------------------

	@Test
	void builderShouldAllowMethodChaining() {
		TaskAwareSyncToolSpecification.Builder builder = TaskAwareSyncToolSpecification.builder();

		// Verify method chaining returns the same builder instance
		assertThat(builder.name("test")).isSameAs(builder);
		assertThat(builder.description("desc")).isSameAs(builder);
		assertThat(builder.inputSchema(TEST_SCHEMA)).isSameAs(builder);
		assertThat(builder.taskSupportMode(TaskSupportMode.OPTIONAL)).isSameAs(builder);
		assertThat(builder.annotations(new ToolAnnotations(null, null, null, null, null, null))).isSameAs(builder);
		assertThat(builder.createTaskHandler((args, extra) -> null)).isSameAs(builder);
		assertThat(builder.getTaskHandler((exchange, request) -> null)).isSameAs(builder);
		assertThat(builder.getTaskResultHandler((exchange, request) -> null)).isSameAs(builder);
	}

	@Test
	void builderShouldCreateSpecificationWithAllOptionalHandlers() {
		SyncGetTaskHandler getTaskHandler = (exchange, request) -> GetTaskResult.builder()
			.taskId("task-123")
			.status(McpSchema.TaskStatus.COMPLETED)
			.statusMessage("done")
			.createdAt("now")
			.lastUpdatedAt("now")
			.build();

		SyncGetTaskResultHandler getTaskResultHandler = (exchange,
				request) -> CallToolResult.builder().addTextContent("custom result").build();

		TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
			.name("full-tool")
			.description("A tool with all handlers")
			.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build())
			.getTaskHandler(getTaskHandler)
			.getTaskResultHandler(getTaskResultHandler)
			.build();

		assertThat(spec.getTaskHandler()).isNotNull();
		assertThat(spec.getTaskResultHandler()).isNotNull();
	}

	@Test
	void builtSpecificationShouldExecuteCreateTaskHandlerCorrectly() {
		String expectedTaskId = "created-task-456";

		TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
			.name("creator-tool")
			.createTaskHandler((args, extra) -> {
				Task task = Task.builder()
					.taskId(expectedTaskId)
					.status(McpSchema.TaskStatus.WORKING)
					.statusMessage("Starting...")
					.createdAt("now")
					.lastUpdatedAt("now")
					.build();
				return McpSchema.CreateTaskResult.builder().task(task).build();
			})
			.build();

		McpSchema.CreateTaskResult result = spec.createTaskHandler().createTask(Map.of(), null);

		assertThat(result).isNotNull();
		assertThat(result.task()).isNotNull();
		assertThat(result.task().taskId()).isEqualTo(expectedTaskId);
		assertThat(result.task().status()).isEqualTo(McpSchema.TaskStatus.WORKING);
	}

	@Test
	void callHandlerShouldThrowForNonTaskCalls() {
		TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
			.name("task-only-tool")
			.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build())
			.build();

		assertThatThrownBy(
				() -> spec.callHandler().apply(null, new McpSchema.CallToolRequest("task-only-tool", Map.of())))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("requires task-augmented execution");
	}

	@Test
	void getTaskShouldExecuteCorrectly() {
		String customMessage = "Custom status from handler";

		TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
			.name("custom-get-tool")
			.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build())
			.getTaskHandler((exchange, request) -> GetTaskResult.builder()
				.taskId(request.taskId())
				.status(McpSchema.TaskStatus.WORKING)
				.statusMessage(customMessage)
				.createdAt("now")
				.lastUpdatedAt("now")
				.build())
			.build();

		GetTaskResult result = spec.getTaskHandler()
			.handle(null, McpSchema.GetTaskRequest.builder().taskId("test-task-id").build());

		assertThat(result).isNotNull();
		assertThat(result.taskId()).isEqualTo("test-task-id");
		assertThat(result.statusMessage()).isEqualTo(customMessage);
	}

	@Test
	void getTaskResultShouldExecuteCorrectly() {
		String expectedResult = "Custom result content";

		TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
			.name("custom-result-tool")
			.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build())
			.getTaskResultHandler(
					(exchange, request) -> CallToolResult.builder().addTextContent(expectedResult).build())
			.build();

		McpSchema.Result result = spec.getTaskResultHandler()
			.handle(null, McpSchema.GetTaskPayloadRequest.builder().taskId("test-task-id").build());

		assertThat(result).isNotNull();
		assertThat(result).isInstanceOf(CallToolResult.class);
		CallToolResult callResult = (CallToolResult) result;
		assertThat(callResult.content()).hasSize(1);
		assertThat(((TextContent) callResult.content().get(0)).text()).isEqualTo(expectedResult);
	}

	// ------------------------------------------
	// Exception Handling Tests
	// ------------------------------------------

	@Test
	void createTaskHandlerExceptionShouldPropagate() {
		TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
			.name("failing-create-task")
			.createTaskHandler((args, extra) -> {
				throw new RuntimeException("createTask failed");
			})
			.build();

		assertThatThrownBy(() -> spec.createTaskHandler().createTask(Map.of(), null))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("createTask failed");
	}

	@Test
	void getTaskHandlerExceptionShouldPropagate() {
		TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
			.name("failing-get-task")
			.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build())
			.getTaskHandler((exchange, request) -> {
				throw new RuntimeException("getTask failed");
			})
			.build();

		assertThat(spec.getTaskHandler()).isNotNull();
		assertThatThrownBy(() -> spec.getTaskHandler().handle(null, null)).isInstanceOf(RuntimeException.class)
			.hasMessage("getTask failed");
	}

	@Test
	void getTaskResultHandlerExceptionShouldPropagate() {
		TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
			.name("failing-get-result")
			.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build())
			.getTaskResultHandler((exchange, request) -> {
				throw new RuntimeException("getTaskResult failed");
			})
			.build();

		assertThat(spec.getTaskResultHandler()).isNotNull();
		assertThatThrownBy(() -> spec.getTaskResultHandler().handle(null, null)).isInstanceOf(RuntimeException.class)
			.hasMessage("getTaskResult failed");
	}

}
