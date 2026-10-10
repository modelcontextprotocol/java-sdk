/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

final class Params {

	private static final Logger logger = LoggerFactory.getLogger(Params.class);

	private Params() {
	}

	static <T> Mono<T> decode(McpJsonMapper jsonMapper, Object params, Class<T> type) {
		try {
			return Mono.just(jsonMapper.convertValue(params, type));
		}
		catch (RuntimeException ex) {
			logger.debug("Malformed params for {}", type.getSimpleName(), ex);
			return Mono.error(McpException.invalidParams("Malformed " + type.getSimpleName()));
		}
	}

}
