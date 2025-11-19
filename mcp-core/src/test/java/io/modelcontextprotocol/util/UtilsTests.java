/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.util;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

	@ParameterizedTest
	@CsvSource({
			// relative endpoints
			"http://localhost:8080/root, /api/v1, http://localhost:8080/api/v1",
			"http://localhost:8080/root/, api, http://localhost:8080/root/api",
			"http://localhost:8080, /api, http://localhost:8080/api",
			// absolute endpoints matching base
			"http://localhost:8080/root, http://localhost:8080/root/api/v1, http://localhost:8080/root/api/v1",
			"http://localhost:8080/root, http://localhost:8080/root, http://localhost:8080/root" })
	void testValidUriResolution(String baseUrl, String endpoint, String expectedResult) {
		URI result = Utils.resolveUri(URI.create(baseUrl), endpoint);
		assertThat(result.toString()).isEqualTo(expectedResult);
	}

	@ParameterizedTest
	@CsvSource({ "http://localhost:8080/root, http://localhost:8080/other/api",
			"http://localhost:8080/root, http://otherhost/api",
			"http://localhost:8080/root, http://localhost:9090/root/api" })
	void testAbsoluteUriNotMatchingBase(String baseUrl, String endpoint) {
		assertThatThrownBy(() -> Utils.resolveUri(URI.create(baseUrl), endpoint))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not match the base URL");
	}

}