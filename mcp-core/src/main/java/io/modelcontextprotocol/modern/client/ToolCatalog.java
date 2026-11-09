/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Annotation;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Type;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The tool definitions a client has listed, kept so that a {@code tools/call} by name can
 * mirror the arguments the tool annotates with {@code x-mcp-header} into
 * {@code Mcp-Param-{Name}} headers. Used only with transports that mirror tool params.
 * Thread-safe.
 * <p>
 * A valid definition is used for as long as it is kept, whatever its {@code ttlMs}: the
 * definition only shapes headers, and a server rejects stale headers with {@code -32020}
 * before running the tool, which makes the caller list the tools again. A definition with
 * invalid annotations is listed again once its {@code ttlMs} has passed, so a tool the
 * server has fixed becomes callable.
 */
final class ToolCatalog {

	private static final Logger logger = LoggerFactory.getLogger(ToolCatalog.class);

	// The generation grows with every invalidation and every full listing, so a listing
	// that started before either doesn't replace what they left.
	private final AtomicReference<State> state = new AtomicReference<>(new State(0, Map.of()));

	private final int maxPages;

	/**
	 * @param maxPages the most pages a full listing reads
	 */
	ToolCatalog(int maxPages) {
		this.maxPages = maxPages;
	}

	/**
	 * Keeps the tools of a page and returns the page without the tools whose annotations
	 * are invalid, which are logged. Tools of other pages are kept as they are.
	 */
	ListToolsResult accept(ListToolsResult page) {
		Map<String, Entry> pageEntries = entries(page);
		Map<String, Entry> previous = this.state.getAndUpdate(current -> {
			Map<String, Entry> merged = new LinkedHashMap<>(current.entries());
			merged.putAll(pageEntries);
			return new State(current.generation(), Collections.unmodifiableMap(merged));
		}).entries();
		pageEntries.forEach((name, entry) -> warnIfNewlyInvalid(previous.get(name), entry));
		return withoutInvalid(page, pageEntries);
	}

	/**
	 * Drops every definition, so the next call by name lists the tools again.
	 */
	void invalidate() {
		this.state.updateAndGet(current -> new State(current.generation() + 1, Map.of()));
	}

	/**
	 * The annotations of the tool named {@code name}, from the catalog, or after listing
	 * all tools if the tool isn't known or its invalid definition is stale. Fails with
	 * {@link IllegalArgumentException} if the server doesn't list the tool or its
	 * annotations are invalid.
	 * @param lister lists the page at a cursor, {@code null} for the first page
	 */
	Mono<List<Annotation>> annotations(String name, Function<String, Mono<ListToolsResult>> lister) {
		return Mono.defer(() -> {
			Entry entry = this.state.get().entries().get(name);
			if (entry != null && (entry.problem() == null || entry.isFresh())) {
				return Mono.just(entry.annotations());
			}
			return refreshed(name, lister);
		});
	}

	/**
	 * The annotations of the tool named {@code name} after listing all tools, as
	 * {@link #annotations}.
	 */
	Mono<List<Annotation>> refreshed(String name, Function<String, Mono<ListToolsResult>> lister) {
		return listAll(lister).map(listed -> {
			Entry entry = listed.get(name);
			if (entry == null) {
				// Without a definition the client can't know which arguments the server
				// expects as headers.
				throw new IllegalArgumentException("Tool '" + name
						+ "' isn't listed by the server, so it can't be called by name over this transport");
			}
			return entry.annotations();
		});
	}

	/**
	 * The valid annotations of a tool the caller supplied.
	 * @throws IllegalArgumentException if any annotation is invalid
	 */
	static List<Annotation> annotations(Tool tool) {
		return Entry.of(tool, 0).annotations();
	}

	/**
	 * The {@code Mcp-Param-{Name}} values, keyed by {@code {Name}} and not encoded, for
	 * the {@code arguments} of a {@code tools/call}'s params.
	 */
	static Map<String, String> paramHeaders(List<Annotation> annotations, Map<String, Object> params) {
		if (annotations.isEmpty()) {
			return Map.of();
		}
		Object arguments = params.get("arguments");
		Map<String, String> headers = new LinkedHashMap<>();
		for (Annotation annotation : annotations) {
			Object value = resolve(arguments, annotation.path());
			// No value means no header. An object or array can't be mirrored; the
			// server's input schema validation reports it.
			if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
				continue;
			}
			headers.put(annotation.name(), headerValue(annotation.type(), value));
		}
		return Collections.unmodifiableMap(headers);
	}

	private Mono<Map<String, Entry>> listAll(Function<String, Mono<ListToolsResult>> lister) {
		return Mono.defer(() -> {
			long generation = this.state.get().generation();
			return Listings.pages(McpSchema.METHOD_TOOLS_LIST, lister, ListToolsResult::nextCursor, this.maxPages)
				.map(pages -> {
					Map<String, Entry> listed = new LinkedHashMap<>();
					pages.forEach(page -> listed.putAll(entries(page)));
					State previous = this.state.getAndUpdate(current -> current.generation() == generation
							? new State(generation + 1, Collections.unmodifiableMap(listed)) : current);
					listed.forEach((name, entry) -> warnIfNewlyInvalid(previous.entries().get(name), entry));
					return listed;
				});
		});
	}

	private static Map<String, Entry> entries(ListToolsResult page) {
		long ttlMs = page.ttlMs() == null ? 0 : page.ttlMs();
		Map<String, Entry> entries = new LinkedHashMap<>();
		for (Tool tool : page.tools()) {
			entries.put(tool.name(), Entry.of(tool, ttlMs));
		}
		return entries;
	}

	private static ListToolsResult withoutInvalid(ListToolsResult page, Map<String, Entry> entries) {
		List<Tool> valid = page.tools().stream().filter(tool -> entries.get(tool.name()).problem() == null).toList();
		if (valid.size() == page.tools().size()) {
			return page;
		}
		return new ListToolsResult(valid, page.nextCursor(), page.ttlMs(), page.cacheScope(), page.resultType(),
				page.meta());
	}

	// Each listing repeats the definitions, so a tool is reported once per change of its
	// definition rather than on every refresh.
	private static void warnIfNewlyInvalid(@Nullable Entry previous, Entry entry) {
		if (entry.problem() == null) {
			return;
		}
		if (previous != null && previous.tool().equals(entry.tool())) {
			return;
		}
		logger.warn("Leaving out tool '{}': {}", entry.tool().name(), entry.problem());
	}

	@Nullable private static Object resolve(@Nullable Object arguments, List<String> path) {
		Object value = arguments;
		for (String key : path) {
			if (!(value instanceof Map<?, ?> object)) {
				return null;
			}
			value = object.get(key);
		}
		return value;
	}

	private static String headerValue(Type type, Object value) {
		if (type == Type.INTEGER && value instanceof Number number) {
			BigDecimal decimal = decimal(number);
			if (decimal != null) {
				// A whole number in any form, such as 42.0, is sent as 42.
				return decimal.stripTrailingZeros().toPlainString();
			}
		}
		return String.valueOf(value);
	}

	@Nullable private static BigDecimal decimal(Number number) {
		if (number instanceof BigDecimal decimal) {
			return decimal;
		}
		if (number instanceof BigInteger integer) {
			return new BigDecimal(integer);
		}
		if (number instanceof Double || number instanceof Float) {
			double value = number.doubleValue();
			return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
		}
		return BigDecimal.valueOf(number.longValue());
	}

	private record State(long generation, Map<String, Entry> entries) {
	}

	/**
	 * A listed tool, with its valid annotations or, if any is invalid, the problem.
	 */
	private record Entry(Tool tool, List<Annotation> valid, @Nullable String problem, long expiresAtNanos) {

		static Entry of(Tool tool, long ttlMs) {
			ToolParamHeaderAnnotations parsed = ToolParamHeaderAnnotations.parse(tool);
			String problem = parsed.isValid() ? null
					: "invalid " + ToolParamHeaderAnnotations.KEYWORD + " annotations: "
							+ parsed.invalid()
								.stream()
								.map(ToolParamHeaderAnnotations.InvalidAnnotation::describe)
								.collect(Collectors.joining("; "));
			return new Entry(tool, parsed.valid(), problem, Deadlines.after(Duration.ofMillis(Math.max(ttlMs, 0))));
		}

		List<Annotation> annotations() {
			if (this.problem != null) {
				throw new IllegalArgumentException(
						"Tool '" + this.tool.name() + "' can't be called over this transport: " + this.problem);
			}
			return this.valid;
		}

		boolean isFresh() {
			return System.nanoTime() - this.expiresAtNanos < 0;
		}

	}

}
