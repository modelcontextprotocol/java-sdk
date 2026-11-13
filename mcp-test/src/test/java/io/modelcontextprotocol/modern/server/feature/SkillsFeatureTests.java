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
import io.modelcontextprotocol.modern.McpSchema.Resource;
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
	void getReturningAnotherSkillIsAnInternalError() {
		McpServer server = server(new McpSyncSkillRepository() {
			@Override
			public SkillsPage list(McpRequestContext ctx, String cursor) {
				return SkillsPage.of(List.of());
			}

			@Override
			public Skill find(McpRequestContext ctx, String uri) {
				return GIT;
			}
		});

		assertThat(call(server, SkillsSchema.METHOD_SKILLS_GET, Map.of("uri", UNLISTED_URI)).error().code())
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

	private static final String ROOT = "skill://git-workflow";

	private static Resource file(String uri) {
		return Resource.builder(uri, uri.substring(uri.lastIndexOf('/') + 1)).mimeType("text/markdown").build();
	}

	private static McpServer directoryServer(List<Resource> children) {
		return server(new McpSyncSkillRepository() {
			@Override
			public SkillsPage list(McpRequestContext ctx, String cursor) {
				return SkillsPage.of(List.of());
			}

			@Override
			public Skill find(McpRequestContext ctx, String uri) {
				return null;
			}

			@Override
			public boolean supportsDirectoryRead() {
				return true;
			}

			@Override
			public ResourcesPage readDirectory(McpRequestContext ctx, String uri, String cursor) {
				return ROOT.equals(uri) ? ResourcesPage.of(children) : null;
			}
		});
	}

	@Test
	void directoryReadIsNeitherAdvertisedNorServedWithoutSupport() {
		Map<String, Object> result = (Map<String, Object>) call(SERVER, McpSchema.METHOD_SERVER_DISCOVER, Map.of())
			.result();
		Map<String, Object> extensions = (Map<String, Object>) ((Map<String, Object>) result.get("capabilities"))
			.get("extensions");

		assertThat((Map<String, Object>) extensions.get(SkillsSchema.EXTENSION_ID)).doesNotContainKey("directoryRead");
		assertThat(call(SERVER, SkillsSchema.METHOD_RESOURCES_DIRECTORY_READ, Map.of("uri", ROOT)).error().code())
			.isEqualTo(ErrorCodes.METHOD_NOT_FOUND);
	}

	@Test
	void directoryReadIsAdvertisedAndReturnsChildren() {
		McpServer server = directoryServer(List.of(file(ROOT + "/SKILL.md"),
				Resource.builder(ROOT + "/references", "references")
					.mimeType(SkillsSchema.DIRECTORY_MIME_TYPE)
					.build()));

		Map<String, Object> discover = (Map<String, Object>) call(server, McpSchema.METHOD_SERVER_DISCOVER, Map.of())
			.result();
		Map<String, Object> extensions = (Map<String, Object>) ((Map<String, Object>) discover.get("capabilities"))
			.get("extensions");
		assertThat(extensions).containsEntry(SkillsSchema.EXTENSION_ID, Map.of("directoryRead", true));

		Map<String, Object> result = (Map<String, Object>) call(server, SkillsSchema.METHOD_RESOURCES_DIRECTORY_READ,
				Map.of("uri", ROOT))
			.result();
		assertThat(result).containsEntry("resultType", "complete").containsEntry("cacheScope", "public");
		assertThat((List<Map<String, Object>>) result.get("resources")).extracting(r -> r.get("mimeType"))
			.containsExactly("text/markdown", SkillsSchema.DIRECTORY_MIME_TYPE);
	}

	@Test
	void directoryReadOfUnknownOrTrailingSlashUriIsInvalidParams() {
		McpServer server = directoryServer(List.of());

		assertThat(call(server, SkillsSchema.METHOD_RESOURCES_DIRECTORY_READ, Map.of("uri", "skill://other")).error()
			.code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(call(server, SkillsSchema.METHOD_RESOURCES_DIRECTORY_READ, Map.of("uri", ROOT + "/")).error().code())
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void directoryReadListingAGrandchildIsAnInternalError() {
		McpServer server = directoryServer(List.of(file(ROOT + "/references/GUIDE.md")));

		assertThat(call(server, SkillsSchema.METHOD_RESOURCES_DIRECTORY_READ, Map.of("uri", ROOT)).error().code())
			.isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

}
