/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.SkillsSchema.GetSkillResult;
import io.modelcontextprotocol.modern.SkillsSchema.ListSkillsResult;
import io.modelcontextprotocol.modern.SkillsSchema.Skill;
import io.modelcontextprotocol.modern.SkillsSchema.SkillResource;
import io.modelcontextprotocol.modern.SkillsSchema.SkillResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SkillsSchemaTests {

	private static final String EMPTY_DIGEST = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private static final String URI = "skill://acme/billing/refunds/SKILL.md";

	private static final Map<String, Object> FRONTMATTER = Map.of("name", "refunds", "description", "Process refunds",
			"metadata", Map.of("version", "2.1.0"));

	private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

	private static SkillResource file(String uri) {
		return new SkillResource(uri, EMPTY_DIGEST, 0L);
	}

	private static Skill skill(String uri, Map<String, Object> frontmatter, SkillResource... files) {
		return Skill.builder(uri, frontmatter, SkillResources.of(List.of(files))).build();
	}

	@Test
	void resourceDigestIsLowercaseHexSha256() {
		SkillResource resource = SkillResource.of(URI, new byte[0]);

		assertThat(resource.digest()).isEqualTo(EMPTY_DIGEST);
		assertThat(resource.size()).isZero();
	}

	@Test
	void skillRoundTripsWithAResourceArray() throws Exception {
		Skill skill = skill(URI, FRONTMATTER, file(URI), file("skill://acme/billing/refunds/examples/email.md"));
		ListSkillsResult result = ListSkillsResult.builder(List.of(skill)).build();

		String json = this.jsonMapper.writeValueAsString(result);

		assertThat(json)
			.contains("\"resources\":[{\"uri\":\"" + URI + "\",\"digest\":\"" + EMPTY_DIGEST + "\",\"size\":0}");
		assertThat(this.jsonMapper.readValue(json, ListSkillsResult.class)).isEqualTo(result);
	}

	@Test
	void skillRoundTripsAsDynamic() throws Exception {
		Skill skill = Skill.builder(URI, FRONTMATTER, SkillResources.dynamic()).build();
		GetSkillResult result = GetSkillResult.builder(skill).build();

		String json = this.jsonMapper.writeValueAsString(result);

		assertThat(json).contains("\"resources\":\"dynamic\"");
		assertThat(this.jsonMapper.readValue(json, GetSkillResult.class).skill().resources().isDynamic()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", ",\"resources\":\"static\"", ",\"resources\":{}", ",\"resources\":null" })
	void entryWithoutValidResourcesIsRejected(String resources) {
		String json = "{\"uri\":\"" + URI + "\",\"frontmatter\":{\"name\":\"refunds\",\"description\":\"d\"}"
				+ resources + "}";

		assertThatException().isThrownBy(() -> this.jsonMapper.readValue(json, Skill.class));
	}

	@Test
	void missingCacheHintsDeserializeToDefaults() throws Exception {
		ListSkillsResult result = this.jsonMapper.readValue("{\"skills\":[],\"somethingNew\":1}",
				ListSkillsResult.class);

		assertThat(result.ttlMs()).isZero();
		assertThat(result.cacheScope()).isEqualTo(McpSchema.CacheScope.PRIVATE);
	}

	@Test
	void skillNameIsTheAuthorityForATopLevelSkill() {
		assertThat(SkillsSchema.skillName("skill://git-workflow/SKILL.md")).isEqualTo("git-workflow");
		assertThat(SkillsSchema.skillName(URI)).isEqualTo("refunds");
	}

	@ParameterizedTest
	@ValueSource(strings = { "skill://refunds/README.md", "skill:///SKILL.md", "skill://refunds/SKILL.md?x=1",
			"refunds/SKILL.md", "skill:refunds/SKILL.md" })
	void skillNameRejectsNonSkillUris(String uri) {
		assertThatIllegalArgumentException().isThrownBy(() -> SkillsSchema.skillName(uri));
	}

	@Test
	void nameMustMatchTheUri() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> skill(URI, Map.of("name", "billing", "description", "d"), file(URI)))
			.withMessageContaining("does not match");
	}

	@ParameterizedTest
	@ValueSource(strings = { "Refunds", "-refunds", "re--funds", "re_funds" })
	void nameMustFollowTheAgentSkillsFormat(String name) {
		String uri = "skill://" + name.toLowerCase() + "/SKILL.md";
		assertThatIllegalArgumentException()
			.isThrownBy(() -> skill(uri, Map.of("name", name, "description", "d"), file(uri)));
	}

	@Test
	void descriptionIsRequired() {
		assertThatIllegalArgumentException().isThrownBy(() -> skill(URI, Map.of("name", "refunds"), file(URI)));
	}

	@Test
	void resourcesMustIncludeSkillMd() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> skill(URI, FRONTMATTER, file("skill://acme/billing/refunds/a.md")))
			.withMessageContaining("SKILL.md");
	}

	@ParameterizedTest
	@ValueSource(strings = { "skill://acme/billing/other/a.md", "skill://acme/billing/refunds-x/a.md",
			"skill://other/billing/refunds/a.md", "file://acme/billing/refunds/a.md",
			"skill://acme/billing/refunds/../other/a.md", "skill://acme/billing/refunds/dir/" })
	void resourcesMustBeFilesWithinTheSkill(String uri) {
		assertThatIllegalArgumentException().isThrownBy(() -> skill(URI, FRONTMATTER, file(URI), file(uri)));
	}

	@Test
	void resourcesMustNotRepeat() {
		assertThatIllegalArgumentException().isThrownBy(() -> skill(URI, FRONTMATTER, file(URI), file(URI)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "sha256:ABC", "sha1:" + "0000000000000000000000000000000000000000",
			"sha256:E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855" })
	void digestMustBeLowercaseSha256(String digest) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> skill(URI, FRONTMATTER, new SkillResource(URI, digest, 0L)));
	}

	@Test
	void sizeMustNotBeNegative() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> skill(URI, FRONTMATTER, new SkillResource(URI, EMPTY_DIGEST, -1L)));
	}

	@Test
	void dynamicSkillSkipsResourceChecks() {
		assertThatNoException().isThrownBy(() -> Skill.builder(URI, FRONTMATTER, SkillResources.dynamic()).build());
	}

}
