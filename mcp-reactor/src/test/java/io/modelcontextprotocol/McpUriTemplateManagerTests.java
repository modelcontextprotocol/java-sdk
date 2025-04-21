/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.util.DeafaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManager;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link McpUriTemplateManager} and its implementations.
 *
 * @author Christian Tzolov
 */
public class McpUriTemplateManagerTests {

	private McpUriTemplateManagerFactory uriTemplateFactory;

	@BeforeEach
	void setUp() {
		this.uriTemplateFactory = new DeafaultMcpUriTemplateManagerFactory();
	}

	@Test
	void shouldExtractVariableNamesFromTemplate() {
		List<String> variables = this.uriTemplateFactory.create("/api/users/{userId}/posts/{postId}")
			.getVariableNames();
		assertEquals(2, variables.size());
		assertEquals("userId", variables.get(0));
		assertEquals("postId", variables.get(1));
	}

	@Test
	void shouldReturnEmptyListWhenTemplateHasNoVariables() {
		List<String> variables = this.uriTemplateFactory.create("/api/users/all").getVariableNames();
		assertEquals(0, variables.size());
	}

	@Test
	void shouldThrowExceptionWhenExtractingVariablesFromNullTemplate() {
		assertThrows(IllegalArgumentException.class, () -> this.uriTemplateFactory.create(null).getVariableNames());
	}

	@Test
	void shouldThrowExceptionWhenExtractingVariablesFromEmptyTemplate() {
		assertThrows(IllegalArgumentException.class, () -> this.uriTemplateFactory.create("").getVariableNames());
	}

	@Test
	void shouldThrowExceptionWhenTemplateContainsDuplicateVariables() {
		assertThrows(IllegalArgumentException.class,
				() -> this.uriTemplateFactory.create("/api/users/{userId}/posts/{userId}").getVariableNames());
	}

	@Test
	void shouldExtractVariableValuesFromRequestUri() {
		Map<String, String> values = this.uriTemplateFactory.create("/api/users/{userId}/posts/{postId}")
			.extractVariableValues("/api/users/123/posts/456");
		assertEquals(2, values.size());
		assertEquals("123", values.get("userId"));
		assertEquals("456", values.get("postId"));
	}

	@Test
	void shouldReturnEmptyMapWhenTemplateHasNoVariables() {
		Map<String, String> values = this.uriTemplateFactory.create("/api/users/all")
			.extractVariableValues("/api/users/all");
		assertEquals(0, values.size());
	}

	@Test
	void shouldReturnEmptyMapWhenRequestUriIsNull() {
		Map<String, String> values = this.uriTemplateFactory.create("/api/users/{userId}/posts/{postId}")
			.extractVariableValues(null);
		assertEquals(0, values.size());
	}

	@Test
	void shouldMatchUriAgainstTemplatePattern() {
		var uriTemplateManager = this.uriTemplateFactory.create("/api/users/{userId}/posts/{postId}");

		assertTrue(uriTemplateManager.matches("/api/users/123/posts/456"));
		assertFalse(uriTemplateManager.matches("/api/users/123/comments/456"));
	}

}
