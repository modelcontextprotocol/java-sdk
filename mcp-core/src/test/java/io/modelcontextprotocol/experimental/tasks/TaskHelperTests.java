/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import io.modelcontextprotocol.spec.McpSchema.TaskStatus;

/**
 * Tests for {@link TaskHelper} utility methods.
 */
class TaskHelperTests {

	// ------------------------------------------
	// isTerminal Tests
	// ------------------------------------------

	static Stream<Arguments> terminalStatusCases() {
		return Stream.of(Arguments.of(TaskStatus.COMPLETED, true), Arguments.of(TaskStatus.FAILED, true),
				Arguments.of(TaskStatus.CANCELLED, true), Arguments.of(TaskStatus.WORKING, false),
				Arguments.of(TaskStatus.INPUT_REQUIRED, false), Arguments.of(null, false));
	}

	@ParameterizedTest(name = "isTerminal({0}) should be {1}")
	@MethodSource("terminalStatusCases")
	void testIsTerminal(TaskStatus status, boolean expected) {
		assertThat(TaskHelper.isTerminal(status)).isEqualTo(expected);
	}

	// ------------------------------------------
	// isValidTransition Tests
	// ------------------------------------------

	static Stream<Arguments> validTransitionCases() {
		return Stream.of(Arguments.of(TaskStatus.WORKING, TaskStatus.COMPLETED),
				Arguments.of(TaskStatus.WORKING, TaskStatus.FAILED),
				Arguments.of(TaskStatus.WORKING, TaskStatus.CANCELLED),
				Arguments.of(TaskStatus.WORKING, TaskStatus.INPUT_REQUIRED),
				Arguments.of(TaskStatus.INPUT_REQUIRED, TaskStatus.WORKING),
				Arguments.of(TaskStatus.INPUT_REQUIRED, TaskStatus.COMPLETED));
	}

	@ParameterizedTest(name = "transition from {0} to {1} should be valid")
	@MethodSource("validTransitionCases")
	void testValidTransitions(TaskStatus from, TaskStatus to) {
		assertThat(TaskHelper.isValidTransition(from, to)).isTrue();
	}

	static Stream<Arguments> invalidTransitionCases() {
		return Stream.of(Arguments.of(TaskStatus.COMPLETED, TaskStatus.WORKING),
				Arguments.of(TaskStatus.FAILED, TaskStatus.WORKING),
				Arguments.of(TaskStatus.CANCELLED, TaskStatus.WORKING), Arguments.of(null, TaskStatus.WORKING),
				Arguments.of(TaskStatus.WORKING, null));
	}

	@ParameterizedTest(name = "transition from {0} to {1} should be invalid")
	@MethodSource("invalidTransitionCases")
	void testInvalidTransitions(TaskStatus from, TaskStatus to) {
		assertThat(TaskHelper.isValidTransition(from, to)).isFalse();
	}

	// ------------------------------------------
	// getStatusDescription Tests
	// ------------------------------------------

	@ParameterizedTest
	@EnumSource(TaskStatus.class)
	void testGetStatusDescriptionReturnsNonNull(TaskStatus status) {
		assertThat(TaskHelper.getStatusDescription(status)).isNotNull().isNotEmpty();
	}

	@Test
	void testGetStatusDescriptionWithNull() {
		assertThat(TaskHelper.getStatusDescription(null)).isEqualTo("Unknown");
	}

}
