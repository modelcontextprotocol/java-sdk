/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.ForkJoinPool;

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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Tests for {@link TaskAwareAsyncToolSpecification} and its builder.
 */
class TaskAwareAsyncToolSpecificationTest extends
		AbstractTaskAwareToolSpecificationTest<TaskAwareAsyncToolSpecification, TaskAwareAsyncToolSpecification.Builder> {

	@Override
	protected TaskAwareAsyncToolSpecification.Builder createBuilder() {
		return TaskAwareAsyncToolSpecification.builder();
	}

	@Override
	protected TaskAwareAsyncToolSpecification.Builder withMinimalCreateTaskHandler(
			TaskAwareAsyncToolSpecification.Builder builder) {
		return builder.createTaskHandler((args, extra) -> Mono.just(McpSchema.CreateTaskResult.builder().build()));
	}

	@Override
	protected TaskAwareAsyncToolSpecification build(TaskAwareAsyncToolSpecification.Builder builder) {
		return builder.build();
	}

	@Override
	protected Tool getTool(TaskAwareAsyncToolSpecification spec) {
		return spec.tool();
	}

	@Override
	protected JsonSchema getEmptyInputSchema() {
		return TaskDefaults.EMPTY_INPUT_SCHEMA;
	}

	// ------------------------------------------
	// Async-Specific Builder Tests
	// ------------------------------------------

	@Test
	void builderShouldAllowMethodChaining() {
		TaskAwareAsyncToolSpecification.Builder builder = TaskAwareAsyncToolSpecification.builder();

		// Verify method chaining returns the same builder instance
		assertThat(builder.name("test")).isSameAs(builder);
		assertThat(builder.description("desc")).isSameAs(builder);
		assertThat(builder.inputSchema(TEST_SCHEMA)).isSameAs(builder);
		assertThat(builder.taskSupportMode(TaskSupportMode.OPTIONAL)).isSameAs(builder);
		assertThat(builder.annotations(new ToolAnnotations(null, null, null, null, null, null))).isSameAs(builder);
		assertThat(builder.createTaskHandler((args, extra) -> Mono.empty())).isSameAs(builder);
		assertThat(builder.getTaskHandler((exchange, request) -> Mono.empty())).isSameAs(builder);
		assertThat(builder.getTaskResultHandler((exchange, request) -> Mono.empty())).isSameAs(builder);
	}

	@Test
	void builderShouldCreateSpecificationWithAllOptionalHandlers() {
		GetTaskHandler getTaskHandler = (exchange,
				request) -> Mono.just(GetTaskResult.builder()
					.taskId("task-123")
					.status(McpSchema.TaskStatus.COMPLETED)
					.statusMessage("done")
					.createdAt("now")
					.lastUpdatedAt("now")
					.build());

		GetTaskResultHandler getTaskResultHandler = (exchange, request) -> Mono
			.just(CallToolResult.builder().addTextContent("custom result").build());

		TaskAwareAsyncToolSpecification spec = TaskAwareAsyncToolSpecification.builder()
			.name("full-tool")
			.description("A tool with all handlers")
			.createTaskHandler((args, extra) -> Mono.just(McpSchema.CreateTaskResult.builder().build()))
			.getTaskHandler(getTaskHandler)
			.getTaskResultHandler(getTaskResultHandler)
			.build();

		assertThat(spec.getTaskHandler()).isNotNull();
		assertThat(spec.getTaskResultHandler()).isNotNull();
	}

	@Test
	void builtSpecificationShouldExecuteCreateTaskHandlerCorrectly() {
		String expectedTaskId = "created-task-456";

		TaskAwareAsyncToolSpecification spec = TaskAwareAsyncToolSpecification.builder()
			.name("creator-tool")
			.createTaskHandler((args, extra) -> {
				Task task = Task.builder()
					.taskId(expectedTaskId)
					.status(McpSchema.TaskStatus.WORKING)
					.statusMessage("Starting...")
					.createdAt("now")
					.lastUpdatedAt("now")
					.build();
				return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
			})
			.build();

		Mono<McpSchema.CreateTaskResult> resultMono = spec.createTaskHandler().createTask(Map.of(), null);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.task()).isNotNull();
			assertThat(result.task().taskId()).isEqualTo(expectedTaskId);
			assertThat(result.task().status()).isEqualTo(McpSchema.TaskStatus.WORKING);
		}).verifyComplete();
	}

	@Test
	void callHandlerShouldReturnErrorForNonTaskCalls() {
		TaskAwareAsyncToolSpecification spec = TaskAwareAsyncToolSpecification.builder()
			.name("task-only-tool")
			.createTaskHandler((args, extra) -> Mono.just(McpSchema.CreateTaskResult.builder().build()))
			.build();

		Mono<CallToolResult> resultMono = spec.callHandler()
			.apply(null, new McpSchema.CallToolRequest("task-only-tool", Map.of()));

		StepVerifier.create(resultMono)
			.expectErrorMatches(ex -> ex instanceof UnsupportedOperationException
					&& ex.getMessage().contains("requires task-augmented execution"))
			.verify();
	}

	// ------------------------------------------
	// fromSync Conversion Tests
	// ------------------------------------------

	@Test
	void fromSyncShouldConvertSyncSpecificationCorrectly() {
		String expectedTaskId = "sync-task-789";

		// Handler that doesn't use 'extra' parameter (avoids null issues in test)
		TaskAwareSyncToolSpecification syncSpec = TaskAwareSyncToolSpecification.builder()
			.name("sync-task-tool")
			.description("A sync task tool")
			.taskSupportMode(TaskSupportMode.REQUIRED)
			.createTaskHandler((args, extra) -> {
				// Note: Not using 'extra' here to avoid NPE in test context
				Task task = Task.builder()
					.taskId(expectedTaskId)
					.status(McpSchema.TaskStatus.WORKING)
					.createdAt("now")
					.lastUpdatedAt("now")
					.build();
				return McpSchema.CreateTaskResult.builder().task(task).build();
			})
			.build();

		TaskAwareAsyncToolSpecification asyncSpec = TaskAwareAsyncToolSpecification.fromSync(syncSpec,
				ForkJoinPool.commonPool());

		assertThat(asyncSpec).isNotNull();
		assertThat(asyncSpec.tool().name()).isEqualTo("sync-task-tool");
		assertThat(asyncSpec.tool().description()).isEqualTo("A sync task tool");
		assertThat(asyncSpec.tool().execution().taskSupport()).isEqualTo(TaskSupportMode.REQUIRED);
		assertThat(asyncSpec.createTaskHandler()).isNotNull();
		assertThat(asyncSpec.callHandler()).isNotNull();
	}

	@Test
	void fromSyncShouldConvertOptionalHandlers() {
		String customMessage = "Custom handler invoked";

		TaskAwareSyncToolSpecification syncSpec = TaskAwareSyncToolSpecification.builder()
			.name("full-sync-tool")
			.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build())
			.getTaskHandler((exchange, request) -> GetTaskResult.builder()
				.taskId(request.taskId())
				.status(McpSchema.TaskStatus.COMPLETED)
				.statusMessage(customMessage)
				.createdAt("now")
				.lastUpdatedAt("now")
				.build())
			.getTaskResultHandler(
					(exchange, request) -> CallToolResult.builder().addTextContent("Custom result content").build())
			.build();

		TaskAwareAsyncToolSpecification asyncSpec = TaskAwareAsyncToolSpecification.fromSync(syncSpec,
				ForkJoinPool.commonPool());

		assertThat(asyncSpec.getTaskHandler()).isNotNull();
		assertThat(asyncSpec.getTaskResultHandler()).isNotNull();

		// Test getTaskHandler
		Mono<GetTaskResult> getTaskMono = asyncSpec.getTaskHandler()
			.handle(null, McpSchema.GetTaskRequest.builder().taskId("test-task").build());

		StepVerifier.create(getTaskMono).assertNext(result -> {
			assertThat(result.statusMessage()).isEqualTo(customMessage);
		}).verifyComplete();

		// Test getTaskResultHandler
		Mono<McpSchema.ServerTaskPayloadResult> getResultMono = asyncSpec.getTaskResultHandler()
			.handle(null, McpSchema.GetTaskPayloadRequest.builder().taskId("test-task").build());

		StepVerifier.create(getResultMono).assertNext(result -> {
			CallToolResult callResult = (CallToolResult) result;
			assertThat(callResult.content()).hasSize(1);
			assertThat(((TextContent) callResult.content().get(0)).text()).isEqualTo("Custom result content");
		}).verifyComplete();
	}

	@Test
	void fromSyncShouldThrowWhenSyncSpecIsNull() {
		assertThatThrownBy(() -> TaskAwareAsyncToolSpecification.fromSync(null, ForkJoinPool.commonPool()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sync specification must not be null");
	}

	@Test
	void fromSyncShouldThrowWhenExecutorIsNull() {
		TaskAwareSyncToolSpecification syncSpec = TaskAwareSyncToolSpecification.builder()
			.name("test")
			.createTaskHandler((args, extra) -> McpSchema.CreateTaskResult.builder().build())
			.build();

		assertThatThrownBy(() -> TaskAwareAsyncToolSpecification.fromSync(syncSpec, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("executor must not be null");
	}

	// ------------------------------------------
	// Exception Handling Tests
	// ------------------------------------------

	@Test
	void createTaskHandlerExceptionShouldPropagate() {
		TaskAwareAsyncToolSpecification spec = TaskAwareAsyncToolSpecification.builder()
			.name("failing-create-task")
			.createTaskHandler((args, extra) -> Mono.error(new RuntimeException("createTask failed")))
			.build();

		StepVerifier.create(spec.createTaskHandler().createTask(Map.of(), null))
			.expectErrorMatches(e -> e instanceof RuntimeException && e.getMessage().equals("createTask failed"))
			.verify();
	}

	@Test
	void getTaskHandlerExceptionShouldPropagate() {
		TaskAwareAsyncToolSpecification spec = TaskAwareAsyncToolSpecification.builder()
			.name("failing-get-task")
			.createTaskHandler((args, extra) -> Mono.just(McpSchema.CreateTaskResult.builder().build()))
			.getTaskHandler((exchange, request) -> Mono.error(new RuntimeException("getTask failed")))
			.build();

		assertThat(spec.getTaskHandler()).isNotNull();
		StepVerifier.create(spec.getTaskHandler().handle(null, null))
			.expectErrorMatches(e -> e instanceof RuntimeException && e.getMessage().equals("getTask failed"))
			.verify();
	}

	@Test
	void getTaskResultHandlerExceptionShouldPropagate() {
		TaskAwareAsyncToolSpecification spec = TaskAwareAsyncToolSpecification.builder()
			.name("failing-get-result")
			.createTaskHandler((args, extra) -> Mono.just(McpSchema.CreateTaskResult.builder().build()))
			.getTaskResultHandler((exchange, request) -> Mono.error(new RuntimeException("getTaskResult failed")))
			.build();

		assertThat(spec.getTaskResultHandler()).isNotNull();
		StepVerifier.create(spec.getTaskResultHandler().handle(null, null))
			.expectErrorMatches(e -> e instanceof RuntimeException && e.getMessage().equals("getTaskResult failed"))
			.verify();
	}

}
