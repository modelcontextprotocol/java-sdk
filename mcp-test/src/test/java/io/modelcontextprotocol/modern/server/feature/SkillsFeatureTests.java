/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.SkillsSchema;
import io.modelcontextprotocol.modern.SkillsSchema.Skill;
import io.modelcontextprotocol.modern.SkillsSchema.SkillResource;
import io.modelcontextprotocol.modern.SkillsSchema.SkillResources;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Lives in mcp-test because the "dynamic"-or-array resources union needs a Jackson mapper.
@SuppressWarnings("unchecked")
class SkillsFeatureTests {

	private static final String GIT_URI = "skill://git-workflow/SKILL.md";

	private static final Skill GIT = Skill
		.builder(GIT_URI, Map.of("name", "git-workflow", "description", "Follow the team's Git conventions"),
				SkillResources.of(List.of(SkillResource.of(GIT_URI, "# Git workflow"))))
		.build();

	private static final String REPORT_URI = "skill://acme/report/SKILL.md";

	private static final Skill REPORT = Skill
		.builder(REPORT_URI, Map.of("name", "report", "description", "Generate today's report"),
				SkillResources.dynamic())
		.build();

	private static final String UNLISTED_URI = "skill://unlisted/SKILL.md";

	private static final Skill UNLISTED = Skill
		.builder(UNLISTED_URI, Map.of("name", "unlisted", "description", "Reachable by URI only"),
				SkillResources.of(List.of(SkillResource.of(UNLISTED_URI, "# Unlisted"))))
		.build();

	private static McpServer server(McpSyncSkillRepository repository) {
		return McpServer.builder()
			.serverInfo(Implementation.builder("test-server", "1.0.0").build())
			.jsonMapper(McpJsonDefaults.getMapper())
			.feature(SkillsFeature.ofSync(repository, McpJsonDefaults.getMapper(), 30000L, CacheScope.PUBLIC))
			.build();
	}

	private static final McpServer SERVER = server(new McpSyncSkillRepository() {
		@Override
		public SkillsPage list(McpRequestContext ctx, String cursor) {
			return SkillsPage.of(List.of(GIT, REPORT));
		}

		@Override
		public Skill find(McpRequestContext ctx, String uri) {
			return List.of(GIT, REPORT, UNLISTED).stream().filter(s -> s.uri().equals(uri)).findFirst().orElse(null);
		}
	});

	private static JSONRPCResponse call(McpServer server, String method, Map<String, Object> params) {
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		Map<String, Object> withMeta = new HashMap<>(params);
		withMeta.put("_meta", meta);
		McpTransportResponse response = server
			.handle(McpTransportContext.EMPTY, new JSONRPCRequest(method, 1, withMeta))
			.block();
		if (response instanceof McpTransportResponse.Result result) {
			return result.response();
		}
		return ((McpTransportResponse.Error) response).response();
	}

	@Test
	void discoverAdvertisesTheExtension() {
		Map<String, Object> result = (Map<String, Object>) call(SERVER, McpSchema.METHOD_SERVER_DISCOVER, Map.of())
			.result();

		Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
		assertThat((Map<String, Object>) capabilities.get("extensions")).containsEntry(SkillsSchema.EXTENSION_ID,
				Map.of());
	}

	@Test
	void listReturnsEntriesWithCacheDefaults() {
		Map<String, Object> result = (Map<String, Object>) call(SERVER, SkillsSchema.METHOD_SKILLS_LIST, Map.of())
			.result();

		assertThat(result).containsEntry("resultType", "complete").containsEntry("cacheScope", "public");
		assertThat(((Number) result.get("ttlMs")).longValue()).isEqualTo(30000L);
		List<Map<String, Object>> skills = (List<Map<String, Object>>) result.get("skills");
		assertThat(skills).extracting(s -> s.get("uri")).containsExactly(GIT_URI, REPORT_URI);
		List<Map<String, Object>> files = (List<Map<String, Object>>) skills.get(0).get("resources");
		assertThat(files).singleElement().satisfies(file -> {
			assertThat(file).containsEntry("uri", GIT_URI)
				.containsEntry("digest", SkillResource.digest("# Git workflow".getBytes()));
			assertThat(((Number) file.get("size")).longValue()).isEqualTo(14L);
		});
		assertThat(skills.get(1)).containsEntry("resources", "dynamic");
	}

	@Test
	void getReturnsSkillsMissingFromTheListing() {
		Map<String, Object> result = (Map<String, Object>) call(SERVER, SkillsSchema.METHOD_SKILLS_GET,
				Map.of("uri", UNLISTED_URI))
			.result();

		assertThat((Map<String, Object>) result.get("skill")).containsEntry("uri", UNLISTED_URI);
		assertThat(result).containsEntry("resultType", "complete").doesNotContainKey("nextCursor");
	}

	@Test
	void getUnknownSkillIsInvalidParams() {
		JSONRPCResponse response = call(SERVER, SkillsSchema.METHOD_SKILLS_GET,
				Map.of("uri", "skill://missing/SKILL.md"));

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void getNonSkillUriIsInvalidParams() {
		JSONRPCResponse response = call(SERVER, SkillsSchema.METHOD_SKILLS_GET,
				Map.of("uri", "skill://git-workflow/notes.md"));

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void getWithoutUriIsInvalidParams() {
		JSONRPCResponse response = call(SERVER, SkillsSchema.METHOD_SKILLS_GET, Map.of());

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void nonconformingEntryIsAnInternalError() {
		// Bypasses the builder's validation: the frontmatter name does not match the URI.
		Skill broken = new Skill(GIT_URI, Map.of("name", "other", "description", "d"), SkillResources.dynamic(), null);
		McpServer server = server(new McpSyncSkillRepository() {
			@Override
			public SkillsPage list(McpRequestContext ctx, String cursor) {
				return SkillsPage.of(List.of(broken));
			}

			@Override
			public Skill find(McpRequestContext ctx, String uri) {
				return broken;
			}
		});

		assertThat(call(server, SkillsSchema.METHOD_SKILLS_LIST, Map.of()).error().code())
			.isEqualTo(ErrorCodes.INTERNAL_ERROR);
		assertThat(call(server, SkillsSchema.METHOD_SKILLS_GET, Map.of("uri", GIT_URI)).error().code())
			.isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void duplicateListedSkillIsAnInternalError() {
		McpServer server = server(new McpSyncSkillRepository() {
			@Override
			public SkillsPage list(McpRequestContext ctx, String cursor) {
				return SkillsPage.of(List.of(GIT, GIT));
			}

			@Override
			public Skill find(McpRequestContext ctx, String uri) {
				return null;
			}
		});

		assertThat(call(server, SkillsSchema.METHOD_SKILLS_LIST, Map.of()).error().code())
			.isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

}
