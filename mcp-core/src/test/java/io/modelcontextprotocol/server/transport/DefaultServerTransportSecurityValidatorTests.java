/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
			assertThatCode(() -> validator.validateHeaders(emptyAccessor())).doesNotThrowAnyException();
		}

		@Test
		void originHeaderListEmpty() {
			assertThatCode(() -> validator.validateHeaders(name -> List.of())).doesNotThrowAnyException();
		}

		@Test
		void caseInsensitive() {
			var accessor = headerAccessor("Origin", "http://localhost:8080");

			assertThatCode(() -> validator.validateHeaders(accessor)).doesNotThrowAnyException();
		}

		@Test
		void exactMatch() {
			var accessor = originAccessor("http://localhost:8080");

			assertThatCode(() -> validator.validateHeaders(accessor)).doesNotThrowAnyException();
		}

		@Test
		void differentPort() {

			var accessor = originAccessor("http://localhost:3000");

			assertThatThrownBy(() -> validator.validateHeaders(accessor)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void differentHost() {

			var accessor = originAccessor("http://example.com:8080");

			assertThatThrownBy(() -> validator.validateHeaders(accessor)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void differentScheme() {

			var accessor = originAccessor("https://localhost:8080");

			assertThatThrownBy(() -> validator.validateHeaders(accessor)).isEqualTo(INVALID_ORIGIN);
		}

		@Nested
		class WildcardPort {

			private final DefaultServerTransportSecurityValidator wildcardValidator = DefaultServerTransportSecurityValidator
				.builder()
				.allowedOrigin("http://localhost:*")
				.build();

			@Test
			void anyPortWithWildcard() {
				var accessor = originAccessor("http://localhost:3000");

				assertThatCode(() -> wildcardValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void noPortWithWildcard() {
				var accessor = originAccessor("http://localhost");

				assertThatCode(() -> wildcardValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void differentPortWithWildcard() {
				var accessor = originAccessor("http://localhost:8080");

				assertThatCode(() -> wildcardValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void differentHostWithWildcard() {
				var accessor = originAccessor("http://example.com:3000");

				assertThatThrownBy(() -> wildcardValidator.validateHeaders(accessor)).isEqualTo(INVALID_ORIGIN);
			}

			@Test
			void differentSchemeWithWildcard() {
				var accessor = originAccessor("https://localhost:3000");

				assertThatThrownBy(() -> wildcardValidator.validateHeaders(accessor)).isEqualTo(INVALID_ORIGIN);
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
				var accessor = originAccessor("http://example.com:3000");

				assertThatCode(() -> multipleOriginsValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void matchingWildcardInMultiple() {
				var accessor = originAccessor("http://myapp.example.com:9999");

				assertThatCode(() -> multipleOriginsValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void notMatchingAny() {
				var accessor = originAccessor("http://malicious.example.com:1234");

				assertThatThrownBy(() -> multipleOriginsValidator.validateHeaders(accessor)).isEqualTo(INVALID_ORIGIN);
			}

		}

		@Nested
		class BuilderTests {

			@Test
			void shouldAddMultipleOriginsWithAllowedOriginsMethod() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedOrigins(List.of("http://localhost:8080", "http://example.com:*"))
					.build();

				var accessor = originAccessor("http://example.com:3000");

				assertThatCode(() -> validator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void shouldCombineAllowedOriginMethods() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedOrigin("http://localhost:8080")
					.allowedOrigins(List.of("http://example.com:*", "http://test.com:3000"))
					.build();

				assertThatCode(() -> validator.validateHeaders(originAccessor("http://localhost:8080")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validateHeaders(originAccessor("http://example.com:9999")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validateHeaders(originAccessor("http://test.com:3000")))
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
			assertThatCode(() -> validator.validateHeaders(emptyAccessor())).doesNotThrowAnyException();
		}

		@Test
		void missing() {
			assertThatThrownBy(() -> hostValidator.validateHeaders(emptyAccessor())).isEqualTo(INVALID_HOST);
		}

		@Test
		void listEmpty() {
			assertThatThrownBy(() -> hostValidator.validateHeaders(name -> List.of())).isEqualTo(INVALID_HOST);
		}

		@Test
		void caseInsensitive() {
			var accessor = headerAccessor("Host", "localhost:8080");

			assertThatCode(() -> hostValidator.validateHeaders(accessor)).doesNotThrowAnyException();
		}

		@Test
		void exactMatch() {
			var accessor = hostAccessor("localhost:8080");

			assertThatCode(() -> hostValidator.validateHeaders(accessor)).doesNotThrowAnyException();
		}

		@Test
		void differentPort() {
			var accessor = hostAccessor("localhost:3000");

			assertThatThrownBy(() -> hostValidator.validateHeaders(accessor)).isEqualTo(INVALID_HOST);
		}

		@Test
		void differentHost() {
			var accessor = hostAccessor("example.com:8080");

			assertThatThrownBy(() -> hostValidator.validateHeaders(accessor)).isEqualTo(INVALID_HOST);
		}

		@Nested
		class HostWildcardPort {

			private final DefaultServerTransportSecurityValidator wildcardHostValidator = DefaultServerTransportSecurityValidator
				.builder()
				.allowedHost("localhost:*")
				.build();

			@Test
			void anyPort() {
				var accessor = hostAccessor("localhost:3000");

				assertThatCode(() -> wildcardHostValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void noPort() {
				var accessor = hostAccessor("localhost");

				assertThatCode(() -> wildcardHostValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void differentHost() {
				var accessor = hostAccessor("example.com:3000");

				assertThatThrownBy(() -> wildcardHostValidator.validateHeaders(accessor)).isEqualTo(INVALID_HOST);
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
				var accessor = hostAccessor("example.com:3000");

				assertThatCode(() -> multipleHostsValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void wildcard() {
				var accessor = hostAccessor("myapp.example.com:9999");

				assertThatCode(() -> multipleHostsValidator.validateHeaders(accessor)).doesNotThrowAnyException();
			}

			@Test
			void differentHost() {
				var accessor = hostAccessor("malicious.example.com:3000");

				assertThatThrownBy(() -> multipleHostsValidator.validateHeaders(accessor)).isEqualTo(INVALID_HOST);
			}

			@Test
			void differentPort() {
				var accessor = hostAccessor("localhost:8080");

				assertThatThrownBy(() -> multipleHostsValidator.validateHeaders(accessor)).isEqualTo(INVALID_HOST);
			}

		}

		@Nested
		class HostBuilderTests {

			@Test
			void multipleHosts() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedHosts(List.of("localhost:8080", "example.com:*"))
					.build();

				assertThatCode(() -> validator.validateHeaders(hostAccessor("example.com:3000")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validateHeaders(hostAccessor("localhost:8080")))
					.doesNotThrowAnyException();
			}

			@Test
			void combined() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedHost("localhost:8080")
					.allowedHosts(List.of("example.com:*", "test.com:3000"))
					.build();

				assertThatCode(() -> validator.validateHeaders(hostAccessor("localhost:8080")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validateHeaders(hostAccessor("example.com:9999")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validateHeaders(hostAccessor("test.com:3000")))
					.doesNotThrowAnyException();
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
			var accessor = combinedAccessor("http://localhost:8080", "localhost:8080");

			assertThatCode(() -> combinedValidator.validateHeaders(accessor)).doesNotThrowAnyException();
		}

		@Test
		void originValidHostInvalid() {
			var accessor = combinedAccessor("http://localhost:8080", "malicious.example.com:8080");

			assertThatThrownBy(() -> combinedValidator.validateHeaders(accessor)).isEqualTo(INVALID_HOST);
		}

		@Test
		void originInvalidHostValid() {
			var accessor = combinedAccessor("http://malicious.example.com:8080", "localhost:8080");

			assertThatThrownBy(() -> combinedValidator.validateHeaders(accessor)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void originMissingHostValid() {
			// Origin missing is OK (same-origin request)
			var accessor = combinedAccessor(null, "localhost:8080");

			assertThatCode(() -> combinedValidator.validateHeaders(accessor)).doesNotThrowAnyException();
		}

		@Test
		void originValidHostMissing() {
			// Host missing is NOT OK when allowedHosts is configured
			var accessor = combinedAccessor("http://localhost:8080", null);

			assertThatThrownBy(() -> combinedValidator.validateHeaders(accessor)).isEqualTo(INVALID_HOST);
		}

	}

	private static Function<String, List<String>> emptyAccessor() {
		return name -> List.of();
	}

	private static Function<String, List<String>> headerAccessor(String headerName, String value) {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put(headerName, List.of(value));
		return name -> headers.getOrDefault(name, List.of());
	}

	private static Function<String, List<String>> originAccessor(String origin) {
		return headerAccessor("Origin", origin);
	}

	private static Function<String, List<String>> hostAccessor(String host) {
		return headerAccessor("Host", host);
	}

	private static Function<String, List<String>> combinedAccessor(String origin, String host) {
		Map<String, List<String>> headers = new HashMap<>();
		if (origin != null) {
			headers.put("Origin", List.of(origin));
		}
		if (host != null) {
			headers.put("Host", List.of(host));
		}
		return name -> headers.getOrDefault(name, List.of());
	}

}
