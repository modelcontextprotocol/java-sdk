/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Daniel Garnier-Moiroux
 */
class DefaultServerTransportSecurityValidatorTests {

	private static final ServerTransportSecurityException INVALID_ORIGIN = new ServerTransportSecurityException(403,
			"Invalid Origin header");

	private final DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
		.allowedOrigin("http://localhost:8080")
		.build();

	@Test
	void builder() {
		assertThatCode(() -> DefaultServerTransportSecurityValidator.builder().build()).doesNotThrowAnyException();
		assertThatThrownBy(() -> DefaultServerTransportSecurityValidator.builder().allowedOrigins(null).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void originHeaderMissing() {
		assertThatCode(() -> validator.validateHeaders(new HashMap<>())).doesNotThrowAnyException();
	}

	@Test
	void originHeaderListEmpty() {
		assertThatCode(() -> validator.validateHeaders(Map.of("Origin", List.of()))).doesNotThrowAnyException();
	}

	@Test
	void caseInsensitive() {
		var headers = Map.of("origin", List.of("http://localhost:8080"));

		assertThatCode(() -> validator.validateHeaders(headers)).doesNotThrowAnyException();
	}

	@Test
	void exactMatch() {
		var headers = originHeader("http://localhost:8080");

		assertThatCode(() -> validator.validateHeaders(headers)).doesNotThrowAnyException();
	}

	@Test
	void differentPort() {

		var headers = originHeader("http://localhost:3000");

		assertThatThrownBy(() -> validator.validateHeaders(headers)).isEqualTo(INVALID_ORIGIN);
	}

	@Test
	void differentHost() {

		var headers = originHeader("http://example.com:8080");

		assertThatThrownBy(() -> validator.validateHeaders(headers)).isEqualTo(INVALID_ORIGIN);
	}

	@Test
	void differentScheme() {

		var headers = originHeader("https://localhost:8080");

		assertThatThrownBy(() -> validator.validateHeaders(headers)).isEqualTo(INVALID_ORIGIN);
	}

	@Nested
	class WildcardPort {

		private final DefaultServerTransportSecurityValidator wildcardValidator = DefaultServerTransportSecurityValidator
			.builder()
			.allowedOrigin("http://localhost:*")
			.build();

		@Test
		void anyPortWithWildcard() {
			var headers = originHeader("http://localhost:3000");

			assertThatCode(() -> wildcardValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void noPortWithWildcard() {
			var headers = originHeader("http://localhost");

			assertThatCode(() -> wildcardValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void differentPortWithWildcard() {
			var headers = originHeader("http://localhost:8080");

			assertThatCode(() -> wildcardValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void differentHostWithWildcard() {
			var headers = originHeader("http://example.com:3000");

			assertThatThrownBy(() -> wildcardValidator.validateHeaders(headers)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void differentSchemeWithWildcard() {
			var headers = originHeader("https://localhost:3000");

			assertThatThrownBy(() -> wildcardValidator.validateHeaders(headers)).isEqualTo(INVALID_ORIGIN);
		}

	}

	@Nested
	class MultipleOrigins {

		DefaultServerTransportSecurityValidator multipleOriginsValidator = DefaultServerTransportSecurityValidator
			.builder()
			.allowedOrigin("http://localhost:8080")
			.allowedOrigin("http://example.com:3000")
			.allowedOrigin("http://myapp.com:*")
			.build();

		@Test
		void matchingOneOfMultiple() {
			var headers = originHeader("http://example.com:3000");

			assertThatCode(() -> multipleOriginsValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void matchingWildcardInMultiple() {
			var headers = originHeader("http://myapp.com:9999");

			assertThatCode(() -> multipleOriginsValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void notMatchingAny() {
			var headers = originHeader("http://malicious.example.com:1234");

			assertThatThrownBy(() -> multipleOriginsValidator.validateHeaders(headers)).isEqualTo(INVALID_ORIGIN);
		}

	}

	@Nested
	class BuilderTests {

		@Test
		void shouldAddMultipleOriginsWithAllowedOriginsMethod() {
			DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
				.allowedOrigins(List.of("http://localhost:8080", "http://example.com:*"))
				.build();

			var headers = originHeader("http://example.com:3000");

			assertThatCode(() -> validator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void shouldCombineAllowedOriginMethods() {
			DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
				.allowedOrigin("http://localhost:8080")
				.allowedOrigins(List.of("http://example.com:*", "http://test.com:3000"))
				.build();

			assertThatCode(() -> validator.validateHeaders(originHeader("http://localhost:8080")))
				.doesNotThrowAnyException();
			assertThatCode(() -> validator.validateHeaders(originHeader("http://example.com:9999")))
				.doesNotThrowAnyException();
			assertThatCode(() -> validator.validateHeaders(originHeader("http://test.com:3000")))
				.doesNotThrowAnyException();
		}

	}

	private static Map<String, List<String>> originHeader(String origin) {
		return Map.of("Origin", List.of(origin));
	}

}
