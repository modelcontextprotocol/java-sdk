/*
 * Copyright 2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpStatelessSkillTests {

	private static final String SKILL_URI = "skill://data-analysis/SKILL.md";

	private static final String DIGEST = "sha256:57985f31c60e16fa467845108d7c9ed98b744cae72bbe87f69aa01775b6f0a13";

	@Test
	void rejectsMalformedManifestDigest() {
		StepVerifier
			.create(server().addSkill(skill(List.of(new McpSchema.SkillResource(SKILL_URI, "sha256:invalid", 1L)))))
			.expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("digest"))
			.verify();
	}

	@Test
	void rejectsManifestWithoutSkillFile() {
		StepVerifier
			.create(server().addSkill(
					skill(List.of(new McpSchema.SkillResource("skill://data-analysis/reference.md", DIGEST, 1L)))))
			.expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("SKILL.md"))
			.verify();
	}

	@Test
	void acceptsDynamicSkill() {
		McpSchema.Skill skill = new McpSchema.Skill(SKILL_URI,
				McpSchema.SkillFrontmatter.of(Map.of("name", "data-analysis", "description", "Analyze tabular data.")),
				McpSchema.SkillResources.dynamicResources());

		StepVerifier.create(server().addSkill(skill)).verifyComplete();
	}

	@Test
	void acceptsSkillWithNestedRoot() {
		String skillUri = "skill://acme/billing/refunds/SKILL.md";
		McpSchema.Skill skill = skill(skillUri, "refunds", List.of(new McpSchema.SkillResource(skillUri, DIGEST, 1L),
				new McpSchema.SkillResource("skill://acme/billing/refunds/references/guide.md", DIGEST, 1L)));

		StepVerifier.create(server().addSkill(skill)).verifyComplete();
	}

	@Test
	void registersNestedSkillIndependently() {
		TestTransport transport = new TestTransport();
		McpStatelessAsyncServer server = server(transport, false);
		String parentUri = "skill://acme/billing/refunds/SKILL.md";
		String nestedUri = "skill://acme/billing/refunds/regional/SKILL.md";
		McpSchema.Skill parentSkill = skill(parentUri, "refunds",
				List.of(new McpSchema.SkillResource(parentUri, DIGEST, 1L),
						new McpSchema.SkillResource(nestedUri, DIGEST, 1L)));
		McpSchema.Skill nestedSkill = skill(nestedUri, "regional",
				List.of(new McpSchema.SkillResource(nestedUri, DIGEST, 1L)));

		StepVerifier.create(server.addSkill(parentSkill)).verifyComplete();

		McpSchema.JSONRPCResponse unregisteredResponse = request(transport, McpSchema.METHOD_SKILLS_GET,
				Map.of("uri", nestedUri));
		assertThat(unregisteredResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);

		StepVerifier.create(server.addSkill(nestedSkill)).verifyComplete();

		McpSchema.JSONRPCResponse listResponse = request(transport, McpSchema.METHOD_SKILLS_LIST, Map.of());
		assertThat(((McpSchema.ListSkillsResult) listResponse.result()).skills()).containsExactlyInAnyOrder(parentSkill,
				nestedSkill);

		McpSchema.JSONRPCResponse nestedResponse = request(transport, McpSchema.METHOD_SKILLS_GET,
				Map.of("uri", nestedUri));
		assertThat(nestedResponse.error()).isNull();
		assertThat(((McpSchema.GetSkillResult) nestedResponse.result()).skill()).isEqualTo(nestedSkill);
	}

	@Test
	void exposesRegisteredSkillsThroughSkillsEndpoints() {
		TestTransport transport = new TestTransport();
		McpStatelessAsyncServer server = server(transport, false);
		McpSchema.Skill skill = skill(List.of(new McpSchema.SkillResource(SKILL_URI, DIGEST, 1L)));

		StepVerifier.create(server.addSkill(skill)).verifyComplete();

		McpSchema.JSONRPCResponse listResponse = request(transport, McpSchema.METHOD_SKILLS_LIST, Map.of());
		assertThat(listResponse.error()).isNull();
		assertThat(((McpSchema.ListSkillsResult) listResponse.result()).skills()).containsExactly(skill);

		McpSchema.JSONRPCResponse getResponse = request(transport, McpSchema.METHOD_SKILLS_GET,
				Map.of("uri", SKILL_URI));
		assertThat(getResponse.error()).isNull();
		assertThat(((McpSchema.GetSkillResult) getResponse.result()).skill()).isEqualTo(skill);

		McpSchema.JSONRPCResponse unknownResponse = request(transport, McpSchema.METHOD_SKILLS_GET,
				Map.of("uri", "skill://unknown/SKILL.md"));
		assertThat(unknownResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat(unknownResponse.error().message()).isEqualTo("Unknown skill URI");
	}

	@Test
	void registersSkillsConfiguredAtBuildTime() {
		TestTransport transport = new TestTransport();
		McpSchema.Skill skill = skill(List.of(new McpSchema.SkillResource(SKILL_URI, DIGEST, 1L)));
		McpServer.async(transport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				.resources(false, false)
				.extensions(Map.of("io.modelcontextprotocol/skills", Map.of()))
				.build())
			.skills(skill)
			.build();

		McpSchema.JSONRPCResponse response = request(transport, McpSchema.METHOD_SKILLS_LIST, Map.of());
		assertThat(response.error()).isNull();
		assertThat(((McpSchema.ListSkillsResult) response.result()).skills()).containsExactly(skill);
	}

	@Test
	void exposesSkillResourceDirectoriesWhenDirectoryReadIsEnabled() {
		TestTransport transport = new TestTransport();
		McpStatelessAsyncServer server = server(transport, true);
		McpSchema.Skill skill = skill(List.of(new McpSchema.SkillResource(SKILL_URI, DIGEST, 1L),
				new McpSchema.SkillResource("skill://data-analysis/references/guide.md", DIGEST, 1L)));
		McpSchema.Resource skillFile = McpSchema.Resource.builder(SKILL_URI, "SKILL.md").build();
		McpSchema.Resource guide = McpSchema.Resource.builder("skill://data-analysis/references/guide.md", "guide.md")
			.build();

		StepVerifier.create(server.addSkill(skill)).verifyComplete();
		StepVerifier.create(server.addResource(new McpStatelessServerFeatures.AsyncResourceSpecification(skillFile,
				(context, request) -> Mono.just(new McpSchema.ReadResourceResult(List.of())))))
			.verifyComplete();
		StepVerifier.create(server.addResource(new McpStatelessServerFeatures.AsyncResourceSpecification(guide,
				(context, request) -> Mono.just(new McpSchema.ReadResourceResult(List.of())))))
			.verifyComplete();

		McpSchema.JSONRPCResponse rootResponse = request(transport, McpSchema.METHOD_RESOURCES_DIRECTORY_READ,
				Map.of("uri", "skill://data-analysis"));
		assertThat(rootResponse.error()).isNull();
		assertThat(((McpSchema.ListResourcesResult) rootResponse.result()).resources())
			.extracting(McpSchema.Resource::uri)
			.containsExactlyInAnyOrder(SKILL_URI, "skill://data-analysis/references");

		McpSchema.JSONRPCResponse invalidResponse = request(transport, McpSchema.METHOD_RESOURCES_DIRECTORY_READ,
				Map.of("uri", "skill://unknown"));
		assertThat(invalidResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat(invalidResponse.error().message()).isEqualTo("URI does not identify a directory resource");
	}

	@Test
	void delegatesDirectoryReadsToConfiguredHandler() {
		TestTransport transport = new TestTransport();
		McpStatelessAsyncServer server = McpServer.async(transport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				.resources(false, false)
				.extensions(Map.of("io.modelcontextprotocol/skills", Map.of("directoryRead", true)))
				.build())
			.directoryReadHandler((context, request) -> {
				assertThat(request.uri()).isEqualTo("skill://data-analysis/generated");
				assertThat(request.cursor()).isEqualTo("page-1");
				return Mono.just(McpSchema.ListResourcesResult.builder(
						List.of(McpSchema.Resource.builder("skill://data-analysis/generated/report.md", "report.md")
							.mimeType("text/markdown")
							.build()))
					.nextCursor("page-2")
					.build());
			})
			.build();

		McpSchema.JSONRPCResponse response = request(transport, McpSchema.METHOD_RESOURCES_DIRECTORY_READ,
				Map.of("uri", "skill://data-analysis/generated", "cursor", "page-1"));

		assertThat(response.error()).isNull();
		McpSchema.ListResourcesResult result = (McpSchema.ListResourcesResult) response.result();
		assertThat(result.nextCursor()).isEqualTo("page-2");
		assertThat(result.resources()).extracting(McpSchema.Resource::uri)
			.containsExactly("skill://data-analysis/generated/report.md");
	}

	@Test
	void requiresResourceCapabilitiesWhenSkillsAreEnabled() {
		assertThatThrownBy(() -> McpServer.async(new TestTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				.extensions(Map.of("io.modelcontextprotocol/skills", Map.of()))
				.build())
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Skills extension requires resource capabilities");
	}

	private static McpStatelessAsyncServer server() {
		return server(new TestTransport(), false);
	}

	private static McpStatelessAsyncServer server(TestTransport transport, boolean directoryRead) {
		return McpServer.async(transport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				.resources(false, false)
				.extensions(Map.of("io.modelcontextprotocol/skills", Map.of("directoryRead", directoryRead)))
				.build())
			.build();
	}

	private static McpSchema.JSONRPCResponse request(TestTransport transport, String method,
			Map<String, Object> params) {
		return transport.mcpHandler
			.handleRequest(McpTransportContext.EMPTY,
					new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, "request-id", params))
			.block();
	}

	private static McpSchema.Skill skill(List<McpSchema.SkillResource> resources) {
		return skill(SKILL_URI, "data-analysis", resources);
	}

	private static McpSchema.Skill skill(String uri, String name, List<McpSchema.SkillResource> resources) {
		return new McpSchema.Skill(uri,
				McpSchema.SkillFrontmatter.of(Map.of("name", name, "description", "Analyze tabular data.")),
				McpSchema.SkillResources.manifest(resources));
	}

	private static final class TestTransport implements McpStatelessServerTransport {

		private McpStatelessServerHandler mcpHandler;

		@Override
		public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
			this.mcpHandler = mcpHandler;
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

}
