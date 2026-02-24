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

	private static final ServerTransportSecurityException INVALID_HOST = new ServerTransportSecurityException(421,
			"Invalid Host header");

	private final DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
		.allowedOrigin("http://localhost:8080")
		.build();

	@Test
	void builder() {
		assertThatCode(() -> DefaultServerTransportSecurityValidator.builder().build()).doesNotThrowAnyException();
		assertThatThrownBy(() -> DefaultServerTransportSecurityValidator.builder().allowedOrigins(null).build())
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DefaultServerTransportSecurityValidator.builder().allowedHosts(null).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Nested
	class OriginHeader {

		@Test
		void originHeaderMissing() {
			assertThatCode(() -> validator.validateHeaders(new HashMap<>())).doesNotThrowAnyException();
		}

		@Test
		void originHeaderListEmpty() {
			assertThatThrownBy(() -> validator.validateHeaders(Map.of("Origin", List.of()))).isEqualTo(INVALID_ORIGIN);
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
				.allowedOrigin("http://myapp.example.com:*")
				.build();

			@Test
			void matchingOneOfMultiple() {
				var headers = originHeader("http://example.com:3000");

				assertThatCode(() -> multipleOriginsValidator.validateHeaders(headers)).doesNotThrowAnyException();
			}

			@Test
			void matchingWildcardInMultiple() {
				var headers = originHeader("http://myapp.example.com:9999");

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

	}

	@Nested
	class HostHeader {

		private final DefaultServerTransportSecurityValidator hostValidator = DefaultServerTransportSecurityValidator
			.builder()
			.allowedHost("localhost:8080")
			.build();

		@Test
		void notConfigured() {
			assertThatCode(() -> validator.validateHeaders(new HashMap<>())).doesNotThrowAnyException();
		}

		@Test
		void missing() {
			assertThatThrownBy(() -> hostValidator.validateHeaders(new HashMap<>())).isEqualTo(INVALID_HOST);
		}

		@Test
		void listEmpty() {
			assertThatThrownBy(() -> hostValidator.validateHeaders(Map.of("Host", List.of()))).isEqualTo(INVALID_HOST);
		}

		@Test
		void caseInsensitive() {
			var headers = Map.of("host", List.of("localhost:8080"));

			assertThatCode(() -> hostValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void exactMatch() {
			var headers = hostHeader("localhost:8080");

			assertThatCode(() -> hostValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void differentPort() {
			var headers = hostHeader("localhost:3000");

			assertThatThrownBy(() -> hostValidator.validateHeaders(headers)).isEqualTo(INVALID_HOST);
		}

		@Test
		void differentHost() {
			var headers = hostHeader("example.com:8080");

			assertThatThrownBy(() -> hostValidator.validateHeaders(headers)).isEqualTo(INVALID_HOST);
		}

		@Nested
		class HostWildcardPort {

			private final DefaultServerTransportSecurityValidator wildcardHostValidator = DefaultServerTransportSecurityValidator
				.builder()
				.allowedHost("localhost:*")
				.build();

			@Test
			void anyPort() {
				var headers = hostHeader("localhost:3000");

				assertThatCode(() -> wildcardHostValidator.validateHeaders(headers)).doesNotThrowAnyException();
			}

			@Test
			void noPort() {
				var headers = hostHeader("localhost");

				assertThatCode(() -> wildcardHostValidator.validateHeaders(headers)).doesNotThrowAnyException();
			}

			@Test
			void differentHost() {
				var headers = hostHeader("example.com:3000");

				assertThatThrownBy(() -> wildcardHostValidator.validateHeaders(headers)).isEqualTo(INVALID_HOST);
			}

		}

		@Nested
		class MultipleHosts {

			DefaultServerTransportSecurityValidator multipleHostsValidator = DefaultServerTransportSecurityValidator
				.builder()
				.allowedHost("example.com:3000")
				.allowedHost("myapp.example.com:*")
				.build();

			@Test
			void exactMatch() {
				var headers = hostHeader("example.com:3000");

				assertThatCode(() -> multipleHostsValidator.validateHeaders(headers)).doesNotThrowAnyException();
			}

			@Test
			void wildcard() {
				var headers = hostHeader("myapp.example.com:9999");

				assertThatCode(() -> multipleHostsValidator.validateHeaders(headers)).doesNotThrowAnyException();
			}

			@Test
			void differentHost() {
				var headers = hostHeader("malicious.example.com:3000");

				assertThatThrownBy(() -> multipleHostsValidator.validateHeaders(headers)).isEqualTo(INVALID_HOST);
			}

			@Test
			void differentPort() {
				var headers = hostHeader("localhost:8080");

				assertThatThrownBy(() -> multipleHostsValidator.validateHeaders(headers)).isEqualTo(INVALID_HOST);
			}

		}

		@Nested
		class HostBuilderTests {

			@Test
			void multipleHosts() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedHosts(List.of("localhost:8080", "example.com:*"))
					.build();

				assertThatCode(() -> validator.validateHeaders(hostHeader("example.com:3000")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validateHeaders(hostHeader("localhost:8080")))
					.doesNotThrowAnyException();
			}

			@Test
			void combined() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedHost("localhost:8080")
					.allowedHosts(List.of("example.com:*", "test.com:3000"))
					.build();

				assertThatCode(() -> validator.validateHeaders(hostHeader("localhost:8080")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validateHeaders(hostHeader("example.com:9999")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validateHeaders(hostHeader("test.com:3000"))).doesNotThrowAnyException();
			}

		}

	}

	@Nested
	class CombinedOriginAndHostValidation {

		private final DefaultServerTransportSecurityValidator combinedValidator = DefaultServerTransportSecurityValidator
			.builder()
			.allowedOrigin("http://localhost:*")
			.allowedHost("localhost:*")
			.build();

		@Test
		void bothValid() {
			var header = headers("http://localhost:8080", "localhost:8080");

			assertThatCode(() -> combinedValidator.validateHeaders(header)).doesNotThrowAnyException();
		}

		@Test
		void originValidHostInvalid() {
			var header = headers("http://localhost:8080", "malicious.example.com:8080");

			assertThatThrownBy(() -> combinedValidator.validateHeaders(header)).isEqualTo(INVALID_HOST);
		}

		@Test
		void originInvalidHostValid() {
			var header = headers("http://malicious.example.com:8080", "localhost:8080");

			assertThatThrownBy(() -> combinedValidator.validateHeaders(header)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void originMissingHostValid() {
			// Origin missing is OK (same-origin request)
			var header = headers(null, "localhost:8080");

			assertThatCode(() -> combinedValidator.validateHeaders(header)).doesNotThrowAnyException();
		}

		@Test
		void originValidHostMissing() {
			// Host missing is NOT OK when allowedHosts is configured
			var header = headers("http://localhost:8080", null);

			assertThatThrownBy(() -> combinedValidator.validateHeaders(header)).isEqualTo(INVALID_HOST);
		}

	}

	private static Map<String, List<String>> originHeader(String origin) {
		return Map.of("Origin", List.of(origin));
	}

	private static Map<String, List<String>> hostHeader(String host) {
		return Map.of("Host", List.of(host));
	}

	private static Map<String, List<String>> headers(String origin, String host) {
		var map = new HashMap<String, List<String>>();
		if (origin != null) {
			map.put("Origin", List.of(origin));
		}
		if (host != null) {
			map.put("Host", List.of(host));
		}
		return map;
	}

}
