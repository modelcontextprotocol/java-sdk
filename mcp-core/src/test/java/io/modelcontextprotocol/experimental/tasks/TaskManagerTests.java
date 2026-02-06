/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link TaskManager}, {@link DefaultTaskManager}, and
 * {@link NullTaskManager}.
 */
class TaskManagerTests {

	@Nested
	@DisplayName("NullTaskManager")
	class NullTaskManagerTests {

		private TaskManager nullTaskManager;

		@BeforeEach
		void setUp() {
			nullTaskManager = NullTaskManager.getInstance();
		}

		@Test
		void testGetInstanceReturnsSingleton() {
			assertThat(NullTaskManager.getInstance()).isSameAs(nullTaskManager);
		}

		@Test
		void testTaskStoreReturnsEmpty() {
			assertThat(nullTaskManager.taskStore()).isEmpty();
		}

		@Test
		void testMessageQueueReturnsEmpty() {
			assertThat(nullTaskManager.messageQueue()).isEmpty();
		}

		@Test
		void testDefaultPollIntervalReturnsDefault() {
			assertThat(nullTaskManager.defaultPollInterval())
				.isEqualTo(Duration.ofMillis(TaskDefaults.DEFAULT_POLL_INTERVAL_MS));
		}

		@Test
		void testProcessInboundRequestPassesThrough() {
			JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call", "req-1", null);

			AtomicBoolean sendNotificationCalled = new AtomicBoolean(false);
			TaskManager.InboundRequestContext ctx = new TaskManager.InboundRequestContext("session-1",
					(notification, options) -> {
						sendNotificationCalled.set(true);
						return Mono.empty();
					}, new TaskManager.SendRequestFunction() {
						@Override
						public <T extends Result> Mono<T> send(McpSchema.Request request, Class<T> resultType,
								TaskManager.RequestOptions options) {
							return Mono.empty();
						}
					});

			TaskManager.InboundRequestResult result = nullTaskManager.processInboundRequest(request, ctx);

			assertThat(result.hasTaskCreationParams()).isFalse();

			// Verify wrapped sendNotification still works - use a real notification
			// instance
			// since McpSchema.Notification is a sealed interface that cannot be mocked
			McpSchema.ProgressNotification testNotification = new McpSchema.ProgressNotification(1, 50.0, 100.0, null);
			result.sendNotification().apply(testNotification).block();
			assertThat(sendNotificationCalled.get()).isTrue();
		}

		@Test
		void testProcessOutboundRequestNeverQueues() {
			JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call", "req-1", null);
			TaskManager.RequestOptions options = TaskManager.RequestOptions.empty();

			TaskManager.OutboundRequestResult result = nullTaskManager.processOutboundRequest(request, options, 1,
					response -> {
					}, error -> {
					});

			assertThat(result.queued()).isFalse();
		}

		@Test
		void testProcessInboundResponseNeverConsumes() {
			JSONRPCResponse response = new JSONRPCResponse(McpSchema.JSONRPC_VERSION, "req-1", Map.of(), null);

			TaskManager.InboundResponseResult result = nullTaskManager.processInboundResponse(response, 1);

			assertThat(result.consumed()).isFalse();
		}

		@Test
		void testProcessOutboundNotificationNeverQueues() {
			McpSchema.ProgressNotification notification = new McpSchema.ProgressNotification(1, 50.0, 100.0, null);

			StepVerifier.create(nullTaskManager.processOutboundNotification(notification, null))
				.consumeNextWith(result -> {
					assertThat(result.queued()).isFalse();
				})
				.verifyComplete();
		}

		@Test
		void testOnCloseDoesNothing() {
			// Should not throw
			nullTaskManager.onClose();
		}

	}

	@Nested
	@DisplayName("DefaultTaskManager")
	class DefaultTaskManagerTests {

		private TaskStore<?> mockTaskStore;

		private TaskMessageQueue mockMessageQueue;

		private TaskManagerHost mockHost;

		private DefaultTaskManager taskManager;

		@BeforeEach
		void setUp() {
			mockTaskStore = mock(TaskStore.class);
			mockMessageQueue = mock(TaskMessageQueue.class);
			mockHost = mock(TaskManagerHost.class);

			TaskManagerOptions options = TaskManagerOptions.builder()
				.store(mockTaskStore)
				.messageQueue(mockMessageQueue)
				.defaultPollInterval(Duration.ofMillis(500))
				.build();

			taskManager = new DefaultTaskManager(options);
		}

		@Test
		void testTaskStoreReturnsConfiguredStore() {
			assertThat(taskManager.taskStore()).isPresent().contains(mockTaskStore);
		}

		@Test
		void testMessageQueueReturnsConfiguredQueue() {
			assertThat(taskManager.messageQueue()).isPresent().contains(mockMessageQueue);
		}

		@Test
		void testDefaultPollIntervalReturnsConfiguredInterval() {
			assertThat(taskManager.defaultPollInterval()).isEqualTo(Duration.ofMillis(500));
		}

		@Test
		void testProcessInboundRequestExtractsTaskCreationParams() {
			// Request with task creation params
			Map<String, Object> params = new HashMap<>();
			Map<String, Object> taskParams = new HashMap<>();
			taskParams.put("ttl", 60000L);
			params.put("task", taskParams);

			JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call", "req-1", params);

			TaskManager.InboundRequestContext ctx = createInboundRequestContext();

			TaskManager.InboundRequestResult result = taskManager.processInboundRequest(request, ctx);

			assertThat(result.hasTaskCreationParams()).isTrue();
		}

		@Test
		void testProcessInboundRequestExtractsRelatedTaskId() {
			// Request with related task metadata
			Map<String, Object> params = new HashMap<>();
			Map<String, Object> meta = new HashMap<>();
			Map<String, Object> relatedTask = new HashMap<>();
			relatedTask.put("taskId", "task-123");
			meta.put("relatedTask", relatedTask);
			params.put("_meta", meta);

			JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call", "req-1", params);

			TaskManager.InboundRequestContext ctx = createInboundRequestContext();

			TaskManager.InboundRequestResult result = taskManager.processInboundRequest(request, ctx);

			// Related task ID is extracted and used for notification/request wrapping,
			// but is not directly exposed on InboundRequestResult
			assertThat(result.sendNotification()).isNotNull();
			assertThat(result.sendRequest()).isNotNull();
		}

		@Test
		void testProcessOutboundRequestQueuesWhenRelatedTaskSet() {
			when(mockMessageQueue.enqueue(anyString(), any())).thenReturn(Mono.empty());

			JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "sampling/createMessage", "req-1",
					null);
			TaskManager.RequestOptions options = new TaskManager.RequestOptions(null,
					new TaskManager.RelatedTaskInfo("task-123"));

			AtomicBoolean responseHandlerCalled = new AtomicBoolean(false);

			TaskManager.OutboundRequestResult result = taskManager.processOutboundRequest(request, options, 1,
					response -> responseHandlerCalled.set(true), error -> {
					});

			assertThat(result.queued()).isTrue();
		}

		@Test
		void testProcessOutboundRequestDoesNotQueueWithoutRelatedTask() {
			JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "sampling/createMessage", "req-1",
					null);
			TaskManager.RequestOptions options = TaskManager.RequestOptions.empty();

			TaskManager.OutboundRequestResult result = taskManager.processOutboundRequest(request, options, 1,
					response -> {
					}, error -> {
					});

			assertThat(result.queued()).isFalse();
		}

		@Test
		void testProcessInboundResponseResolvesQueuedRequest() {
			when(mockMessageQueue.enqueue(anyString(), any())).thenReturn(Mono.empty());

			// First, queue a request
			JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "sampling/createMessage", "req-1",
					null);
			TaskManager.RequestOptions options = new TaskManager.RequestOptions(null,
					new TaskManager.RelatedTaskInfo("task-123"));

			AtomicReference<Object> receivedResponse = new AtomicReference<>();
			taskManager.processOutboundRequest(request, options, "req-1", receivedResponse::set, error -> {
			});

			// Now simulate receiving a response
			Map<String, Object> resultData = Map.of("role", "assistant", "content", "Hello");
			JSONRPCResponse response = new JSONRPCResponse(McpSchema.JSONRPC_VERSION, "req-1", resultData, null);

			TaskManager.InboundResponseResult inboundResult = taskManager.processInboundResponse(response, "req-1");

			assertThat(inboundResult.consumed()).isTrue();
			assertThat(receivedResponse.get()).isEqualTo(resultData);
		}

		@Test
		void testProcessOutboundNotificationQueuesWhenRelatedTaskSet() {
			when(mockMessageQueue.enqueue(anyString(), any())).thenReturn(Mono.empty());

			McpSchema.ProgressNotification notification = new McpSchema.ProgressNotification(1, 50.0, 100.0, null);
			TaskManager.NotificationOptions options = TaskManager.NotificationOptions
				.withRelatedTask(new TaskManager.RelatedTaskInfo("task-123"));

			StepVerifier.create(taskManager.processOutboundNotification(notification, options))
				.consumeNextWith(result -> assertThat(result.queued()).isTrue())
				.verifyComplete();
		}

		@Test
		void testProcessOutboundNotificationDoesNotQueueWithoutRelatedTask() {
			McpSchema.ProgressNotification notification = new McpSchema.ProgressNotification(1, 50.0, 100.0, null);
			TaskManager.NotificationOptions options = TaskManager.NotificationOptions.empty();

			StepVerifier.create(taskManager.processOutboundNotification(notification, options))
				.consumeNextWith(result -> assertThat(result.queued()).isFalse())
				.verifyComplete();
		}

		@Test
		void testOnCloseClearsState() {
			when(mockMessageQueue.enqueue(anyString(), any())).thenReturn(Mono.empty());

			// Queue a request first
			JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "sampling/createMessage", "req-1",
					null);
			TaskManager.RequestOptions options = new TaskManager.RequestOptions(null,
					new TaskManager.RelatedTaskInfo("task-123"));

			taskManager.processOutboundRequest(request, options, "req-1", response -> {
			}, error -> {
			});

			// Close the manager
			taskManager.onClose();

			// Now the response should not be consumed (resolver was cleared)
			Map<String, Object> resultData = Map.of("role", "assistant");
			JSONRPCResponse response = new JSONRPCResponse(McpSchema.JSONRPC_VERSION, "req-1", resultData, null);

			TaskManager.InboundResponseResult inboundResult = taskManager.processInboundResponse(response, "req-1");

			assertThat(inboundResult.consumed()).isFalse();
		}

		private TaskManager.InboundRequestContext createInboundRequestContext() {
			return new TaskManager.InboundRequestContext("session-1", (notification, options) -> Mono.empty(),
					new TaskManager.SendRequestFunction() {
						@Override
						public <T extends Result> Mono<T> send(McpSchema.Request request, Class<T> resultType,
								TaskManager.RequestOptions options) {
							return Mono.empty();
						}
					});
		}

	}

	@Nested
	@DisplayName("TaskManagerOptions")
	class TaskManagerOptionsTests {

		@Test
		void testBuilderWithAllOptions() {
			TaskStore<?> mockStore = mock(TaskStore.class);
			TaskMessageQueue mockQueue = mock(TaskMessageQueue.class);

			TaskManagerOptions options = TaskManagerOptions.builder()
				.store(mockStore)
				.messageQueue(mockQueue)
				.defaultPollInterval(Duration.ofSeconds(2))
				.pollTimeout(Duration.ofMinutes(10))
				.build();

			assertThat(options.taskStore()).isSameAs(mockStore);
			assertThat(options.messageQueue()).isSameAs(mockQueue);
			assertThat(options.defaultPollInterval()).isEqualTo(Duration.ofSeconds(2));
			assertThat(options.pollTimeout()).isEqualTo(Duration.ofMinutes(10));
		}

		@Test
		void testBuilderWithDefaults() {
			TaskManagerOptions options = TaskManagerOptions.builder().build();

			assertThat(options.taskStore()).isNull();
			assertThat(options.messageQueue()).isNull();
			assertThat(options.defaultPollInterval())
				.isEqualTo(Duration.ofMillis(TaskDefaults.DEFAULT_POLL_INTERVAL_MS));
			assertThat(options.pollTimeout()).isNull();
		}

		@Test
		void testCreateTaskManagerReturnsDefaultWhenStoreConfigured() {
			TaskStore<?> mockStore = mock(TaskStore.class);

			TaskManagerOptions options = TaskManagerOptions.builder().store(mockStore).build();

			TaskManager manager = options.createTaskManager();

			assertThat(manager).isInstanceOf(DefaultTaskManager.class);
		}

		@Test
		void testCreateTaskManagerReturnsDefaultWhenQueueConfigured() {
			TaskMessageQueue mockQueue = mock(TaskMessageQueue.class);

			TaskManagerOptions options = TaskManagerOptions.builder().messageQueue(mockQueue).build();

			TaskManager manager = options.createTaskManager();

			assertThat(manager).isInstanceOf(DefaultTaskManager.class);
		}

		@Test
		void testCreateTaskManagerReturnsNullWhenNothingConfigured() {
			TaskManagerOptions options = TaskManagerOptions.builder().build();

			TaskManager manager = options.createTaskManager();

			assertThat(manager).isSameAs(NullTaskManager.getInstance());
		}

	}

	@Nested
	@DisplayName("TaskHandlerRegistry")
	class TaskHandlerRegistryTests {

		private TaskHandlerRegistry registry;

		@BeforeEach
		void setUp() {
			registry = new TaskHandlerRegistry();
		}

		@Test
		void testRegisterAndInvokeHandler() {
			// Register a handler that echoes the method and task ID back
			registry.registerHandler(McpSchema.METHOD_TASKS_GET, (request, context) -> {
				String taskId = extractTaskId(request);
				return Mono.just(McpSchema.GetTaskResult.builder()
					.taskId(taskId)
					.status(McpSchema.TaskStatus.COMPLETED)
					.statusMessage("handled by registry")
					.createdAt("2026-01-01T00:00:00Z")
					.lastUpdatedAt("2026-01-01T00:00:00Z")
					.build());
			});

			TaskManagerHost.TaskHandlerContext context = mock(TaskManagerHost.TaskHandlerContext.class);
			when(context.sessionId()).thenReturn(null);

			// Invoke the handler through the registry using raw params (simulating
			// what the transport layer would provide)
			Map<String, Object> params = Map.of("taskId", "test-123");
			StepVerifier
				.create(registry.<McpSchema.GetTaskResult>invokeHandler(McpSchema.METHOD_TASKS_GET, params, context))
				.expectNextMatches(result -> "handled by registry".equals(result.statusMessage()))
				.verifyComplete();
		}

		@Test
		void testInvokeHandlerErrorsOnUnregisteredMethod() {
			TaskManagerHost.TaskHandlerContext context = mock(TaskManagerHost.TaskHandlerContext.class);

			StepVerifier
				.create(registry.invokeHandler(McpSchema.METHOD_TASKS_GET, Map.of("taskId", "test-123"), context))
				.expectErrorMatches(
						e -> e instanceof IllegalStateException && e.getMessage().contains("No handler registered for"))
				.verify();
		}

		@Test
		void testWireHandlersWithAllCapabilities() {
			// Register all 4 handlers
			TaskManagerHost.TaskRequestHandler dummyHandler = (req, ctx) -> Mono.just(mock(Result.class));
			registry.registerHandler(McpSchema.METHOD_TASKS_GET, dummyHandler);
			registry.registerHandler(McpSchema.METHOD_TASKS_RESULT, dummyHandler);
			registry.registerHandler(McpSchema.METHOD_TASKS_LIST, dummyHandler);
			registry.registerHandler(McpSchema.METHOD_TASKS_CANCEL, dummyHandler);

			// Wire with all capabilities enabled
			List<String> wiredMethods = new ArrayList<>();
			registry.wireHandlers(true, true, (method, handler) -> method, // adapter
																			// returns
																			// method name
					(method, adapted) -> wiredMethods.add(adapted));

			assertThat(wiredMethods).containsExactly(McpSchema.METHOD_TASKS_GET, McpSchema.METHOD_TASKS_RESULT,
					McpSchema.METHOD_TASKS_LIST, McpSchema.METHOD_TASKS_CANCEL);
		}

		@Test
		void testWireHandlersWithoutListOrCancel() {
			// Register all 4 handlers
			TaskManagerHost.TaskRequestHandler dummyHandler = (req, ctx) -> Mono.just(mock(Result.class));
			registry.registerHandler(McpSchema.METHOD_TASKS_GET, dummyHandler);
			registry.registerHandler(McpSchema.METHOD_TASKS_RESULT, dummyHandler);
			registry.registerHandler(McpSchema.METHOD_TASKS_LIST, dummyHandler);
			registry.registerHandler(McpSchema.METHOD_TASKS_CANCEL, dummyHandler);

			// Wire with only get/result capabilities (list=false, cancel=false)
			List<String> wiredMethods = new ArrayList<>();
			registry.wireHandlers(false, false, (method, handler) -> method,
					(method, adapted) -> wiredMethods.add(adapted));

			assertThat(wiredMethods).containsExactly(McpSchema.METHOD_TASKS_GET, McpSchema.METHOD_TASKS_RESULT);
		}

		@Test
		void testWireHandlersSkipsMissingHandlers() {
			// Only register tasks/get — tasks/result is missing
			registry.registerHandler(McpSchema.METHOD_TASKS_GET, (req, ctx) -> Mono.just(mock(Result.class)));

			List<String> wiredMethods = new ArrayList<>();
			registry.wireHandlers(false, false, (method, handler) -> method,
					(method, adapted) -> wiredMethods.add(adapted));

			// Only tasks/get should be wired; tasks/result logs a warning but doesn't
			// fail
			assertThat(wiredMethods).containsExactly(McpSchema.METHOD_TASKS_GET);
		}

		@Test
		void testGetResultTypeRefForElicitation() {
			TypeRef<? extends McpSchema.Result> typeRef = TaskHandlerRegistry
				.getResultTypeRefForMethod(McpSchema.METHOD_ELICITATION_CREATE);
			assertThat(typeRef).isNotNull();
		}

		@Test
		void testGetResultTypeRefForSampling() {
			TypeRef<? extends McpSchema.Result> typeRef = TaskHandlerRegistry
				.getResultTypeRefForMethod(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE);
			assertThat(typeRef).isNotNull();
		}

		@Test
		void testGetResultTypeRefThrowsForUnknownMethod() {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
				TaskHandlerRegistry.getResultTypeRefForMethod("unknown/method");
			}).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported side-channel method");
		}

		/**
		 * Extracts taskId from raw request params (which arrive as part of the
		 * JSONRPCRequest).
		 */
		private String extractTaskId(JSONRPCRequest request) {
			if (request.params() instanceof Map<?, ?> map) {
				Object taskId = map.get("taskId");
				return taskId != null ? taskId.toString() : null;
			}
			return null;
		}

	}

	@Nested
	@DisplayName("TaskManager bind → TaskHandlerRegistry integration")
	class TaskManagerRegistryIntegrationTests {

		@SuppressWarnings("unchecked")
		@Test
		void testBindRegistersInvokableHandlersInRegistry() {
			// Verify the full chain: TaskManager.bind() → registerHandler() →
			// registry stores handler → invokeHandler() dispatches correctly
			TaskStore<?> mockTaskStore = mock(TaskStore.class);
			// Stub store methods to return Mono.empty() (not null) so handlers
			// can execute without NPE — we expect controlled errors, not NPEs
			when(mockTaskStore.getTask(any(), any())).thenReturn(Mono.empty());
			when(mockTaskStore.listTasks(any(), any())).thenReturn(Mono.empty());
			when(mockTaskStore.requestCancellation(any(), any())).thenReturn(Mono.empty());
			TaskHandlerRegistry registry = new TaskHandlerRegistry();

			// Create a TaskManagerHost that delegates to our registry
			TaskManagerHost host = new TaskManagerHost() {
				@Override
				public <T extends Result> Mono<T> request(McpSchema.Request request, Class<T> resultType) {
					return Mono.empty();
				}

				@Override
				public Mono<Void> notification(McpSchema.Notification notification) {
					return Mono.empty();
				}

				@Override
				public void registerHandler(String method, TaskRequestHandler handler) {
					registry.registerHandler(method, handler);
				}

				@Override
				public <T extends Result> Mono<T> invokeCustomTaskHandler(String taskId, String method,
						McpSchema.Request request, TaskHandlerContext context, Class<T> resultType) {
					return Mono.empty();
				}
			};

			TaskManagerOptions options = TaskManagerOptions.builder().store(mockTaskStore).build();
			DefaultTaskManager taskManager = new DefaultTaskManager(options);
			taskManager.bind(host);

			// Verify all 4 methods have handlers registered in the registry
			TaskManagerHost.TaskHandlerContext context = mock(TaskManagerHost.TaskHandlerContext.class);
			when(context.sessionId()).thenReturn(null);

			// tasks/get should be invokable (will fail looking up the task in the
			// mock store, but the handler itself is registered and executing)
			StepVerifier
				.create(registry.invokeHandler(McpSchema.METHOD_TASKS_GET, Map.of("taskId", "test-task-123"), context))
				.expectError()
				.verify();

			// tasks/result should also be invokable
			StepVerifier
				.create(registry.invokeHandler(McpSchema.METHOD_TASKS_RESULT, Map.of("taskId", "test-task-123"),
						context))
				.expectError()
				.verify();

			// tasks/list should be invokable — completes empty since mock store
			// returns Mono.empty() from listTasks
			StepVerifier.create(registry.invokeHandler(McpSchema.METHOD_TASKS_LIST, Map.of(), context))
				.verifyComplete();

			// tasks/cancel should be invokable — completes empty since mock store
			// returns Mono.empty() from requestCancellation
			StepVerifier
				.create(registry.invokeHandler(McpSchema.METHOD_TASKS_CANCEL, Map.of("taskId", "test-task-123"),
						context))
				.verifyComplete();
		}

		@Test
		void testBindWithRealTaskStoreHandlesGetTask() {
			// End-to-end: bind with a real in-memory store, create a task, then
			// invoke tasks/get through the registry
			InMemoryTaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
			TaskHandlerRegistry registry = new TaskHandlerRegistry();

			TaskManagerHost host = new TaskManagerHost() {
				@Override
				public <T extends Result> Mono<T> request(McpSchema.Request request, Class<T> resultType) {
					return Mono.empty();
				}

				@Override
				public Mono<Void> notification(McpSchema.Notification notification) {
					return Mono.empty();
				}

				@Override
				public void registerHandler(String method, TaskRequestHandler handler) {
					registry.registerHandler(method, handler);
				}

				@Override
				public <T extends Result> Mono<T> invokeCustomTaskHandler(String taskId, String method,
						McpSchema.Request request, TaskHandlerContext context, Class<T> resultType) {
					return Mono.empty();
				}
			};

			TaskManagerOptions options = TaskManagerOptions.builder().store(taskStore).build();
			DefaultTaskManager taskManager = new DefaultTaskManager(options);
			taskManager.bind(host);

			// Create a task in the store
			AtomicReference<String> taskIdRef = new AtomicReference<>();
			StepVerifier
				.create(taskStore
					.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()))
				.consumeNextWith(task -> taskIdRef.set(task.taskId()))
				.verifyComplete();

			// Invoke tasks/get through the registry — should find the task
			TaskManagerHost.TaskHandlerContext context = mock(TaskManagerHost.TaskHandlerContext.class);
			when(context.sessionId()).thenReturn(null);

			StepVerifier
				.create(registry.<McpSchema.GetTaskResult>invokeHandler(McpSchema.METHOD_TASKS_GET,
						Map.of("taskId", taskIdRef.get()), context))
				.expectNextMatches(result -> result.taskId().equals(taskIdRef.get())
						&& result.status() == McpSchema.TaskStatus.WORKING)
				.verifyComplete();

			taskStore.shutdown().block(Duration.ofSeconds(5));
		}

		@Test
		void testBindWithRealTaskStoreHandlesListTasks() {
			// End-to-end: bind with a real store, create tasks, then list through
			// registry
			InMemoryTaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
			TaskHandlerRegistry registry = new TaskHandlerRegistry();

			TaskManagerHost host = new TaskManagerHost() {
				@Override
				public <T extends Result> Mono<T> request(McpSchema.Request request, Class<T> resultType) {
					return Mono.empty();
				}

				@Override
				public Mono<Void> notification(McpSchema.Notification notification) {
					return Mono.empty();
				}

				@Override
				public void registerHandler(String method, TaskRequestHandler handler) {
					registry.registerHandler(method, handler);
				}

				@Override
				public <T extends Result> Mono<T> invokeCustomTaskHandler(String taskId, String method,
						McpSchema.Request request, TaskHandlerContext context, Class<T> resultType) {
					return Mono.empty();
				}
			};

			TaskManagerOptions options = TaskManagerOptions.builder().store(taskStore).build();
			DefaultTaskManager taskManager = new DefaultTaskManager(options);
			taskManager.bind(host);

			// Create 2 tasks
			StepVerifier
				.create(taskStore
					.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("tool-1")).build()))
				.expectNextCount(1)
				.verifyComplete();
			StepVerifier
				.create(taskStore
					.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("tool-2")).build()))
				.expectNextCount(1)
				.verifyComplete();

			// Invoke tasks/list through the registry
			TaskManagerHost.TaskHandlerContext context = mock(TaskManagerHost.TaskHandlerContext.class);
			when(context.sessionId()).thenReturn(null);

			StepVerifier
				.create(registry.<McpSchema.ListTasksResult>invokeHandler(McpSchema.METHOD_TASKS_LIST, null, context))
				.expectNextMatches(result -> result.tasks() != null && result.tasks().size() == 2)
				.verifyComplete();

			taskStore.shutdown().block(Duration.ofSeconds(5));
		}

	}

}
