/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Holds task handler registrations and provides common adapter logic for both client and
 * server {@link TaskManagerHost} implementations.
 *
 * <p>
 * Both {@code McpAsyncClient} and {@code McpAsyncServer} compose this class to share:
 * <ul>
 * <li>Handler storage and registration</li>
 * <li>Handler invocation (JSONRPCRequest wrapping)</li>
 * <li>Capability-based wiring pattern</li>
 * <li>Side-channel TypeRef resolution</li>
 * </ul>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see TaskManagerHost
 */
class TaskHandlerRegistry {

	private static final Logger logger = LoggerFactory.getLogger(TaskHandlerRegistry.class);

	private final ConcurrentHashMap<String, TaskManagerHost.TaskRequestHandler> handlers = new ConcurrentHashMap<>();

	/**
	 * Registers a handler for a specific request method.
	 * @param method the request method (e.g., "tasks/get")
	 * @param handler the handler function
	 */
	public void registerHandler(String method, TaskManagerHost.TaskRequestHandler handler) {
		logger.debug("TaskManager registering handler for method: {}", method);
		this.handlers.put(method, handler);
	}

	/**
	 * Invokes a registered handler by wrapping raw params into a
	 * {@link McpSchema.JSONRPCRequest} and dispatching to the handler with the given
	 * context.
	 * @param <T> the expected result type
	 * @param method the MCP method name
	 * @param params the raw request parameters
	 * @param context the handler context with session information
	 * @return a Mono emitting the handler result
	 */
	@SuppressWarnings("unchecked")
	public <T> Mono<T> invokeHandler(String method, Object params, TaskManagerHost.TaskHandlerContext context) {
		TaskManagerHost.TaskRequestHandler handler = this.handlers.get(method);
		if (handler == null) {
			return Mono.error(new IllegalStateException("No handler registered for: " + method));
		}
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
				"task-" + System.nanoTime(), params);
		return handler.handle(request, context).map(result -> (T) result);
	}

	/**
	 * Wires adapted handlers for all task methods matching the given capabilities. The
	 * adapter function converts a (method, {@link TaskManagerHost.TaskRequestHandler})
	 * pair into the target handler type, and the registrar puts the adapted handler into
	 * the appropriate handler map.
	 *
	 * <p>
	 * The {@code tasks/get} and {@code tasks/result} methods are always wired. The
	 * {@code tasks/list} and {@code tasks/cancel} methods are wired only when their
	 * respective capabilities are enabled (non-null).
	 *
	 * <p>
	 * This consolidates the wiring pattern that was previously duplicated across
	 * {@code McpAsyncClient} and {@code McpAsyncServer}.
	 * @param <T> the target handler type
	 * @param supportsList whether the tasks/list capability is enabled
	 * @param supportsCancel whether the tasks/cancel capability is enabled
	 * @param adapter converts (method, taskHandler) into the target handler type
	 * @param registrar receives (method, adaptedHandler) to register in the handler map
	 */
	public <T> void wireHandlers(boolean supportsList, boolean supportsCancel,
			BiFunction<String, TaskManagerHost.TaskRequestHandler, T> adapter, BiConsumer<String, T> registrar) {
		wireIfPresent(McpSchema.METHOD_TASKS_GET, adapter, registrar);
		wireIfPresent(McpSchema.METHOD_TASKS_RESULT, adapter, registrar);
		if (supportsList) {
			wireIfPresent(McpSchema.METHOD_TASKS_LIST, adapter, registrar);
		}
		if (supportsCancel) {
			wireIfPresent(McpSchema.METHOD_TASKS_CANCEL, adapter, registrar);
		}
	}

	private <T> void wireIfPresent(String method, BiFunction<String, TaskManagerHost.TaskRequestHandler, T> adapter,
			BiConsumer<String, T> registrar) {
		TaskManagerHost.TaskRequestHandler handler = this.handlers.get(method);
		if (handler != null) {
			registrar.accept(method, adapter.apply(method, handler));
		}
		else {
			logger.warn("TaskManager did not register {} handler", method);
		}
	}

	/**
	 * Maps side-channel method strings to {@link TypeRef} instances for runtime generic
	 * type preservation. Needed because the generic type parameter {@code R} is erased at
	 * runtime and concrete TypeRef instances are required for correct deserialization.
	 *
	 * <p>
	 * Currently supports elicitation and sampling side-channel methods.
	 * @param method the MCP method string
	 * @return the TypeRef for the method's result type
	 * @throws IllegalArgumentException if the method is not a supported side-channel
	 * method
	 */
	public static TypeRef<? extends McpSchema.Result> getResultTypeRefForMethod(String method) {
		return switch (method) {
			case McpSchema.METHOD_ELICITATION_CREATE -> new TypeRef<McpSchema.ElicitResult>() {
			};
			case McpSchema.METHOD_SAMPLING_CREATE_MESSAGE -> new TypeRef<McpSchema.CreateMessageResult>() {
			};
			default -> throw new IllegalArgumentException("Unsupported side-channel method: " + method);
		};
	}

}
