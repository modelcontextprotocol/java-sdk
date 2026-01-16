/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import io.modelcontextprotocol.spec.McpSchema.ToolExecution;
import io.modelcontextprotocol.util.Assert;

/**
 * Abstract base builder for task-aware tool specifications.
 *
 * <p>
 * This class provides common builder functionality shared between
 * {@link TaskAwareAsyncToolSpecification.Builder} and
 * {@link TaskAwareSyncToolSpecification.Builder}.
 *
 * <p>
 * Subclasses must implement:
 * <ul>
 * <li>Handler-specific setter methods (createTask, getTask, getTaskResult)</li>
 * <li>The {@link #build()} method to construct the final specification</li>
 * </ul>
 *
 * @param <B> the concrete builder type for fluent method chaining
 * @see TaskAwareAsyncToolSpecification.Builder
 * @see TaskAwareSyncToolSpecification.Builder
 */
public abstract class AbstractTaskAwareToolSpecificationBuilder<B extends AbstractTaskAwareToolSpecificationBuilder<B>> {

	protected String name;

	protected String description;

	protected JsonSchema inputSchema;

	protected TaskSupportMode taskSupportMode = TaskSupportMode.REQUIRED;

	protected ToolAnnotations annotations;

	/**
	 * Returns this builder cast to the concrete type for fluent chaining.
	 * @return this builder as the concrete type B
	 */
	@SuppressWarnings("unchecked")
	protected B self() {
		return (B) this;
	}

	/**
	 * Sets the tool name (required).
	 * @param name the unique name for this tool
	 * @return this builder
	 */
	public B name(String name) {
		this.name = name;
		return self();
	}

	/**
	 * Sets the tool description.
	 * @param description a human-readable description of what the tool does
	 * @return this builder
	 */
	public B description(String description) {
		this.description = description;
		return self();
	}

	/**
	 * Sets the JSON Schema for the tool's input parameters.
	 *
	 * <p>
	 * If not set, defaults to an empty object schema (no parameters).
	 * @param inputSchema the JSON Schema defining the expected input structure
	 * @return this builder
	 */
	public B inputSchema(JsonSchema inputSchema) {
		this.inputSchema = inputSchema;
		return self();
	}

	/**
	 * Sets the task support mode for this tool.
	 *
	 * <p>
	 * Defaults to {@link TaskSupportMode#REQUIRED} if not set.
	 * @param mode the task support mode (FORBIDDEN, OPTIONAL, or REQUIRED)
	 * @return this builder
	 */
	public B taskSupportMode(TaskSupportMode mode) {
		this.taskSupportMode = mode != null ? mode : TaskSupportMode.REQUIRED;
		return self();
	}

	/**
	 * Sets optional annotations for the tool.
	 * @param annotations additional metadata for the tool
	 * @return this builder
	 */
	public B annotations(ToolAnnotations annotations) {
		this.annotations = annotations;
		return self();
	}

	/**
	 * Validates that required fields are set.
	 *
	 * <p>
	 * Subclasses should call this method at the start of their {@link #build()} method
	 * and add any additional validation specific to their handler types.
	 * @throws IllegalArgumentException if name is empty
	 */
	protected void validateCommonFields() {
		Assert.hasText(name, "name must not be empty");
	}

	/**
	 * Builds the {@link Tool} object from the configured properties.
	 *
	 * <p>
	 * This method applies defaults for missing optional fields:
	 * <ul>
	 * <li>inputSchema defaults to an empty object schema</li>
	 * <li>description defaults to the tool name</li>
	 * </ul>
	 * @return a configured Tool instance
	 */
	protected Tool buildTool() {
		// Use default empty schema if not provided
		JsonSchema schema = inputSchema != null ? inputSchema : TaskDefaults.EMPTY_INPUT_SCHEMA;

		// Use description or default to name
		String desc = description != null ? description : name;

		// Build execution property with configured task support mode
		ToolExecution execution = ToolExecution.builder().taskSupport(taskSupportMode).build();

		// Build the tool
		return Tool.builder()
			.name(name)
			.description(desc)
			.inputSchema(schema)
			.execution(execution)
			.annotations(annotations)
			.build();
	}

	/**
	 * Builds the final tool specification.
	 *
	 * <p>
	 * Implementations must validate their handler-specific fields and construct the
	 * appropriate specification type.
	 * @return the built specification
	 * @throws IllegalArgumentException if required fields are not set
	 */
	public abstract Object build();

}
