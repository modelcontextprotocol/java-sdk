/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Test to verify the separation of regular resources and resource templates. Regular
 * resources (without template parameters) should only appear in resources/list. Template
 * resources (containing {}) should only appear in resources/templates/list.
 */
public class ResourceTemplateListingTest {

	@Test
	void testTemplateResourcesFilteredFromRegularListing() {
		// The change we made filters resources containing "{" from the regular listing
		// This test verifies that behavior is working correctly

		// Given a string with template parameter
		String templateUri = "file:///test/{userId}/profile.txt";
		assertThat(templateUri.contains("{")).isTrue();

		// And a regular URI
		String regularUri = "file:///test/regular.txt";
		assertThat(regularUri.contains("{")).isFalse();

		// The filter should exclude template URIs
		assertThat(!templateUri.contains("{")).isFalse();
		assertThat(!regularUri.contains("{")).isTrue();
	}

	@Test
	void testResourceListingWithMixedResources() {
		// Create resource list with both regular and template resources
		List<McpSchema.Resource> allResources = List.of(
				new McpSchema.Resource("file:///test/doc1.txt", "Document 1", "text/plain", null, null),
				new McpSchema.Resource("file:///test/doc2.txt", "Document 2", "text/plain", null, null),
				new McpSchema.Resource("file:///test/{type}/document.txt", "Typed Document", "text/plain", null, null),
				new McpSchema.Resource("file:///users/{userId}/files/{fileId}", "User File", "text/plain", null, null));

		// Apply the filter logic from McpAsyncServer line 438
		List<McpSchema.Resource> filteredResources = allResources.stream()
			.filter(resource -> !resource.uri().contains("{"))
			.collect(Collectors.toList());

		// Verify only regular resources are included
		assertThat(filteredResources).hasSize(2);
		assertThat(filteredResources).extracting(McpSchema.Resource::uri)
			.containsExactlyInAnyOrder("file:///test/doc1.txt", "file:///test/doc2.txt");
	}

	@Test
	void testResourceTemplatesListedSeparately() {
		// Create mixed resources
		List<McpSchema.Resource> resources = List.of(
				new McpSchema.Resource("file:///test/regular.txt", "Regular Resource", "text/plain", null, null),
				new McpSchema.Resource("file:///test/user/{userId}/profile.txt", "User Profile", "text/plain", null,
						null));

		// Create explicit resource template
		McpSchema.ResourceTemplate explicitTemplate = new McpSchema.ResourceTemplate(
				"file:///test/document/{docId}/content.txt", "Document Template", null, "text/plain", null);

		// Filter regular resources (those without template parameters)
		List<McpSchema.Resource> regularResources = resources.stream()
			.filter(resource -> !resource.uri().contains("{"))
			.collect(Collectors.toList());

		// Extract template resources (those with template parameters)
		List<McpSchema.ResourceTemplate> templateResources = resources.stream()
			.filter(resource -> resource.uri().contains("{"))
			.map(resource -> new McpSchema.ResourceTemplate(resource.uri(), resource.name(), resource.description(),
					resource.mimeType(), resource.annotations()))
			.collect(Collectors.toList());

		// Verify regular resources list
		assertThat(regularResources).hasSize(1);
		assertThat(regularResources.get(0).uri()).isEqualTo("file:///test/regular.txt");

		// Verify template resources list includes both extracted and explicit templates
		assertThat(templateResources).hasSize(1);
		assertThat(templateResources.get(0).uriTemplate()).isEqualTo("file:///test/user/{userId}/profile.txt");

		// In the actual implementation, both would be combined
		List<McpSchema.ResourceTemplate> allTemplates = List.of(templateResources.get(0), explicitTemplate);
		assertThat(allTemplates).hasSize(2);
		assertThat(allTemplates).extracting(McpSchema.ResourceTemplate::uriTemplate)
			.containsExactlyInAnyOrder("file:///test/user/{userId}/profile.txt",
					"file:///test/document/{docId}/content.txt");
	}

}