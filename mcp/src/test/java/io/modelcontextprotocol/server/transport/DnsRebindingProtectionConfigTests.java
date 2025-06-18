package io.modelcontextprotocol.server.transport;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DNS rebinding protection configuration.
 */
public class DnsRebindingProtectionConfigTests {

	@Test
	void testDefaultConfiguration() {
		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder().build();

		// Test default behavior - when allowed lists are empty and headers are provided,
		// validation fails because the headers are not in the (empty) allowed lists
		assertThat(config.validate("any.host.com", "http://any.origin.com")).isFalse();
		assertThat(config.validate("localhost", null)).isFalse();
		assertThat(config.validate(null, "http://example.com")).isFalse();
		// Null values are allowed when lists are empty
		assertThat(config.validate(null, null)).isTrue();
	}

	@Test
	void testDisableDnsRebindingProtection() {
		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder()
			.enableDnsRebindingProtection(false)
			.allowedHost("localhost") // Should be ignored when protection is disabled
			.allowedOrigin("http://localhost") // Should be ignored when protection is
												// disabled
			.build();

		// When protection is disabled, all hosts and origins should be allowed
		assertThat(config.validate("evil.com", "http://evil.com")).isTrue();
		assertThat(config.validate("any.host", "http://any.origin")).isTrue();
		assertThat(config.validate(null, null)).isTrue();
	}

	@Test
	void testHostValidation() {
		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder()
			.allowedHost("localhost")
			.allowedHost("127.0.0.1")
			.build();

		// Valid hosts
		assertThat(config.validate("localhost", null)).isTrue();
		assertThat(config.validate("127.0.0.1", null)).isTrue();

		// Invalid hosts
		assertThat(config.validate("evil.com", null)).isFalse();

		// Null host is allowed when no specific hosts are being checked
		assertThat(config.validate(null, null)).isTrue();
	}

	@Test
	void testOriginValidation() {
		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder()
			.allowedOrigin("http://localhost:8080")
			.allowedOrigin("https://app.example.com")
			.build();

		// Valid origins
		assertThat(config.validate(null, "http://localhost:8080")).isTrue();
		assertThat(config.validate(null, "https://app.example.com")).isTrue();

		// Invalid origins
		assertThat(config.validate(null, "http://evil.com")).isFalse();

		// Null origin is allowed when no specific origins are being checked
		assertThat(config.validate(null, null)).isTrue();
	}

	@Test
	void testCombinedHostAndOriginValidation() {
		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder()
			.allowedHost("localhost")
			.allowedOrigin("http://localhost:8080")
			.build();

		// Both valid
		assertThat(config.validate("localhost", "http://localhost:8080")).isTrue();

		// Host valid, origin invalid
		assertThat(config.validate("localhost", "http://evil.com")).isFalse();

		// Host invalid, origin valid
		assertThat(config.validate("evil.com", "http://localhost:8080")).isFalse();

		// Both invalid
		assertThat(config.validate("evil.com", "http://evil.com")).isFalse();
	}

	@Test
	void testCaseInsensitiveHostAndOrigin() {
		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder()
			.allowedHost("LOCALHOST")
			.allowedOrigin("HTTP://LOCALHOST:8080")
			.build();

		// Case insensitive matching
		assertThat(config.validate("localhost", null)).isTrue();
		assertThat(config.validate("LOCALHOST", null)).isTrue();
		assertThat(config.validate("LoCaLhOsT", null)).isTrue();

		assertThat(config.validate(null, "http://localhost:8080")).isTrue();
		assertThat(config.validate(null, "HTTP://LOCALHOST:8080")).isTrue();
	}

	@Test
	void testEmptyAllowedListsDenyNonNull() {
		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder().build();

		// When allowed lists are empty and headers are provided, validation fails
		assertThat(config.validate("any.host.com", "http://any.origin.com")).isFalse();
		assertThat(config.validate("random.host", "http://random.origin")).isFalse();
		// But null values are allowed
		assertThat(config.validate(null, null)).isTrue();
	}

	@Test
	void testBuilderWithSets() {
		Set<String> hosts = Set.of("host1.com", "host2.com");
		Set<String> origins = Set.of("http://origin1.com", "http://origin2.com");

		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder()
			.allowedHosts(hosts)
			.allowedOrigins(origins)
			.build();

		assertThat(config.validate("host1.com", null)).isTrue();
		assertThat(config.validate("host2.com", null)).isTrue();
		assertThat(config.validate("host3.com", null)).isFalse();

		assertThat(config.validate(null, "http://origin1.com")).isTrue();
		assertThat(config.validate(null, "http://origin2.com")).isTrue();
		assertThat(config.validate(null, "http://origin3.com")).isFalse();
	}

	@Test
	void testNullValuesWithConfiguredLists() {
		DnsRebindingProtectionConfig config = DnsRebindingProtectionConfig.builder()
			.allowedHost("localhost")
			.allowedOrigin("http://localhost")
			.build();

		// Null values should be allowed when no check is needed for that header
		assertThat(config.validate(null, "http://localhost")).isTrue();
		assertThat(config.validate("localhost", null)).isTrue();
		assertThat(config.validate(null, null)).isTrue();
	}

}