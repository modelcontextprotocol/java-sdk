package io.modelcontextprotocol.util;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilsTests {

	@Test
	void testHasText() {
		assertFalse(Utils.hasText(null));
		assertFalse(Utils.hasText(""));
		assertFalse(Utils.hasText(" "));
		assertTrue(Utils.hasText("test"));
	}

	@Test
	void testCollectionIsEmpty() {
		assertTrue(Utils.isEmpty((Collection<?>) null));
		assertTrue(Utils.isEmpty(List.of()));
		assertFalse(Utils.isEmpty(List.of("test")));
	}

	@Test
	void testMapIsEmpty() {
		assertTrue(Utils.isEmpty((Map<?, ?>) null));
		assertTrue(Utils.isEmpty(Map.of()));
		assertFalse(Utils.isEmpty(Map.of("key", "value")));
	}

}