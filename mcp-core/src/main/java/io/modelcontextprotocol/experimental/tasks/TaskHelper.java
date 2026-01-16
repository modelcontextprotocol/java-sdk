/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.spec.McpSchema.TaskStatus;

/**
 * Utility methods for working with MCP tasks.
 *
 * <p>
 * This class provides helper methods for common task operations like checking terminal
 * states and determining valid state transitions.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 */
public final class TaskHelper {

	private TaskHelper() {
		// Utility class, no instantiation
	}

	/**
	 * Checks if a task status is a terminal state.
	 *
	 * <p>
	 * Terminal states are: COMPLETED, FAILED, CANCELLED. Once a task reaches a terminal
	 * state, it cannot transition to any other state.
	 *
	 * <p>
	 * Note: INPUT_REQUIRED is NOT a terminal state - it can transition back to WORKING.
	 * @param status the task status to check
	 * @return true if the status is terminal, false otherwise
	 */
	public static boolean isTerminal(TaskStatus status) {
		if (status == null) {
			return false;
		}
		return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED;
	}

	/**
	 * Checks if a state transition is valid according to the MCP task state machine.
	 *
	 * <p>
	 * Valid transitions:
	 * <ul>
	 * <li>WORKING → COMPLETED, FAILED, CANCELLED, INPUT_REQUIRED
	 * <li>INPUT_REQUIRED → WORKING, COMPLETED, FAILED, CANCELLED
	 * <li>COMPLETED → (none - terminal)
	 * <li>FAILED → (none - terminal)
	 * <li>CANCELLED → (none - terminal)
	 * </ul>
	 * @param from the current status
	 * @param to the desired new status
	 * @return true if the transition is valid, false otherwise
	 */
	public static boolean isValidTransition(TaskStatus from, TaskStatus to) {
		if (from == null || to == null) {
			return false;
		}

		// Terminal states cannot transition to any state (including themselves)
		if (isTerminal(from)) {
			return false;
		}

		// From WORKING, can go to any state (including WORKING again).
		// Note: WORKING → WORKING is valid - this represents a status update without
		// actual state change, which may occur when updating the statusMessage field
		// while the task continues running.
		if (from == TaskStatus.WORKING) {
			return true;
		}

		// From INPUT_REQUIRED, can go back to WORKING or to terminal states.
		// Note: INPUT_REQUIRED → INPUT_REQUIRED is NOT valid. If the task needs to
		// remain in INPUT_REQUIRED state with an updated message, callers should
		// update the statusMessage directly rather than calling a transition method.
		// This ensures that INPUT_REQUIRED always represents a clear "waiting for
		// input" → "got input, resuming work" lifecycle.
		if (from == TaskStatus.INPUT_REQUIRED) {
			return to == TaskStatus.WORKING || isTerminal(to);
		}

		return false;
	}

	/**
	 * Gets a human-readable description of a task status.
	 * @param status the task status
	 * @return a human-readable description
	 */
	public static String getStatusDescription(TaskStatus status) {
		if (status == null) {
			return "Unknown";
		}
		return switch (status) {
			case WORKING -> "Task is in progress";
			case INPUT_REQUIRED -> "Task requires additional input";
			case COMPLETED -> "Task completed successfully";
			case FAILED -> "Task failed";
			case CANCELLED -> "Task was cancelled";
		};
	}

}
