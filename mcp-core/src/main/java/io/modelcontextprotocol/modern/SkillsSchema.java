/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CacheableResult;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wire types for the Skills extension ({@value #EXTENSION_ID}, SEP-2640): serving Agent
 * Skills as MCP resources, enumerated by {@code skills/list}, described by
 * {@code skills/get} and, optionally, browsed by {@code resources/directory/read}. Skill
 * files themselves are read with the ordinary {@code resources/read}.
 * <p>
 * Entries validate the SEP-2640 rules when constructed or deserialized, so a
 * nonconforming {@link Skill} cannot exist.
 *
 * @see <a href=
 * "https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2640">SEP-2640</a>
 */
public final class SkillsSchema {

	private static final Logger logger = LoggerFactory.getLogger(SkillsSchema.class);

	public static final String EXTENSION_ID = "io.modelcontextprotocol/skills";

	public static final String METHOD_SKILLS_LIST = "skills/list";

	public static final String METHOD_SKILLS_GET = "skills/get";

	public static final String METHOD_RESOURCES_DIRECTORY_READ = "resources/directory/read";

	/** The {@code mimeType} of a directory resource. */
	public static final String DIRECTORY_MIME_TYPE = "inode/directory";

	/**
	 * Resources per skill, {@code SKILL.md} included, that every host must accept.
	 * Servers should not exceed it, but larger skills are not rejected.
	 */
	public static final int MAX_RESOURCES_PER_SKILL = 512;

	/**
	 * Total bytes per skill, summed over its resources, that every host must accept.
	 * Servers should not exceed it, but larger skills are not rejected.
	 */
	public static final long MAX_TOTAL_SIZE_PER_SKILL = 16L * 1024 * 1024;

	private static final String SKILL_MD = "/SKILL.md";

	private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

	private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

	private SkillsSchema() {
	}

	/**
	 * The skill name a {@code SKILL.md} URI encodes: its final path segment, or the
	 * authority for {@code skill://<name>/SKILL.md}.
	 * @throws IllegalArgumentException if {@code uri} is not an absolute, hierarchical
	 * URI ending in {@code /SKILL.md}
	 */
	public static String skillName(String uri) {
		URI parsed = parse(uri);
		String path = parsed.getRawPath();
		if (path == null || !path.endsWith(SKILL_MD)) {
			throw new IllegalArgumentException("Skill URI must end in /SKILL.md: " + uri);
		}
		String directory = path.substring(0, path.length() - SKILL_MD.length());
		String name = directory.isEmpty() ? parsed.getRawAuthority()
				: directory.substring(directory.lastIndexOf('/') + 1);
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Skill URI has no skill name: " + uri);
		}
		return name;
	}

	private static URI parse(String uri) {
		Assert.notNull(uri, "uri must not be null");
		URI parsed;
		try {
			parsed = new URI(uri);
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException("Invalid URI: " + uri, ex);
		}
		if (!parsed.isAbsolute() || parsed.isOpaque() || parsed.getRawQuery() != null
				|| parsed.getRawFragment() != null) {
			throw new IllegalArgumentException(
					"URI must be absolute and hierarchical, without query or fragment: " + uri);
		}
		return parsed;
	}

	private static void requireWithinSkill(URI skill, URI resource, String resourceUri) {
		if (!skill.getScheme().equals(resource.getScheme())
				|| !Objects.equals(skill.getRawAuthority(), resource.getRawAuthority())) {
			throw new IllegalArgumentException("Resource " + resourceUri + " is outside skill " + skill);
		}
		String skillPath = skill.getRawPath();
		String root = skillPath.substring(0, skillPath.length() - SKILL_MD.length()) + "/";
		String path = resource.getRawPath();
		if (path == null || path.endsWith("/") || !path.startsWith(root)) {
			throw new IllegalArgumentException("Resource " + resourceUri + " is outside skill " + skill);
		}
		for (String segment : path.split("/")) {
			if (segment.equals(".") || segment.equals("..")) {
				throw new IllegalArgumentException("Resource " + resourceUri + " contains a traversal segment");
			}
		}
	}

	/**
	 * Checks a {@code resources/directory/read} URI: absolute, without query, fragment or
	 * trailing slash.
	 * @throws IllegalArgumentException if it is not
	 */
	public static void checkDirectoryUri(String uri) {
		Assert.notNull(uri, "uri must not be null");
		if (uri.endsWith("/")) {
			throw new IllegalArgumentException("Directory URI must not have a trailing slash: " + uri);
		}
		parse(uri);
	}

	/**
	 * Checks that each resource is a direct child of the directory {@code uri}, listed
	 * once. It cannot tell whether the listing is complete.
	 * @throws IllegalArgumentException if one is not
	 */
	public static void checkDirectoryChildren(String uri, List<Resource> children) {
		checkDirectoryUri(uri);
		URI directory = parse(uri);
		String path = directory.getRawPath() == null ? "" : directory.getRawPath();
		String prefix = path.isEmpty() ? "/" : path + "/";
		Set<String> seen = new HashSet<>();
		for (Resource child : children) {
			URI parsed = parse(child.uri());
			String childPath = parsed.getRawPath() == null ? "" : parsed.getRawPath();
			String relative = childPath.startsWith(prefix) ? childPath.substring(prefix.length()) : "";
			if (!directory.getScheme().equals(parsed.getScheme())
					|| !Objects.equals(directory.getRawAuthority(), parsed.getRawAuthority()) || relative.isEmpty()
					|| relative.contains("/") || relative.equals(".") || relative.equals("..")) {
				throw new IllegalArgumentException(child.uri() + " is not a direct child of directory " + uri);
			}
			if (!seen.add(child.uri())) {
				throw new IllegalArgumentException("Directory " + uri + " lists " + child.uri() + " twice");
			}
		}
	}

	/** One file of a skill: its URI, SHA-256 digest and size in bytes. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SkillResource( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("digest") String digest,
		@JsonProperty("size") Long size) { // @formatter:on

		public SkillResource {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(digest, "digest must not be null");
			Assert.notNull(size, "size must not be null");
			Assert.isTrue(DIGEST.matcher(digest).matches(),
					"digest must be sha256: followed by 64 lowercase hex characters: " + digest);
			Assert.isTrue(size >= 0, "size must not be negative");
		}

		/** Computes the digest and size of {@code content}. */
		public static SkillResource of(String uri, byte[] content) {
			Assert.notNull(content, "content must not be null");
			return new SkillResource(uri, digest(content), (long) content.length);
		}

		/** Computes the digest and size of {@code content} encoded as UTF-8. */
		public static SkillResource of(String uri, String content) {
			Assert.notNull(content, "content must not be null");
			return of(uri, content.getBytes(StandardCharsets.UTF_8));
		}

		/** {@code sha256:} followed by the lowercase hex SHA-256 of {@code content}. */
		public static String digest(byte[] content) {
			try {
				return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException("SHA-256 is not available", ex);
			}
		}

	}

	/**
	 * A skill's {@code resources}: the complete list of its files, or the
	 * {@code "dynamic"} marker for content that has no stable digests.
	 */
	public static final class SkillResources {

		private static final String DYNAMIC_MARKER = "dynamic";

		private static final SkillResources DYNAMIC = new SkillResources(null);

		private final List<SkillResource> files;

		private SkillResources(List<SkillResource> files) {
			this.files = files;
		}

		public static SkillResources of(List<SkillResource> files) {
			Assert.notNull(files, "files must not be null");
			return new SkillResources(List.copyOf(files));
		}

		public static SkillResources dynamic() {
			return DYNAMIC;
		}

		public boolean isDynamic() {
			return this.files == null;
		}

		/** The skill's files, or {@code null} if {@link #isDynamic() dynamic}. */
		public List<SkillResource> files() {
			return this.files;
		}

		@JsonValue
		Object toJson() {
			return isDynamic() ? DYNAMIC_MARKER : this.files;
		}

		// Any value other than an array or "dynamic" makes the entry invalid.
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		static SkillResources fromJson(Object value) {
			if (DYNAMIC_MARKER.equals(value)) {
				return DYNAMIC;
			}
			if (!(value instanceof List<?> list)) {
				throw new IllegalArgumentException("resources must be an array or \"dynamic\"");
			}
			List<SkillResource> files = new ArrayList<>(list.size());
			for (Object item : list) {
				if (!(item instanceof Map<?, ?> map) || !(map.get("uri") instanceof String uri)
						|| !(map.get("digest") instanceof String digest) || !(map.get("size") instanceof Number size)) {
					throw new IllegalArgumentException("resources[] must be {uri, digest, size} objects");
				}
				files.add(new SkillResource(uri, digest, size.longValue()));
			}
			return of(files);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof SkillResources that && Objects.equals(this.files, that.files);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(this.files);
		}

		@Override
		public String toString() {
			return String.valueOf(toJson());
		}

	}

	/**
	 * A skill entry of {@code skills/list} and {@code skills/get}. Constructing or
	 * deserializing one enforces the SEP-2640 rules: a valid {@code name} matching the
	 * URI, a {@code description}, and, unless dynamic, a duplicate-free resource list of
	 * files within the skill that includes {@code SKILL.md}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Skill( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("frontmatter") Map<String, Object> frontmatter,
		@JsonProperty("resources") SkillResources resources,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		// Stricter than other records, with no lenient fromJson: hosts must not load an
		// invalid entry.
		public Skill {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(frontmatter, "frontmatter must not be null");
			Assert.notNull(resources, "resources must not be null");
			frontmatter = Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
			validate(uri, frontmatter, resources);
		}

		private static void validate(String uri, Map<String, Object> frontmatter, SkillResources resources) {
			String uriName = skillName(uri);
			if (!(frontmatter.get("name") instanceof String name) || name.length() > 64
					|| !NAME.matcher(name).matches()) {
				throw new IllegalArgumentException(
						"Skill " + uri + ": frontmatter name must be 1-64 lowercase letters, digits or hyphens");
			}
			if (!name.equals(uriName)) {
				throw new IllegalArgumentException(
						"Skill " + uri + ": frontmatter name " + name + " does not match the URI");
			}
			if (!(frontmatter.get("description") instanceof String description) || description.isEmpty()
					|| description.length() > 1024) {
				throw new IllegalArgumentException(
						"Skill " + uri + ": frontmatter description must have 1-1024 characters");
			}
			if (resources.isDynamic()) {
				return;
			}
			URI skill = parse(uri);
			Set<String> seen = new HashSet<>();
			for (SkillResource resource : resources.files()) {
				requireWithinSkill(skill, parse(resource.uri()), resource.uri());
				if (!seen.add(resource.uri())) {
					throw new IllegalArgumentException("Skill " + uri + " lists " + resource.uri() + " twice");
				}
			}
			if (!seen.contains(uri)) {
				throw new IllegalArgumentException("Skill " + uri + ": resources must include its SKILL.md");
			}
		}

		public static Builder builder(String uri, Map<String, Object> frontmatter, SkillResources resources) {
			return new Builder(uri, frontmatter, resources);
		}

		public static final class Builder {

			private final String uri;

			private final Map<String, Object> frontmatter;

			private final SkillResources resources;

			private Map<String, Object> meta;

			private Builder(String uri, Map<String, Object> frontmatter, SkillResources resources) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.notNull(frontmatter, "frontmatter must not be null");
				Assert.notNull(resources, "resources must not be null");
				this.uri = uri;
				this.frontmatter = frontmatter;
				this.resources = resources;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * @throws IllegalArgumentException if the entry breaks a SEP-2640 rule
			 */
			public Skill build() {
				return new Skill(this.uri, this.frontmatter, this.resources, this.meta);
			}

		}
	}

	/** The {@code skills/get} parameters. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetSkillRequest( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public GetSkillRequest {
			Assert.notNull(uri, "uri must not be null");
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListSkillsResult( // @formatter:off
		@JsonProperty("skills") List<Skill> skills,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListSkillsResult {
			Assert.notNull(skills, "skills must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
			Set<String> uris = new HashSet<>();
			for (Skill skill : skills) {
				Assert.isTrue(uris.add(skill.uri()), "skills/list lists skill " + skill.uri() + " twice");
			}
		}

		@JsonCreator
		static ListSkillsResult fromJson(@JsonProperty("skills") List<Skill> skills,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (skills == null || ttlMs == null || cacheScope == null) {
				logger.warn("ListSkillsResult: missing required fields during deserialization; substituting defaults");
			}
			skills = skills == null ? List.of() : skills;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListSkillsResult(skills, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public static Builder builder(List<Skill> skills) {
			return new Builder(skills);
		}

		public static final class Builder {

			private final List<Skill> skills;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<Skill> skills) {
				Assert.notNull(skills, "skills must not be null");
				this.skills = skills;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				Assert.notNull(cacheScope, "cacheScope must not be null");
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListSkillsResult build() {
				return new ListSkillsResult(skills, nextCursor, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	/**
	 * The {@code skills/get} result. SEP-2640 leaves caching attributes here open; like
	 * {@code resources/read} (and the Python SDK) it carries them.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetSkillResult( // @formatter:off
		@JsonProperty("skill") Skill skill,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public GetSkillResult {
			Assert.notNull(skill, "skill must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		// A missing skill is unprocessable, so only the caching attributes get defaults.
		@JsonCreator
		static GetSkillResult fromJson(@JsonProperty("skill") Skill skill, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (ttlMs == null || cacheScope == null) {
				logger.warn("GetSkillResult: missing required fields during deserialization; substituting defaults");
			}
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new GetSkillResult(skill, ttlMs, cacheScope, resultType, meta);
		}

		public static Builder builder(Skill skill) {
			return new Builder(skill);
		}

		public static final class Builder {

			private final Skill skill;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(Skill skill) {
				Assert.notNull(skill, "skill must not be null");
				this.skill = skill;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				Assert.notNull(cacheScope, "cacheScope must not be null");
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetSkillResult build() {
				return new GetSkillResult(skill, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	/** The {@code resources/directory/read} parameters. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadDirectoryRequest( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("cursor") String cursor,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public ReadDirectoryRequest {
			Assert.notNull(uri, "uri must not be null");
		}

	}

	/**
	 * The {@code resources/directory/read} result: the directory's direct children, with
	 * subdirectories carrying the {@value #DIRECTORY_MIME_TYPE} {@code mimeType}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadDirectoryResult( // @formatter:off
		@JsonProperty("resources") List<Resource> resources,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ReadDirectoryResult {
			Assert.notNull(resources, "resources must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static ReadDirectoryResult fromJson(@JsonProperty("resources") List<Resource> resources,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (resources == null || ttlMs == null || cacheScope == null) {
				logger
					.warn("ReadDirectoryResult: missing required fields during deserialization; substituting defaults");
			}
			resources = resources == null ? List.of() : resources;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ReadDirectoryResult(resources, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public static Builder builder(List<Resource> resources) {
			return new Builder(resources);
		}

		public static final class Builder {

			private final List<Resource> resources;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<Resource> resources) {
				Assert.notNull(resources, "resources must not be null");
				this.resources = resources;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				Assert.notNull(cacheScope, "cacheScope must not be null");
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadDirectoryResult build() {
				return new ReadDirectoryResult(resources, nextCursor, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

}
