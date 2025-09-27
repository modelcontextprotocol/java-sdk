/*
* Copyright 2025 - 2025 the original author or authors.
*/

package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify the equals method implementation for PromptReference.
 */
class PromptReferenceEqualsTest {

	@Test
	void testEqualsWithSameIdentifierAndType() {
		McpSchema.PromptReference ref1 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt",
				"Test Title");
		McpSchema.PromptReference ref2 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt",
				"Different Title");

		assertTrue(ref1.equals(ref2), "PromptReferences with same identifier and type should be equal");
		assertEquals(ref1.hashCode(), ref2.hashCode(), "Equal objects should have same hash code");
	}

	@Test
	void testEqualsWithDifferentIdentifier() {
		McpSchema.PromptReference ref1 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt-1",
				"Test Title");
		McpSchema.PromptReference ref2 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt-2",
				"Test Title");

		assertFalse(ref1.equals(ref2), "PromptReferences with different identifiers should not be equal");
	}

	@Test
	void testEqualsWithDifferentType() {
		McpSchema.PromptReference ref1 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt",
				"Test Title");
		McpSchema.PromptReference ref2 = new McpSchema.PromptReference("ref/other", "test-prompt", "Test Title");

		assertFalse(ref1.equals(ref2), "PromptReferences with different types should not be equal");
	}

	@Test
	void testEqualsWithNull() {
		McpSchema.PromptReference ref1 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt",
				"Test Title");

		assertFalse(ref1.equals(null), "PromptReference should not be equal to null");
	}

	@Test
	void testEqualsWithDifferentClass() {
		McpSchema.PromptReference ref1 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt",
				"Test Title");
		String other = "not a PromptReference";

		assertFalse(ref1.equals(other), "PromptReference should not be equal to different class");
	}

	@Test
	void testEqualsWithSameInstance() {
		McpSchema.PromptReference ref1 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt",
				"Test Title");

		assertTrue(ref1.equals(ref1), "PromptReference should be equal to itself");
	}

	@Test
	void testEqualsIgnoresTitle() {
		McpSchema.PromptReference ref1 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt", "Title 1");
		McpSchema.PromptReference ref2 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt", "Title 2");
		McpSchema.PromptReference ref3 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt", null);

		assertTrue(ref1.equals(ref2), "PromptReferences should be equal regardless of title");
		assertTrue(ref1.equals(ref3), "PromptReferences should be equal even when one has null title");
		assertTrue(ref2.equals(ref3), "PromptReferences should be equal even when one has null title");
	}

	@Test
	void testHashCodeConsistency() {
		McpSchema.PromptReference ref1 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt",
				"Test Title");
		McpSchema.PromptReference ref2 = new McpSchema.PromptReference(PromptReference.TYPE, "test-prompt",
				"Different Title");

		assertEquals(ref1.hashCode(), ref2.hashCode(), "Objects that are equal should have the same hash code");

		// Call hashCode multiple times to ensure consistency
		int hashCode1 = ref1.hashCode();
		int hashCode2 = ref1.hashCode();
		assertEquals(hashCode1, hashCode2, "Hash code should be consistent across multiple calls");
	}

}
