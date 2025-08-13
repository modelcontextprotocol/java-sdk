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

	@Test
	void shouldHandleSpecialCharactersInVariableValues() {
		Map<String, String> values = this.uriTemplateFactory.create("/api/users/{userId}/files/{fileName}")
			.extractVariableValues("/api/users/user@example.com/files/my-file.txt");
		assertEquals(2, values.size());
		assertEquals("user@example.com", values.get("userId"));
		assertEquals("my-file.txt", values.get("fileName"));
	}

	@Test
	void shouldHandleComplexNestedPaths() {
		var uriTemplateManager = this.uriTemplateFactory
			.create("/api/v1/organizations/{orgId}/projects/{projectId}/tasks/{taskId}");

		List<String> variables = uriTemplateManager.getVariableNames();
		assertEquals(3, variables.size());
		assertEquals("orgId", variables.get(0));
		assertEquals("projectId", variables.get(1));
		assertEquals("taskId", variables.get(2));

		Map<String, String> values = uriTemplateManager
			.extractVariableValues("/api/v1/organizations/acme/projects/web-app/tasks/1");
		assertEquals("acme", values.get("orgId"));
		assertEquals("web-app", values.get("projectId"));
		assertEquals("1", values.get("taskId"));
	}

	@Test
	void shouldNotMatchWhenPathSegmentCountDiffers() {
		var uriTemplateManager = this.uriTemplateFactory.create("/api/users/{userId}/posts/{postId}");

		assertFalse(uriTemplateManager.matches("/api/users/1"));
		assertFalse(uriTemplateManager.matches("/api/users/1/posts"));
		assertFalse(uriTemplateManager.matches("/api/users/1/posts/1/comments"));
	}

	@Test
	void shouldNotMatchWhenStaticSegmentsDiffer() {
		var uriTemplateManager = this.uriTemplateFactory.create("/api/users/{userId}/posts/{postId}");

		assertFalse(uriTemplateManager.matches("/api/user/1/posts/1"));
		assertFalse(uriTemplateManager.matches("/api/users/1/post/1"));
		assertFalse(uriTemplateManager.matches("/v1/users/1/posts/1"));
	}

	@Test
	void shouldHandleVariableAtBeginningOfPath() {
		var uriTemplateManager = this.uriTemplateFactory.create("/{version}/api/users/{userId}");

		List<String> variables = uriTemplateManager.getVariableNames();
		assertEquals(2, variables.size());
		assertEquals("version", variables.get(0));
		assertEquals("userId", variables.get(1));

		assertTrue(uriTemplateManager.matches("/v1/api/users/1"));

		Map<String, String> values = uriTemplateManager.extractVariableValues("/v1/api/users/1");
		assertEquals("v1", values.get("version"));
		assertEquals("1", values.get("userId"));
	}

	@Test
	void shouldHandleTemplateWithSingleVariable() {
		var uriTemplateManager = this.uriTemplateFactory.create("/api/users/{id}");

		List<String> variables = uriTemplateManager.getVariableNames();
		assertEquals(1, variables.size());
		assertEquals("id", variables.get(0));

		Map<String, String> values = uriTemplateManager.extractVariableValues("/api/users/1");
		assertEquals("1", values.get("id"));

		assertTrue(uriTemplateManager.matches("/api/users/1"));
	}

	@Test
	void shouldHandleRootPathTemplate() {
		var uriTemplateManager = this.uriTemplateFactory.create("/{id}");

		List<String> variables = uriTemplateManager.getVariableNames();
		assertEquals(1, variables.size());
		assertEquals("id", variables.get(0));

		assertTrue(uriTemplateManager.matches("/1"));
		assertFalse(uriTemplateManager.matches("/1/something"));

		Map<String, String> values = uriTemplateManager.extractVariableValues("/abc");
		assertEquals("abc", values.get("id"));
	}

	@Test
	void shouldHandleConsecutiveVariables() {
		var uriTemplateManager = this.uriTemplateFactory.create("/api/{version}/{userId}");

		assertTrue(uriTemplateManager.matches("/api/v1/1"));

		Map<String, String> values = uriTemplateManager.extractVariableValues("/api/v2/1");
		assertEquals("v2", values.get("version"));
		assertEquals("1", values.get("userId"));
	}

}
