/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link RequestStateCodec}: HMAC-SHA256 over a small JSON payload, binding the
 * state to a principal, an expiry, and the originating method/primitive. This bounds the
 * replay window but does not by itself guarantee single use - enforce that server-side
 * where it matters.
 * <p>
 * Without an explicit key, a random per-process key is generated, which is fine for a
 * single instance but means state sealed by one instance can't be opened by another in a
 * multi-instance deployment - configure a shared {@link Builder#key(byte[])} there.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class HmacRequestStateCodec implements RequestStateCodec {

	private static final Logger logger = LoggerFactory.getLogger(HmacRequestStateCodec.class);

	private static final String ALGORITHM = "HmacSHA256";

	private final byte[] key;

	private final Duration ttl;

	private final Function<McpTransportContext, String> principalExtractor;

	private final Clock clock;

	private final McpJsonMapper jsonMapper;

	private HmacRequestStateCodec(byte[] key, Duration ttl, Function<McpTransportContext, String> principalExtractor,
			Clock clock, McpJsonMapper jsonMapper) {
		this.key = key;
		this.ttl = ttl;
		this.principalExtractor = principalExtractor;
		this.clock = clock;
		this.jsonMapper = jsonMapper;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String seal(McpRequestContext ctx, String state) {
		Payload payload = new Payload(1, state, this.clock.millis() + this.ttl.toMillis(),
				this.principalExtractor.apply(ctx.transportContext()), ctx.method(), ctx.primitiveName());
		String payloadJson;
		try {
			payloadJson = this.jsonMapper.writeValueAsString(payload);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to seal requestState", e);
		}
		String payloadB64 = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
		String mac = base64Url(hmac(payloadB64.getBytes(StandardCharsets.UTF_8)));
		return payloadB64 + "." + mac;
	}

	@Override
	public Optional<String> open(McpRequestContext ctx, String sealed) {
		int dot = sealed == null ? -1 : sealed.lastIndexOf('.');
		if (dot < 0) {
			return Optional.empty();
		}
		String payloadB64 = sealed.substring(0, dot);
		String macB64 = sealed.substring(dot + 1);

		byte[] expectedMac = hmac(payloadB64.getBytes(StandardCharsets.UTF_8));
		byte[] actualMac;
		try {
			actualMac = Base64.getUrlDecoder().decode(macB64);
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		if (!MessageDigest.isEqual(expectedMac, actualMac)) {
			return Optional.empty();
		}

		Payload payload;
		try {
			String payloadJson = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
			payload = this.jsonMapper.readValue(payloadJson, Payload.class);
		}
		catch (Exception e) {
			return Optional.empty();
		}

		if (payload.exp() < this.clock.millis()) {
			return Optional.empty();
		}
		String principal = this.principalExtractor.apply(ctx.transportContext());
		if (!Objects.equals(principal, payload.p())) {
			return Optional.empty();
		}
		if (!Objects.equals(ctx.method(), payload.m())) {
			return Optional.empty();
		}
		if (!Objects.equals(ctx.primitiveName(), payload.n())) {
			return Optional.empty();
		}
		return Optional.of(payload.s());
	}

	private byte[] hmac(byte[] data) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(this.key, ALGORITHM));
			return mac.doFinal(data);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to compute HMAC", e);
		}
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private record Payload(@JsonProperty("v") int v, @JsonProperty("s") String s, @JsonProperty("exp") long exp,
			@JsonProperty("p") String p, @JsonProperty("m") String m, @JsonProperty("n") String n) {
	}

	public static final class Builder {

		private byte[] key;

		private Duration ttl = Duration.ofMinutes(10);

		private Function<McpTransportContext, String> principalExtractor = tc -> null;

		private Clock clock = Clock.systemUTC();

		private McpJsonMapper jsonMapper;

		public Builder key(byte[] key) {
			this.key = key;
			return this;
		}

		public Builder ttl(Duration ttl) {
			this.ttl = ttl;
			return this;
		}

		public Builder principalExtractor(Function<McpTransportContext, String> principalExtractor) {
			this.principalExtractor = principalExtractor;
			return this;
		}

		public Builder clock(Clock clock) {
			this.clock = clock;
			return this;
		}

		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			this.jsonMapper = jsonMapper;
			return this;
		}

		public HmacRequestStateCodec build() {
			byte[] effectiveKey = this.key;
			if (effectiveKey == null) {
				effectiveKey = new byte[32];
				new SecureRandom().nextBytes(effectiveKey);
				logger.warn("HmacRequestStateCodec: no key configured, generated a random per-process key. "
						+ "State sealed by this instance cannot be opened by another instance; "
						+ "configure a shared key for multi-instance deployments.");
			}
			McpJsonMapper mapper = this.jsonMapper != null ? this.jsonMapper : McpJsonDefaults.getMapper();
			return new HmacRequestStateCodec(effectiveKey, this.ttl, this.principalExtractor, this.clock, mapper);
		}

	}

}
