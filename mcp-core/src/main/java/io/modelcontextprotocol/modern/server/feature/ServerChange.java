/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

/**
 * A change a {@link McpChangePublisher} may emit for {@code subscriptions/listen} to
 * forward. Types it does not recognize are not forwarded.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface ServerChange {

	record ToolsListChanged() implements ServerChange {
	}

	record PromptsListChanged() implements ServerChange {
	}

	record ResourcesListChanged() implements ServerChange {
	}

	record ResourceUpdated(String uri) implements ServerChange {
	}

}
