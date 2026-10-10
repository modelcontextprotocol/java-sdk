/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacRequestStateCodecTests {

	private static McpRequestContext ctx(String method, String primitiveName, McpTransportContext tc) {
		Map<String, Object> meta = Map.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION,
				MetaKeys.CLIENT_CAPABILITIES, Map.of());
		return new McpRequestContext(1, method, McpSchema.LATEST_PROTOCOL_VERSION, ClientCapabilities.NONE, null, null,
				primitiveName, meta, tc, false, false);
	}

	private static HmacRequestStateCodec codec(Clock clock) {
		return HmacRequestStateCodec.builder()
			.key("test-key-0123456789abcdef".getBytes())
			.clock(clock)
			.jsonMapper(new GsonMcpJsonMapper())
			.build();
	}

	@Test
	void roundTrip() {
		Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
		HmacRequestStateCodec codec = codec(clock);
		McpRequestContext ctx = ctx("tools/call", "echo", McpTransportContext.EMPTY);

		String sealed = codec.seal(ctx, "plaintext-state");
		assertThat(sealed).isNotEqualTo("plaintext-state");
		assertThat(codec.open(ctx, sealed)).contains("plaintext-state");
	}

	@Test
	void tamperedPayloadIsRejected() {
		Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
		HmacRequestStateCodec codec = codec(clock);
		McpRequestContext ctx = ctx("tools/call", "echo", McpTransportContext.EMPTY);
		String sealed = codec.seal(ctx, "state");
		String tampered = "x" + sealed.substring(1);

		assertThat(codec.open(ctx, tampered)).isEmpty();
	}

	@Test
	void tamperedMacIsRejected() {
		Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
		HmacRequestStateCodec codec = codec(clock);
		McpRequestContext ctx = ctx("tools/call", "echo", McpTransportContext.EMPTY);
		String sealed = codec.seal(ctx, "state");
		String tampered = sealed.substring(0, sealed.length() - 1) + (sealed.endsWith("A") ? "B" : "A");

		assertThat(codec.open(ctx, tampered)).isEmpty();
	}

	@Test
	void expiredStateIsRejected() {
		Instant start = Instant.parse("2026-01-01T00:00:00Z");
		AtomicReference<Instant> now = new AtomicReference<>(start);
		Clock clock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		HmacRequestStateCodec codec = HmacRequestStateCodec.builder()
			.key("test-key-0123456789abcdef".getBytes())
			.clock(clock)
			.ttl(Duration.ofMinutes(1))
			.jsonMapper(new GsonMcpJsonMapper())
			.build();
		McpRequestContext ctx = ctx("tools/call", "echo", McpTransportContext.EMPTY);
		String sealed = codec.seal(ctx, "state");

		now.set(start.plus(Duration.ofMinutes(2)));
		assertThat(codec.open(ctx, sealed)).isEmpty();
	}

	@Test
	void differentPrincipalIsRejected() {
		Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
		HmacRequestStateCodec codec = HmacRequestStateCodec.builder()
			.key("test-key-0123456789abcdef".getBytes())
			.clock(clock)
			.principalExtractor(tc -> (String) tc.get("user"))
			.jsonMapper(new GsonMcpJsonMapper())
			.build();
		McpTransportContext alice = McpTransportContext.create(Map.of("user", "alice"));
		McpTransportContext bob = McpTransportContext.create(Map.of("user", "bob"));
		String sealed = codec.seal(ctx("tools/call", "echo", alice), "state");

		assertThat(codec.open(ctx("tools/call", "echo", bob), sealed)).isEmpty();
	}

	@Test
	void differentMethodIsRejected() {
		Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
		HmacRequestStateCodec codec = codec(clock);
		String sealed = codec.seal(ctx("tools/call", "echo", McpTransportContext.EMPTY), "state");

		assertThat(codec.open(ctx("resources/read", "echo", McpTransportContext.EMPTY), sealed)).isEmpty();
	}

	@Test
	void differentPrimitiveNameIsRejected() {
		Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
		HmacRequestStateCodec codec = codec(clock);
		String sealed = codec.seal(ctx("tools/call", "echo", McpTransportContext.EMPTY), "state");

		assertThat(codec.open(ctx("tools/call", "other-tool", McpTransportContext.EMPTY), sealed)).isEmpty();
	}

}
