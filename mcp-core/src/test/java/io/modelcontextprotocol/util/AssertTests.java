/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
	void testCollectionNoNullElements() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Assert.noNullElements(Arrays.asList("test", null), "collection has null elements"));
		assertEquals("collection has null elements", e.getMessage());

		assertDoesNotThrow(() -> Assert.noNullElements(null, "collection is null"));
		assertDoesNotThrow(() -> Assert.noNullElements(List.of(), "collection is empty"));
		assertDoesNotThrow(() -> Assert.noNullElements(List.of("test"), "collection has no null elements"));
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