/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * Abstract base class for testing task-aware tool specification builders.
 *
 * <p>
 * This class provides common test cases for builder validation and defaults that are
 * shared between {@link TaskAwareAsyncToolSpecificationTest} and
 * {@link TaskAwareSyncToolSpecificationTest}.
 *
 * @param <S> the specification type (e.g., TaskAwareAsyncToolSpecification)
 * @param <B> the builder type
 */
abstract class AbstractTaskAwareToolSpecificationTest<S, B extends AbstractTaskAwareToolSpecificationBuilder<B>> {

	protected static final JsonSchema TEST_SCHEMA = new JsonSchema("object", Map.of("input", Map.of("type", "string")),
			null, null, null, null);

	/**
	 * Creates a new builder instance.
	 * @return a new builder
	 */
	protected abstract B createBuilder();

	/**
	 * Configures the builder with a minimal valid createTask handler.
	 * @param builder the builder to configure
	 * @return the same builder for chaining
	 */
	protected abstract B withMinimalCreateTaskHandler(B builder);

	/**
	 * Builds the specification from the builder.
	 * @param builder the configured builder
	 * @return the built specification
	 */
	protected abstract S build(B builder);

	/**
	 * Gets the tool from the specification.
	 * @param spec the specification
	 * @return the tool definition
	 */
	protected abstract Tool getTool(S spec);

	/**
	 * Gets the EMPTY_INPUT_SCHEMA constant from the specification class.
	 * @return the empty input schema
	 */
	protected abstract JsonSchema getEmptyInputSchema();

	// ------------------------------------------
	// Builder Validation Tests
	// ------------------------------------------

	@Test
	void builderShouldThrowExceptionWhenNameIsNull() {
		B builder = createBuilder();
		withMinimalCreateTaskHandler(builder);

		assertThatThrownBy(() -> build(builder)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("name must not be empty");
	}

	@Test
	void builderShouldThrowExceptionWhenNameIsEmpty() {
		B builder = createBuilder();
		builder.name("");
		withMinimalCreateTaskHandler(builder);

		assertThatThrownBy(() -> build(builder)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("name must not be empty");
	}

	@Test
	void builderShouldThrowExceptionWhenCreateTaskHandlerIsNull() {
		B builder = createBuilder();
		builder.name("test-tool");
		// Don't set createTask handler

		assertThatThrownBy(() -> build(builder)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("createTaskHandler must not be null");
	}

	// ------------------------------------------
	// Default Values Tests
	// ------------------------------------------

	@Test
	void builderShouldUseDefaultValuesWhenOptionalFieldsNotSet() {
		B builder = createBuilder();
		builder.name("minimal-tool");
		withMinimalCreateTaskHandler(builder);

		S spec = build(builder);
		Tool tool = getTool(spec);

		assertThat(tool.name()).isEqualTo("minimal-tool");
		// Description defaults to name
		assertThat(tool.description()).isEqualTo("minimal-tool");
		// InputSchema defaults to empty object schema
		assertThat(tool.inputSchema()).isEqualTo(getEmptyInputSchema());
		// TaskSupportMode defaults to REQUIRED
		assertThat(tool.execution().taskSupport()).isEqualTo(TaskSupportMode.REQUIRED);
	}

	@Test
	void builderShouldSetAnnotationsCorrectly() {
		ToolAnnotations annotations = new ToolAnnotations("Test Tool", true, false, true, false, true);

		B builder = createBuilder();
		builder.name("annotated-tool").annotations(annotations);
		withMinimalCreateTaskHandler(builder);

		S spec = build(builder);
		Tool tool = getTool(spec);

		assertThat(tool.annotations()).isEqualTo(annotations);
	}

	@Test
	void taskSupportModeNullShouldDefaultToRequired() {
		B builder = createBuilder();
		builder.name("null-mode-tool").taskSupportMode(null);
		withMinimalCreateTaskHandler(builder);

		S spec = build(builder);
		Tool tool = getTool(spec);

		assertThat(tool.execution().taskSupport()).isEqualTo(TaskSupportMode.REQUIRED);
	}

	@Test
	void builderShouldCreateValidSpecification() {
		B builder = createBuilder();
		builder.name("test-task-tool")
			.description("A test task tool")
			.inputSchema(TEST_SCHEMA)
			.taskSupportMode(TaskSupportMode.REQUIRED);
		withMinimalCreateTaskHandler(builder);

		S spec = build(builder);
		Tool tool = getTool(spec);

		assertThat(spec).isNotNull();
		assertThat(tool).isNotNull();
		assertThat(tool.name()).isEqualTo("test-task-tool");
		assertThat(tool.description()).isEqualTo("A test task tool");
		assertThat(tool.inputSchema()).isEqualTo(TEST_SCHEMA);
		assertThat(tool.execution().taskSupport()).isEqualTo(TaskSupportMode.REQUIRED);
	}

}
