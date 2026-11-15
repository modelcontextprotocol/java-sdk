/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.modelcontextprotocol.modern.McpSchema.Tool;

/**
 * Reads the {@code x-mcp-header} annotations of a tool's {@code inputSchema}, which ask
 * clients to mirror an argument into an {@code Mcp-Param-{Name}} header.
 *
 * @author Daniel Garnier-Moiroux
 * @see <a href="https://modelcontextprotocol.io/seps/2243-http-standardization">SEP-2243:
 * HTTP Standardization</a>
 */
public final class ToolParamHeaderAnnotations {

	/**
	 * The JSON Schema keyword that annotates an argument with its header name.
	 */
	public static final String KEYWORD = "x-mcp-header";

	// RFC 9110 token: 1*tchar
	private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

	// JSON Schema keywords whose value is a schema or an array of schemas
	// (draft-07 and 2020-12), other than properties.
	private static final Set<String> SUBSCHEMA_KEYWORDS = Set.of("items", "prefixItems", "additionalItems", "contains",
			"unevaluatedItems", "additionalProperties", "unevaluatedProperties", "propertyNames", "not", "if", "then",
			"else", "allOf", "anyOf", "oneOf");

	// Keywords whose value maps names to schemas.
	private static final Set<String> SCHEMA_MAP_KEYWORDS = Set.of("patternProperties", "dependentSchemas",
			"dependencies", "$defs", "definitions");

	private static final String UNREACHABLE = "not statically reachable from the schema root through properties";

	private final List<Annotation> valid;

	private final List<InvalidAnnotation> invalid;

	private ToolParamHeaderAnnotations(List<Annotation> valid, List<InvalidAnnotation> invalid) {
		this.valid = List.copyOf(valid);
		this.invalid = List.copyOf(invalid);
	}

	/**
	 * An argument mirrored into a header.
	 *
	 * @param name the {@code {Name}} of {@code Mcp-Param-{Name}}, as declared
	 * @param path the {@code properties} keys leading from the schema root to the
	 * argument
	 * @param type the declared type of the argument
	 */
	public record Annotation(String name, List<String> path, Type type) {

		public Annotation {
			path = List.copyOf(path);
		}

		/**
		 * The argument's path joined with dots, for messages.
		 * @return the dotted path, such as {@code location.region}
		 */
		public String argument() {
			return String.join(".", this.path);
		}

	}

	/**
	 * An annotation that can't be enforced, and the reasons why.
	 *
	 * @param name the value of the {@code x-mcp-header} keyword, which may not be a
	 * string
	 * @param path the {@code properties} keys leading from the schema root to the
	 * argument; for an annotation that isn't statically reachable, every key leading to
	 * the annotated schema, keywords and array indexes included
	 * @param problems why the annotation is invalid, in human-readable form, with
	 * characters outside printable ASCII written as Unicode escapes
	 */
	public record InvalidAnnotation(Object name, List<String> path, List<String> problems) {

		public InvalidAnnotation {
			path = List.copyOf(path);
			problems = List.copyOf(problems);
		}

		/**
		 * The argument's path joined with dots, for messages.
		 * @return the dotted path, such as {@code location.region}
		 */
		public String argument() {
			return String.join(".", this.path);
		}

		/**
		 * Describes the annotation and its problems, with characters outside printable
		 * ASCII written as Unicode escapes, so the description can safely be logged.
		 * @return a description such as
		 * {@code 'Score' on argument 'score' (unsupported type number)}
		 */
		public String describe() {
			return ToolParamHeaderAnnotations.describe(this.name, this.path) + " (" + String.join(", ", this.problems)
					+ ")";
		}

	}

	/** The argument types an annotation may declare. */
	public enum Type {

		STRING, INTEGER, BOOLEAN

	}

	/**
	 * Parses the {@code x-mcp-header} annotations of the tool's input schema.
	 * <p>
	 * Arguments are reached from the schema root through chains of {@code properties}
	 * keys. An annotation is invalid if:
	 * <ul>
	 * <li>it isn't statically reachable that way: it is on the root schema, or under
	 * {@code items}, {@code allOf}, {@code $defs} (where {@code $ref} targets live) or
	 * any other keyword whose value is a schema;</li>
	 * <li>its name is not a string, is empty, or isn't an RFC 9110 token;</li>
	 * <li>its name equals another annotation's ignoring case, in which case both are
	 * invalid;</li>
	 * <li>its {@code type} is missing or isn't one of the strings {@code string},
	 * {@code integer} or {@code boolean}; a type array, even {@code ["string", "null"]},
	 * is invalid.</li>
	 * </ul>
	 * @param tool the tool
	 * @return the tool's valid and invalid annotations
	 */
	public static ToolParamHeaderAnnotations parse(Tool tool) {
		List<Candidate> candidates = new ArrayList<>();
		collect(tool.inputSchema(), List.of(), List.of(), candidates);
		flagCollisions(candidates);
		List<Annotation> valid = new ArrayList<>();
		List<InvalidAnnotation> invalid = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (candidate.problems().isEmpty()) {
				valid.add(new Annotation((String) candidate.name(), candidate.path(), candidate.type()));
			}
			else {
				invalid.add(new InvalidAnnotation(candidate.name(), candidate.path(), candidate.problems()));
			}
		}
		return new ToolParamHeaderAnnotations(valid, invalid);
	}

	/**
	 * The valid annotations, in schema order: depth-first, a parent before its children.
	 * @return the valid annotations, empty if there are none
	 */
	public List<Annotation> valid() {
		return this.valid;
	}

	/**
	 * The invalid annotations, in schema order: depth-first, a parent before its
	 * children.
	 * @return the invalid annotations, empty if there are none
	 */
	public List<InvalidAnnotation> invalid() {
		return this.invalid;
	}

	/**
	 * Whether every annotation is valid. A compliant client drops a tool with invalid
	 * annotations from {@code tools/list}.
	 * @return {@code true} if there are no invalid annotations, including when the tool
	 * declares none
	 */
	public boolean isValid() {
		return this.invalid.isEmpty();
	}

	// Depth-first in schema order: arguments through properties keys, then the
	// unreachable annotations under the schema's other keywords.
	// The pointer holds every key from the root, keywords included.
	private static void collect(Map<?, ?> schema, List<String> parent, List<String> pointer,
			List<Candidate> candidates) {
		if (pointer.isEmpty() && schema.containsKey(KEYWORD)) {
			candidates.add(unreachable(List.of(), schema));
		}
		if (schema.get("properties") instanceof Map<?, ?> properties) {
			for (Map.Entry<?, ?> entry : properties.entrySet()) {
				if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Map<?, ?> property)) {
					continue;
				}
				List<String> path = new ArrayList<>(parent);
				path.add(key);
				if (property.containsKey(KEYWORD)) {
					candidates.add(candidate(List.copyOf(path), property));
				}
				collect(property, path, append(pointer, "properties", key), candidates);
			}
		}
		collectUnreachable(schema, pointer, candidates);
	}

	// Every annotation in the subschemas of the schema's keywords other than
	// properties, whose own properties are followed too.
	private static void collectUnreachable(Map<?, ?> schema, List<String> location, List<Candidate> candidates) {
		for (Map.Entry<?, ?> entry : schema.entrySet()) {
			if (!(entry.getKey() instanceof String keyword)) {
				continue;
			}
			if (SUBSCHEMA_KEYWORDS.contains(keyword)) {
				if (entry.getValue() instanceof List<?> list) {
					for (int i = 0; i < list.size(); i++) {
						unreachableIn(list.get(i), append(location, keyword, String.valueOf(i)), candidates);
					}
				}
				else {
					unreachableIn(entry.getValue(), append(location, keyword), candidates);
				}
			}
			else if (SCHEMA_MAP_KEYWORDS.contains(keyword) && entry.getValue() instanceof Map<?, ?> schemas) {
				for (Map.Entry<?, ?> named : schemas.entrySet()) {
					unreachableIn(named.getValue(), append(location, keyword, String.valueOf(named.getKey())),
							candidates);
				}
			}
		}
	}

	private static void unreachableIn(Object value, List<String> location, List<Candidate> candidates) {
		if (!(value instanceof Map<?, ?> schema)) {
			return;
		}
		if (schema.containsKey(KEYWORD)) {
			candidates.add(unreachable(location, schema));
		}
		if (schema.get("properties") instanceof Map<?, ?> properties) {
			for (Map.Entry<?, ?> entry : properties.entrySet()) {
				unreachableIn(entry.getValue(), append(location, "properties", String.valueOf(entry.getKey())),
						candidates);
			}
		}
		collectUnreachable(schema, location, candidates);
	}

	private static List<String> append(List<String> path, String... keys) {
		List<String> appended = new ArrayList<>(path);
		appended.addAll(List.of(keys));
		return List.copyOf(appended);
	}

	private static Candidate unreachable(List<String> location, Map<?, ?> schema) {
		Candidate candidate = candidate(location, schema);
		candidate.problems().add(0, UNREACHABLE);
		return candidate;
	}

	private static Candidate candidate(List<String> path, Map<?, ?> property) {
		Object name = property.get(KEYWORD);
		List<String> problems = new ArrayList<>();
		if (!(name instanceof String string)) {
			problems.add("name is not a string");
		}
		else if (string.isEmpty()) {
			problems.add("empty name");
		}
		else if (!TOKEN.matcher(string).matches()) {
			problems.add("name is not an RFC 9110 token");
		}
		Object typeKeyword = property.get("type");
		Type type = type(typeKeyword);
		if (type == null) {
			problems.add(typeKeyword == null ? "missing type" : "unsupported type " + escape(typeKeyword));
		}
		return new Candidate(name, path, type, problems);
	}

	private static void flagCollisions(List<Candidate> candidates) {
		Map<String, List<Candidate>> byName = new LinkedHashMap<>();
		for (Candidate candidate : candidates) {
			if (candidate.name() instanceof String name && !name.isEmpty()) {
				byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(candidate);
			}
		}
		for (List<Candidate> collision : byName.values()) {
			if (collision.size() < 2) {
				continue;
			}
			for (Candidate candidate : collision) {
				List<String> others = new ArrayList<>();
				for (Candidate other : collision) {
					if (other != candidate) {
						others.add(other.describe());
					}
				}
				candidate.problems().add("name collides ignoring case with " + String.join(" and ", others));
			}
		}
	}

	// Names come from user schemas: escape them so they can't break log lines.
	private static String escape(Object value) {
		String string = String.valueOf(value);
		StringBuilder escaped = new StringBuilder(string.length());
		for (char c : string.toCharArray()) {
			if (c >= 0x20 && c < 0x7F) {
				escaped.append(c);
			}
			else {
				escaped.append(String.format("\\u%04x", (int) c));
			}
		}
		return escaped.toString();
	}

	private record Candidate(Object name, List<String> path, Type type, List<String> problems) {

		String describe() {
			return ToolParamHeaderAnnotations.describe(this.name, this.path);
		}

	}

	private static String describe(Object name, List<String> path) {
		return "'" + escape(name) + "' on argument '" + escape(String.join(".", path)) + "'";
	}

	// null if the type can't be mirrored. The spec only admits a single primitive type,
	// so type arrays such as ["string", "null"] are rejected, as the TypeScript SDK does.
	private static Type type(Object type) {
		if (!(type instanceof String name)) {
			return null;
		}
		return switch (name) {
			case "string" -> Type.STRING;
			case "integer" -> Type.INTEGER;
			case "boolean" -> Type.BOOLEAN;
			default -> null;
		};
	}

}
