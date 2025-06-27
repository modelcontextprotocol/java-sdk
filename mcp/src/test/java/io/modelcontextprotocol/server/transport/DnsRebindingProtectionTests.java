package io.modelcontextprotocol.server.transport;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DNS rebinding protection configuration.
 */
public class DnsRebindingProtectionTests {

	@Test
	void testDefaultConfiguration() {
		DnsRebindingProtection config = DnsRebindingProtection.builder().build();

		// Test default behavior - when allowed lists are empty and headers are provided,
		// validation fails because the headers are not in the (empty) allowed lists
		assertThat(config.isValid("any.host.com", "http://any.origin.com")).isFalse();
		assertThat(config.isValid("localhost", null)).isFalse();
		assertThat(config.isValid(null, "http://example.com")).isFalse();
		// Null values are allowed when lists are empty
		assertThat(config.isValid(null, null)).isTrue();
	}

	@Test
	void testDisableDnsRebindingProtection() {
		DnsRebindingProtection config = DnsRebindingProtection.builder()
			.enableDnsRebindingProtection(false)
			.allowedHost("localhost") // Should be ignored when protection is disabled
			.allowedOrigin("http://localhost") // Should be ignored when protection is
												// disabled
			.build();

		// When protection is disabled, all hosts and origins should be allowed
		assertThat(config.isValid("evil.com", "http://evil.com")).isTrue();
		assertThat(config.isValid("any.host", "http://any.origin")).isTrue();
		assertThat(config.isValid(null, null)).isTrue();
	}

	@Test
	void testHostValidation() {
		DnsRebindingProtection config = DnsRebindingProtection.builder()
			.allowedHost("localhost")
			.allowedHost("127.0.0.1")
			.build();

		// Valid hosts
		assertThat(config.isValid("localhost", null)).isTrue();
		assertThat(config.isValid("127.0.0.1", null)).isTrue();

		// Invalid hosts
		assertThat(config.isValid("evil.com", null)).isFalse();

		// Null host is allowed when no specific hosts are being checked
		assertThat(config.isValid(null, null)).isTrue();
	}

	@Test
	void testOriginValidation() {
		DnsRebindingProtection config = DnsRebindingProtection.builder()
			.allowedOrigin("http://localhost:8080")
			.allowedOrigin("https://app.example.com")
			.build();

		// Valid origins
		assertThat(config.isValid(null, "http://localhost:8080")).isTrue();
		assertThat(config.isValid(null, "https://app.example.com")).isTrue();

		// Invalid origins
		assertThat(config.isValid(null, "http://evil.com")).isFalse();

		// Null origin is allowed when no specific origins are being checked
		assertThat(config.isValid(null, null)).isTrue();
	}

	@Test
	void testCombinedHostAndOriginValidation() {
		DnsRebindingProtection config = DnsRebindingProtection.builder()
			.allowedHost("localhost")
			.allowedOrigin("http://localhost:8080")
			.build();

		// Both valid
		assertThat(config.isValid("localhost", "http://localhost:8080")).isTrue();

		// Host valid, origin invalid
		assertThat(config.isValid("localhost", "http://evil.com")).isFalse();

		// Host invalid, origin valid
		assertThat(config.isValid("evil.com", "http://localhost:8080")).isFalse();

		// Both invalid
		assertThat(config.isValid("evil.com", "http://evil.com")).isFalse();
	}

	@Test
	void testCaseInsensitiveHostAndOrigin() {
		DnsRebindingProtection config = DnsRebindingProtection.builder()
			.allowedHost("LOCALHOST")
			.allowedOrigin("HTTP://LOCALHOST:8080")
			.build();

		// Case insensitive matching
		assertThat(config.isValid("localhost", null)).isTrue();
		assertThat(config.isValid("LOCALHOST", null)).isTrue();
		assertThat(config.isValid("LoCaLhOsT", null)).isTrue();

		assertThat(config.isValid(null, "http://localhost:8080")).isTrue();
		assertThat(config.isValid(null, "HTTP://LOCALHOST:8080")).isTrue();
	}

	@Test
	void testEmptyAllowedListsDenyNonNull() {
		DnsRebindingProtection config = DnsRebindingProtection.builder().build();

		// When allowed lists are empty and headers are provided, validation fails
		assertThat(config.isValid("any.host.com", "http://any.origin.com")).isFalse();
		assertThat(config.isValid("random.host", "http://random.origin")).isFalse();
		// But null values are allowed
		assertThat(config.isValid(null, null)).isTrue();
	}

	@Test
	void testBuilderWithSets() {
		Set<String> hosts = Set.of("host1.com", "host2.com");
		Set<String> origins = Set.of("http://origin1.com", "http://origin2.com");

		DnsRebindingProtection config = DnsRebindingProtection.builder()
			.allowedHosts(hosts)
			.allowedOrigins(origins)
			.build();

		assertThat(config.isValid("host1.com", null)).isTrue();
		assertThat(config.isValid("host2.com", null)).isTrue();
		assertThat(config.isValid("host3.com", null)).isFalse();

		assertThat(config.isValid(null, "http://origin1.com")).isTrue();
		assertThat(config.isValid(null, "http://origin2.com")).isTrue();
		assertThat(config.isValid(null, "http://origin3.com")).isFalse();
	}

	@Test
	void testNullValuesWithConfiguredLists() {
		DnsRebindingProtection config = DnsRebindingProtection.builder()
			.allowedHost("localhost")
			.allowedOrigin("http://localhost")
			.build();

		// Null values should be allowed when no check is needed for that header
		assertThat(config.isValid(null, "http://localhost")).isTrue();
		assertThat(config.isValid("localhost", null)).isTrue();
		assertThat(config.isValid(null, null)).isTrue();
	}

}