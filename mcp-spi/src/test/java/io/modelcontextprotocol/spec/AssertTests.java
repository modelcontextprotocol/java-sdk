/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.util.Assert;

class AssertTests {

	@Test
	void testCollectionNotEmpty() {
		IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
				() -> Assert.notEmpty(null, "collection is null"));
		assertEquals("collection is null", e1.getMessage());

		IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
				() -> Assert.notEmpty(List.of(), "collection is empty"));
		assertEquals("collection is empty", e2.getMessage());

		assertDoesNotThrow(() -> Assert.notEmpty(List.of("test"), "collection is not empty"));
	}

	@Test
	void testObjectNotNull() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Assert.notNull(null, "object is null"));
		assertEquals("object is null", e.getMessage());

		assertDoesNotThrow(() -> Assert.notNull("test", "object is not null"));
	}

	@Test
	void testStringHasText() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Assert.hasText(null, "string is null"));
		assertEquals("string is null", e.getMessage());

		assertDoesNotThrow(() -> Assert.hasText("test", "string is not empty"));
	}

}