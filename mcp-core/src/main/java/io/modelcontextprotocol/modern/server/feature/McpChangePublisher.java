/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import reactor.core.publisher.Flux;

/**
 * A hot source of {@link ServerChange}s for {@code subscriptions/listen} to forward. Each
 * subscriber (one per active listen stream) sees changes from the moment it subscribes
 * onward.
 *
 * @author Dariusz Jędrzejczyk
 * @see McpChangeFeed
 */
public interface McpChangePublisher {

	Flux<ServerChange> changes();

}
