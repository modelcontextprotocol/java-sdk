/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.feature.McpAsyncCompletionHandler;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResource;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResourceTemplate;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.ResourceTemplatesPage;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManager;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import reactor.core.publisher.Mono;

/**
 * An {@link McpAsyncResourceRepository} that keeps resources and resource templates in
 * memory, each paired with the {@link McpAsyncResource} that reads it.
 * <p>
 * A {@code resources/read} URI is served by the resource registered under exactly that
 * URI, or else by the first template, in {@code uriTemplate} order, whose URI template
 * matches it. Resources and templates can be added and removed at runtime; each change is
 * broadcast as {@link ServerChange.ResourcesListChanged} when a change broadcaster is
 * configured, and {@link #resourceUpdated(String)} broadcasts
 * {@link ServerChange.ResourceUpdated}. {@code resources/list} and
 * {@code resources/templates/list} return entries sorted by URI and URI template, paged
 * when a page size is set.
 * <p>
 * Reading and changing the catalog never blocks. {@code list} and {@code find} read it
 * when subscribed to, not when called, and report an invalid cursor as an error signal.
 * <p>
 * Template variables are completed through
 * {@link McpAsyncResourceTemplate#withCompletionHandler(McpAsyncCompletionHandler) the
 * template's completion handler} only when the repository is built with
 * {@link Builder#supportsCompletion(boolean) supportsCompletion(true)}.
 *
 * @author Christian Tzolov
 */
public final class InMemoryAsyncResourceRepository implements McpAsyncResourceRepository {

	private final NavigableMap<String, StaticResource> resources = new ConcurrentSkipListMap<>();

	private final NavigableMap<String, TemplatedResource> templates = new ConcurrentSkipListMap<>();

	private final McpUriTemplateManagerFactory uriTemplateManagerFactory;

	private final McpChangeBroadcaster changeBroadcaster;

	private final Integer pageSize;

	private final boolean supportsSubscribe;

	private final boolean supportsCompletion;

	private InMemoryAsyncResourceRepository(Builder builder) {
		this.uriTemplateManagerFactory = builder.uriTemplateManagerFactory;
		this.changeBroadcaster = builder.changeBroadcaster;
		this.pageSize = builder.pageSize;
		this.supportsSubscribe = builder.supportsSubscribe;
		this.supportsCompletion = builder.supportsCompletion;
		builder.resources.forEach(resource -> putResource(resource.definition(), resource.reader()));
		builder.templates.forEach(template -> putTemplate(template.template(), template.reader()));
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Adds a resource read by {@code reader} and broadcasts
	 * {@link ServerChange.ResourcesListChanged}.
	 * @throws IllegalArgumentException if a resource with the same URI exists
	 */
	public void addResource(Resource resource, McpAsyncResource reader) {
		putResource(resource, reader);
		broadcastListChanged();
	}

	/**
	 * Adds a resource template whose matching URIs are read by {@code reader}, and
	 * broadcasts {@link ServerChange.ResourcesListChanged}.
	 * @throws IllegalArgumentException if a template with the same URI template exists
	 */
	public void addTemplate(McpAsyncResourceTemplate template, McpAsyncResource reader) {
		putTemplate(template, reader);
		broadcastListChanged();
	}

	/**
	 * Removes the resource with this URI and, if it existed, broadcasts
	 * {@link ServerChange.ResourcesListChanged}.
	 * @return whether a resource was removed
	 */
	public boolean removeResource(String uri) {
		Assert.hasText(uri, "uri must not be empty");
		return removed(this.resources.remove(uri) != null);
	}

	/**
	 * Removes the resource template with this URI template and, if it existed, broadcasts
	 * {@link ServerChange.ResourcesListChanged}.
	 * @return whether a template was removed
	 */
	public boolean removeTemplate(String uriTemplate) {
		Assert.hasText(uriTemplate, "uriTemplate must not be empty");
		return removed(this.templates.remove(uriTemplate) != null);
	}

	/**
	 * Broadcasts {@link ServerChange.ResourceUpdated} for {@code uri}, telling
	 * subscribers that its content changed. The server forwards it only when the
	 * repository is built with {@link Builder#supportsSubscribe(boolean)
	 * supportsSubscribe(true)}.
	 */
	public void resourceUpdated(String uri) {
		Assert.hasText(uri, "uri must not be empty");
		if (this.changeBroadcaster != null) {
			this.changeBroadcaster.broadcast(new ServerChange.ResourceUpdated(uri));
		}
	}

	@Override
	public Mono<ResourcesPage> list(McpRequestContext ctx, String cursor) {
		return Mono.fromSupplier(() -> page(cursor));
	}

	@Override
	public Mono<ResourceTemplatesPage> listTemplates(McpRequestContext ctx, String cursor) {
		return Mono.fromSupplier(() -> templatesPage(cursor));
	}

	@Override
	public Mono<McpAsyncResource> find(McpRequestContext ctx, String uri) {
		return Mono.fromSupplier(() -> reader(uri));
	}

	@Override
	public Mono<McpAsyncResourceTemplate> findTemplate(McpRequestContext ctx, String uriTemplate) {
		return Mono.fromSupplier(() -> this.templates.get(uriTemplate)).map(TemplatedResource::template);
	}

	@Override
	public boolean supportsSubscribe() {
		return this.supportsSubscribe;
	}

	@Override
	public boolean supportsCompletion() {
		return this.supportsCompletion;
	}

	private ResourcesPage page(String cursor) {
		KeyCursorPagination.Page<Resource> page = KeyCursorPagination.page(this.resources, StaticResource::definition,
				cursor, this.pageSize);
		return new ResourcesPage(page.items(), page.nextCursor(), null, null);
	}

	private ResourceTemplatesPage templatesPage(String cursor) {
		KeyCursorPagination.Page<ResourceTemplate> page = KeyCursorPagination.page(this.templates,
				template -> template.template().definition(), cursor, this.pageSize);
		return new ResourceTemplatesPage(page.items(), page.nextCursor(), null, null);
	}

	/**
	 * The reader of the resource registered under exactly {@code uri}, or else of the
	 * first template matching it, or {@code null} if there is none.
	 */
	private McpAsyncResource reader(String uri) {
		StaticResource resource = this.resources.get(uri);
		if (resource != null) {
			return resource.reader();
		}
		for (TemplatedResource template : this.templates.values()) {
			if (template.matcher().matches(uri)) {
				return template.reader();
			}
		}
		return null;
	}

	private void putResource(Resource resource, McpAsyncResource reader) {
		Assert.notNull(resource, "resource must not be null");
		Assert.notNull(reader, "reader must not be null");
		String uri = resource.uri();
		if (this.resources.putIfAbsent(uri, new StaticResource(resource, reader)) != null) {
			throw new IllegalArgumentException("Resource with URI '" + uri + "' already exists");
		}
	}

	private void putTemplate(McpAsyncResourceTemplate template, McpAsyncResource reader) {
		Assert.notNull(template, "template must not be null");
		Assert.notNull(reader, "reader must not be null");
		String uriTemplate = template.definition().uriTemplate();
		// The matcher is created once per template rather than on every resources/read.
		TemplatedResource compiled = new TemplatedResource(template, reader,
				this.uriTemplateManagerFactory.create(uriTemplate));
		if (this.templates.putIfAbsent(uriTemplate, compiled) != null) {
			throw new IllegalArgumentException("Resource template with URI '" + uriTemplate + "' already exists");
		}
	}

	private boolean removed(boolean removed) {
		if (removed) {
			broadcastListChanged();
		}
		return removed;
	}

	private void broadcastListChanged() {
		if (this.changeBroadcaster != null) {
			this.changeBroadcaster.broadcast(new ServerChange.ResourcesListChanged());
		}
	}

	private record StaticResource(Resource definition, McpAsyncResource reader) {
	}

	private record TemplatedResource(McpAsyncResourceTemplate template, McpAsyncResource reader,
			McpUriTemplateManager matcher) {
	}

	private record PendingTemplate(McpAsyncResourceTemplate template, McpAsyncResource reader) {
	}

	/**
	 * Builder for {@link InMemoryAsyncResourceRepository}.
	 */
	public static final class Builder {

		private final List<StaticResource> resources = new ArrayList<>();

		private final List<PendingTemplate> templates = new ArrayList<>();

		private McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

		private McpChangeBroadcaster changeBroadcaster;

		private Integer pageSize;

		private boolean supportsSubscribe;

		private boolean supportsCompletion;

		private Builder() {
		}

		/** Adds a resource read by {@code reader}. */
		public Builder resource(Resource resource, McpAsyncResource reader) {
			Assert.notNull(resource, "resource must not be null");
			Assert.notNull(reader, "reader must not be null");
			this.resources.add(new StaticResource(resource, reader));
			return this;
		}

		/** Adds a resource template whose matching URIs are read by {@code reader}. */
		public Builder template(McpAsyncResourceTemplate template, McpAsyncResource reader) {
			Assert.notNull(template, "template must not be null");
			Assert.notNull(reader, "reader must not be null");
			this.templates.add(new PendingTemplate(template, reader));
			return this;
		}

		/**
		 * Compiles the URI templates that {@code resources/read} URIs are matched
		 * against. Defaults to {@link DefaultMcpUriTemplateManagerFactory}.
		 */
		public Builder uriTemplateManagerFactory(McpUriTemplateManagerFactory uriTemplateManagerFactory) {
			Assert.notNull(uriTemplateManagerFactory, "uriTemplateManagerFactory must not be null");
			this.uriTemplateManagerFactory = uriTemplateManagerFactory;
			return this;
		}

		/**
		 * Receives a {@link ServerChange.ResourcesListChanged} for every add and remove,
		 * and the {@link ServerChange.ResourceUpdated} of
		 * {@link InMemoryAsyncResourceRepository#resourceUpdated(String)}, typically an
		 * {@link McpChangeFeed} also registered with
		 * {@code McpServer.Builder#subscriptions}. Unset, changes are not announced.
		 */
		public Builder changeBroadcaster(McpChangeBroadcaster changeBroadcaster) {
			this.changeBroadcaster = changeBroadcaster;
			return this;
		}

		/**
		 * Maximum number of entries per {@code resources/list} and
		 * {@code resources/templates/list} page. Unset, everything is returned on one
		 * page.
		 */
		public Builder pageSize(Integer pageSize) {
			Assert.isTrue(pageSize == null || pageSize > 0, "pageSize must be positive");
			this.pageSize = pageSize;
			return this;
		}

		/**
		 * Whether {@code resources.subscribe} is advertised, so that
		 * {@link InMemoryAsyncResourceRepository#resourceUpdated(String)} reaches
		 * subscribers. Defaults to {@code false}.
		 */
		public Builder supportsSubscribe(boolean supportsSubscribe) {
			this.supportsSubscribe = supportsSubscribe;
			return this;
		}

		/**
		 * Whether {@code completion/complete} is served for {@code ref/resource}
		 * references and {@code completions} is advertised. The server reads this once,
		 * when it is built, so it is a fixed setting rather than derived from the
		 * templates present: templates with a completion handler may be added later. A
		 * template without a completion handler completes to no values. Defaults to
		 * {@code false}.
		 */
		public Builder supportsCompletion(boolean supportsCompletion) {
			this.supportsCompletion = supportsCompletion;
			return this;
		}

		public InMemoryAsyncResourceRepository build() {
			return new InMemoryAsyncResourceRepository(this);
		}

	}

}
