/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Shared {@link TypeRef} constants for task-related RPC response deserialization.
 *
 * <p>
 * Centralizes type references that are used by both the server exchange and the client
 * task handler to avoid duplicate definitions.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 */
public final class TaskTypeRefs {

	private TaskTypeRefs() {
		// Utility class — no instantiation
	}

	/**
	 * Type reference for {@link McpSchema.GetTaskResult}.
	 */
	public static final TypeRef<McpSchema.GetTaskResult> GET_TASK_RESULT = new TypeRef<>() {
	};

	/**
	 * Type reference for {@link McpSchema.CreateTaskResult}.
	 */
	public static final TypeRef<McpSchema.CreateTaskResult> CREATE_TASK_RESULT = new TypeRef<>() {
	};

	/**
	 * Type reference for {@link McpSchema.ListTasksResult}.
	 */
	public static final TypeRef<McpSchema.ListTasksResult> LIST_TASKS_RESULT = new TypeRef<>() {
	};

	/**
	 * Type reference for {@link McpSchema.CancelTaskResult}.
	 */
	public static final TypeRef<McpSchema.CancelTaskResult> CANCEL_TASK_RESULT = new TypeRef<>() {
	};

}
