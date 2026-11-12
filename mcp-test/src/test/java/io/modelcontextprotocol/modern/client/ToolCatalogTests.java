/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Annotation;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Tool OLD_REGION = tool("Region");

	private static final Tool NEW_REGION = tool("Zone");

	private static Tool tool(String header) {
		return Tool
			.builder("region", Map.of("type", "object", "properties",
					Map.of("region", Map.of("type", "string", "x-mcp-header", header))))
			.build();
	}

	private static ListToolsResult page(Tool tool) {
		return new ListToolsResult(List.of(tool), null, 0L, CacheScope.PRIVATE, ResultType.COMPLETE, null);
	}

	private static List<String> headerNames(List<Annotation> annotations) {
		return annotations.stream().map(Annotation::name).toList();
	}

	@Test
	void listingThatStartedBeforeAnInvalidationIsNotKept() {
		ToolCatalog catalog = new ToolCatalog(true, McpClient.DEFAULT_MAX_LIST_PAGES);
		Sinks.One<ListToolsResult> slowPage = Sinks.one();
		AtomicInteger listings = new AtomicInteger();
		Function<String, Mono<ListToolsResult>> lister = cursor -> listings.incrementAndGet() == 1 ? slowPage.asMono()
				: Mono.just(page(NEW_REGION));

		// The first listing is still waiting for its page when the tools change.
		StepVerifier.create(catalog.annotations("region", lister))
			.then(catalog::invalidate)
			.then(() -> slowPage.tryEmitValue(page(OLD_REGION)))
			.assertNext(annotations -> assertThat(headerNames(annotations)).containsExactly("Region"))
			.verifyComplete();

		StepVerifier.create(catalog.annotations("region", lister))
			.assertNext(annotations -> assertThat(headerNames(annotations)).containsExactly("Zone"))
			.expectComplete()
			.verify(TIMEOUT);
		assertThat(listings).hasValue(2);
	}

	@Test
	void finishedListingIsKept() {
		ToolCatalog catalog = new ToolCatalog(true, McpClient.DEFAULT_MAX_LIST_PAGES);
		AtomicInteger listings = new AtomicInteger();
		Function<String, Mono<ListToolsResult>> lister = cursor -> {
			listings.incrementAndGet();
			return Mono.just(page(OLD_REGION));
		};

		catalog.annotations("region", lister).block(TIMEOUT);
		catalog.annotations("region", lister).block(TIMEOUT);

		assertThat(listings).hasValue(1);
	}

}
