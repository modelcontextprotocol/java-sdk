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
			assertThatCode(() -> validator.validate(emptyAccessor())).doesNotThrowAnyException();
		}

		@Test
		void originHeaderListEmpty() {
			assertThatCode(() -> validator.validate(headerAccessor())).doesNotThrowAnyException();
		}

		@Test
		void caseInsensitive() {
			var accessor = headerAccessor("Origin", "http://localhost:8080");

			assertThatCode(() -> validator.validate(accessor)).doesNotThrowAnyException();
		}

		@Test
		void exactMatch() {
			var accessor = originAccessor("http://localhost:8080");

			assertThatCode(() -> validator.validate(accessor)).doesNotThrowAnyException();
		}

		@Test
		void differentPort() {

			var accessor = originAccessor("http://localhost:3000");

			assertThatThrownBy(() -> validator.validate(accessor)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void differentHost() {

			var accessor = originAccessor("http://example.com:8080");

			assertThatThrownBy(() -> validator.validate(accessor)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void differentScheme() {

			var accessor = originAccessor("https://localhost:8080");

			assertThatThrownBy(() -> validator.validate(accessor)).isEqualTo(INVALID_ORIGIN);
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

				assertThatCode(() -> wildcardValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void noPortWithWildcard() {
				var accessor = originAccessor("http://localhost");

				assertThatCode(() -> wildcardValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void differentPortWithWildcard() {
				var accessor = originAccessor("http://localhost:8080");

				assertThatCode(() -> wildcardValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void differentHostWithWildcard() {
				var accessor = originAccessor("http://example.com:3000");

				assertThatThrownBy(() -> wildcardValidator.validate(accessor)).isEqualTo(INVALID_ORIGIN);
			}

			@Test
			void differentSchemeWithWildcard() {
				var accessor = originAccessor("https://localhost:3000");

				assertThatThrownBy(() -> wildcardValidator.validate(accessor)).isEqualTo(INVALID_ORIGIN);
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

				assertThatCode(() -> multipleOriginsValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void matchingWildcardInMultiple() {
				var accessor = originAccessor("http://myapp.example.com:9999");

				assertThatCode(() -> multipleOriginsValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void notMatchingAny() {
				var accessor = originAccessor("http://malicious.example.com:1234");

				assertThatThrownBy(() -> multipleOriginsValidator.validate(accessor)).isEqualTo(INVALID_ORIGIN);
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

				assertThatCode(() -> validator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void shouldCombineAllowedOriginMethods() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedOrigin("http://localhost:8080")
					.allowedOrigins(List.of("http://example.com:*", "http://test.com:3000"))
					.build();

				assertThatCode(() -> validator.validate(originAccessor("http://localhost:8080")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validate(originAccessor("http://example.com:9999")))
					.doesNotThrowAnyException();
				assertThatCode(() -> validator.validate(originAccessor("http://test.com:3000")))
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
			assertThatCode(() -> validator.validate(emptyAccessor())).doesNotThrowAnyException();
		}

		@Test
		void missing() {
			assertThatThrownBy(() -> hostValidator.validate(emptyAccessor())).isEqualTo(INVALID_HOST);
		}

		@Test
		void listEmpty() {
			assertThatThrownBy(() -> hostValidator.validate(headerAccessor())).isEqualTo(INVALID_HOST);
		}

		@Test
		void caseInsensitive() {
			var accessor = headerAccessor("Host", "localhost:8080");

			assertThatCode(() -> hostValidator.validate(accessor)).doesNotThrowAnyException();
		}

		@Test
		void exactMatch() {
			var accessor = hostAccessor("localhost:8080");

			assertThatCode(() -> hostValidator.validate(accessor)).doesNotThrowAnyException();
		}

		@Test
		void differentPort() {
			var accessor = hostAccessor("localhost:3000");

			assertThatThrownBy(() -> hostValidator.validate(accessor)).isEqualTo(INVALID_HOST);
		}

		@Test
		void differentHost() {
			var accessor = hostAccessor("example.com:8080");

			assertThatThrownBy(() -> hostValidator.validate(accessor)).isEqualTo(INVALID_HOST);
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

				assertThatCode(() -> wildcardHostValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void noPort() {
				var accessor = hostAccessor("localhost");

				assertThatCode(() -> wildcardHostValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void differentHost() {
				var accessor = hostAccessor("example.com:3000");

				assertThatThrownBy(() -> wildcardHostValidator.validate(accessor)).isEqualTo(INVALID_HOST);
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

				assertThatCode(() -> multipleHostsValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void wildcard() {
				var accessor = hostAccessor("myapp.example.com:9999");

				assertThatCode(() -> multipleHostsValidator.validate(accessor)).doesNotThrowAnyException();
			}

			@Test
			void differentHost() {
				var accessor = hostAccessor("malicious.example.com:3000");

				assertThatThrownBy(() -> multipleHostsValidator.validate(accessor)).isEqualTo(INVALID_HOST);
			}

			@Test
			void differentPort() {
				var accessor = hostAccessor("localhost:8080");

				assertThatThrownBy(() -> multipleHostsValidator.validate(accessor)).isEqualTo(INVALID_HOST);
			}

		}

		@Nested
		class HostBuilderTests {

			@Test
			void multipleHosts() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedHosts(List.of("localhost:8080", "example.com:*"))
					.build();

				assertThatCode(() -> validator.validate(hostAccessor("example.com:3000"))).doesNotThrowAnyException();
				assertThatCode(() -> validator.validate(hostAccessor("localhost:8080"))).doesNotThrowAnyException();
			}

			@Test
			void combined() {
				DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
					.allowedHost("localhost:8080")
					.allowedHosts(List.of("example.com:*", "test.com:3000"))
					.build();

				assertThatCode(() -> validator.validate(hostAccessor("localhost:8080"))).doesNotThrowAnyException();
				assertThatCode(() -> validator.validate(hostAccessor("example.com:9999"))).doesNotThrowAnyException();
				assertThatCode(() -> validator.validate(hostAccessor("test.com:3000"))).doesNotThrowAnyException();
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

			assertThatCode(() -> combinedValidator.validate(accessor)).doesNotThrowAnyException();
		}

		@Test
		void originValidHostInvalid() {
			var accessor = combinedAccessor("http://localhost:8080", "malicious.example.com:8080");

			assertThatThrownBy(() -> combinedValidator.validate(accessor)).isEqualTo(INVALID_HOST);
		}

		@Test
		void originInvalidHostValid() {
			var accessor = combinedAccessor("http://malicious.example.com:8080", "localhost:8080");

			assertThatThrownBy(() -> combinedValidator.validate(accessor)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void originMissingHostValid() {
			// Origin missing is OK (same-origin request)
			var accessor = combinedAccessor(null, "localhost:8080");

			assertThatCode(() -> combinedValidator.validate(accessor)).doesNotThrowAnyException();
		}

		@Test
		void originValidHostMissing() {
			// Host missing is NOT OK when allowedHosts is configured
			var accessor = combinedAccessor("http://localhost:8080", null);

			assertThatThrownBy(() -> combinedValidator.validate(accessor)).isEqualTo(INVALID_HOST);
		}

	}

	@Nested
	class DeprecatedMapBasedApi {

		@Test
		void originValidation() {
			Map<String, List<String>> headers = new HashMap<>();
			headers.put("Origin", List.of("http://localhost:8080"));

			assertThatCode(() -> validator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void originRejected() {
			Map<String, List<String>> headers = new HashMap<>();
			headers.put("Origin", List.of("http://malicious.example.com"));

			assertThatThrownBy(() -> validator.validateHeaders(headers)).isEqualTo(INVALID_ORIGIN);
		}

		@Test
		void caseInsensitiveHeaderLookup() {
			Map<String, List<String>> headers = new HashMap<>();
			headers.put("origin", List.of("http://localhost:8080"));

			assertThatCode(() -> validator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void hostValidation() {
			DefaultServerTransportSecurityValidator hostValidator = DefaultServerTransportSecurityValidator.builder()
				.allowedHost("localhost:8080")
				.build();

			Map<String, List<String>> headers = new HashMap<>();
			headers.put("Host", List.of("localhost:8080"));

			assertThatCode(() -> hostValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

		@Test
		void hostRejected() {
			DefaultServerTransportSecurityValidator hostValidator = DefaultServerTransportSecurityValidator.builder()
				.allowedHost("localhost:8080")
				.build();

			Map<String, List<String>> headers = new HashMap<>();
			headers.put("Host", List.of("malicious.com:8080"));

			assertThatThrownBy(() -> hostValidator.validateHeaders(headers)).isEqualTo(INVALID_HOST);
		}

		@Test
		void emptyHeaders() {
			assertThatCode(() -> validator.validateHeaders(new HashMap<>())).doesNotThrowAnyException();
		}

		@Test
		void combinedOriginAndHost() {
			DefaultServerTransportSecurityValidator combinedValidator = DefaultServerTransportSecurityValidator
				.builder()
				.allowedOrigin("http://localhost:*")
				.allowedHost("localhost:*")
				.build();

			Map<String, List<String>> headers = new HashMap<>();
			headers.put("Origin", List.of("http://localhost:8080"));
			headers.put("Host", List.of("localhost:8080"));

			assertThatCode(() -> combinedValidator.validateHeaders(headers)).doesNotThrowAnyException();
		}

	}

	@Nested
	class ValidatorCompatibility {

		@Test
		void noopAcceptsAll() {
			assertThatCode(() -> ServerHttpHeaderValidator.NOOP.validate(emptyAccessor())).doesNotThrowAnyException();
			assertThatCode(() -> ServerTransportSecurityValidator.NOOP.validateHeaders(new HashMap<>()))
				.doesNotThrowAnyException();
		}

		@Test
		void legacyValidatorAdaptsToHeaderAccessor() {
			ServerTransportSecurityValidator legacyValidator = headers -> {
				List<String> origins = headers.getOrDefault("origin", List.of());
				if (!origins.isEmpty() && origins.get(0).contains("evil")) {
					throw new ServerTransportSecurityException(403, "Invalid Origin header");
				}
			};
			ServerHttpHeaderValidator headerValidator = ServerTransportSecurityValidator
				.toHttpHeaderValidator(legacyValidator);

			assertThatCode(() -> headerValidator.validate(originAccessor("http://good.example.com")))
				.doesNotThrowAnyException();

			assertThatThrownBy(() -> headerValidator.validate(originAccessor("http://evil.example.com")))
				.isEqualTo(INVALID_ORIGIN);
		}

	}

	private static HeaderAccessor emptyAccessor() {
		return headerAccessor();
	}

	private static HeaderAccessor headerAccessor(String... namesAndValues) {
		Map<String, List<String>> headers = new HashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			headers.put(namesAndValues[i], List.of(namesAndValues[i + 1]));
		}
		return new HeaderAccessor() {
			@Override
			public List<String> getHeader(String name) {
				return headers.getOrDefault(name, List.of());
			}

			@Override
			public List<String> getHeaderNames() {
				return List.copyOf(headers.keySet());
			}
		};
	}

	private static HeaderAccessor originAccessor(String origin) {
		return headerAccessor("Origin", origin);
	}

	private static HeaderAccessor hostAccessor(String host) {
		return headerAccessor("Host", host);
	}

	private static HeaderAccessor combinedAccessor(String origin, String host) {
		Map<String, List<String>> headers = new HashMap<>();
		if (origin != null) {
			headers.put("Origin", List.of(origin));
		}
		if (host != null) {
			headers.put("Host", List.of(host));
		}
		return new HeaderAccessor() {
			@Override
			public List<String> getHeader(String name) {
				return headers.getOrDefault(name, List.of());
			}

			@Override
			public List<String> getHeaderNames() {
				return List.copyOf(headers.keySet());
			}
		};
	}

}
