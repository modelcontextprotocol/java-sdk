package io.modelcontextprotocol.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements URI Template handling according to RFC 6570. This class allows for the
 * expansion of URI templates with variables and also supports matching URIs against
 * templates to extract variables.
 * <p>
 * URI templates are strings with embedded expressions enclosed in curly braces, such as:
 * http://example.com/{username}/profile{?tab,section}
 */
public class UriTemplate {

	// Constants for security and performance limits
	private static final int MAX_TEMPLATE_LENGTH = 1_000_000;

	private static final int MAX_VARIABLE_LENGTH = 1_000_000;

	private static final int MAX_TEMPLATE_EXPRESSIONS = 10_000;

	private static final int MAX_REGEX_LENGTH = 1_000_000;

	// The original template string and parsed components
	private final String template;

	private final List<Part> parts;

	private final Pattern pattern;

	/**
	 * Constructor to create a new UriTemplate instance. Validates the template length,
	 * parses it into parts, and compiles a regex pattern.
	 * @param template The URI template string
	 * @throws IllegalArgumentException if the template is invalid or too long
	 */
	public UriTemplate(String template) {
		validateLength(template, MAX_TEMPLATE_LENGTH, "Template");
		this.template = template;
		this.parts = parseTemplate(template);
		this.pattern = Pattern.compile(createMatchingPattern());
	}

	/**
	 * Returns the original template string.
	 */
	@Override
	public String toString() {
		return template;
	}

	/**
	 * Checks if a given URI matches the compiled template pattern.
	 * @param uri The URI to check
	 * @return true if the URI matches the template pattern, false otherwise
	 */
	public boolean isMatching(String uri) {
		validateLength(uri, MAX_TEMPLATE_LENGTH, "URI");
		return pattern.matcher(uri).matches();
	}

	/**
	 * Matches a URI against this template and extracts variable values.
	 * @param uri The URI to match
	 * @return Map of variable names to extracted values, or null if the URI doesn't match
	 */
	public Map<String, Object> match(String uri) {
		validateLength(uri, MAX_TEMPLATE_LENGTH, "URI");
		Matcher matcher = pattern.matcher(uri);
		if (!matcher.matches())
			return null;

		// Extract variable names from parts and capture their values
		List<NameInfo> names = extractNamesFromParts();
		Map<String, Object> result = new HashMap<>();
		for (int i = 0; i < names.size(); i++) {
			NameInfo nameInfo = names.get(i);
			String value = matcher.group(i + 1);
			String cleanName = nameInfo.name().replace("*", "");

			// Handle exploded values (comma-separated lists)
			if (nameInfo.exploded() && value.contains(",")) {
				result.put(cleanName, List.of(value.split(",")));
			}
			else {
				result.put(cleanName, value);
			}
		}
		return result;
	}

	/**
	 * Validates that a string does not exceed a maximum allowed length.
	 * @param str String to validate
	 * @param max Maximum allowed length
	 * @param context Context description for error message
	 * @throws IllegalArgumentException if the string exceeds the maximum length
	 */
	private static void validateLength(String str, int max, String context) {
		if (str.length() > max) {
			throw new IllegalArgumentException(
					context + " exceeds maximum length of " + max + " characters (got " + str.length() + ")");
		}
	}

	/**
	 * Parses a URI template into parts consisting of literal strings and template parts.
	 * @param template The URI template to parse
	 * @return List of parts (Strings for literals, TemplatePart objects for expressions)
	 */
	private List<Part> parseTemplate(String template) {
		List<Part> parsedParts = new ArrayList<>();
		StringBuilder literal = new StringBuilder();
		int expressionCount = 0;

		// Iteratively parse template into parts
		for (int i = 0; i < template.length(); i++) {
			if (template.charAt(i) == '{') {
				if (!literal.isEmpty()) {
					parsedParts.add(new Part(literal.toString()));
					literal.setLength(0);
				}
				int end = template.indexOf("}", i);
				if (end == -1)
					throw new IllegalArgumentException("Unclosed template expression");

				expressionCount++;
				if (expressionCount > MAX_TEMPLATE_EXPRESSIONS) {
					throw new IllegalArgumentException("Too many template expressions");
				}

				String expr = template.substring(i + 1, end);
				parsedParts.add(parseTemplatePart(expr));
				i = end;
			}
			else {
				literal.append(template.charAt(i));
			}
		}
		if (!literal.isEmpty())
			parsedParts.add(new Part(literal.toString()));

		return parsedParts;
	}

	/**
	 * Parses a single template expression into a TemplatePart object.
	 * @param expr The template expression string
	 * @return A TemplatePart object representing the expression
	 */
	private TemplatePart parseTemplatePart(String expr) {
		String operator = extractOperator(expr);
		boolean exploded = expr.contains("*");
		List<String> names = extractNames(expr);

		for (String name : names)
			validateLength(name, MAX_VARIABLE_LENGTH, "Variable name");

		return new TemplatePart(names.get(0), operator, names, exploded);
	}

	/**
	 * Extracts the operator from a template expression if present.
	 * @param expr The template expression string
	 * @return The operator as a string, or an empty string if none
	 */
	private String extractOperator(String expr) {
		return switch (expr.charAt(0)) {
			case '+', '#', '.', '/', '?', '&' -> String.valueOf(expr.charAt(0));
			default -> "";
		};
	}

	/**
	 * Extracts variable names from a template expression.
	 * @param expr The template expression string
	 * @return A list of variable names
	 */
	private List<String> extractNames(String expr) {
		String[] nameParts = expr.replaceAll("^[+.#/?&]", "").split(",");
		List<String> names = new ArrayList<>();
		for (String name : nameParts) {
			String trimmed = name.replace("*", "").trim();
			if (!trimmed.isEmpty())
				names.add(trimmed);
		}
		return names;
	}

	/**
	 * Constructs a regex pattern string to match URIs based on the template parts.
	 * @return A regex pattern string
	 */
	private String createMatchingPattern() {
		StringBuilder patternBuilder = new StringBuilder("^");
		for (Part part : parts) {
			if (part instanceof TemplatePart templatePart) {
				patternBuilder.append(createPatternForPart(templatePart));
			}
			else {
				patternBuilder.append(Pattern.quote(part.getName()));
			}
		}
		patternBuilder.append("$");
		String patternStr = patternBuilder.toString();
		validateLength(patternStr, MAX_REGEX_LENGTH, "Generated regex pattern");
		return patternStr;
	}

	/**
	 * Creates a regex pattern for a specific template part based on its operator.
	 * @param part The template part
	 * @return A regex pattern string
	 */
	private String createPatternForPart(TemplatePart part) {
		return switch (part.getOperator()) {
			case "", "+" -> part.isExploded() ? "([^/]+(?:,[^/]+)*)" : "([^/,]+)";
			case "#" -> "(.+)";
			case "." -> "\\.([^/,]+)";
			case "/" -> "/" + (part.isExploded() ? "([^/]+(?:,[^/]+)*)" : "([^/,]+)");
			case "?", "&" -> "\\?" + part.getName() + "=([^&]+)";
			default -> "([^/]+)";
		};
	}

	/**
	 * Extracts variable names from template parts.
	 * @return A list of NameInfo objects containing variable names and their properties
	 */
	private List<NameInfo> extractNamesFromParts() {
		List<NameInfo> names = new ArrayList<>();
		for (Object part : parts) {
			if (part instanceof TemplatePart templatePart) {
				templatePart.getNames().forEach(name -> names.add(new NameInfo(name, templatePart.isExploded())));
			}
		}
		return names;
	}

	private static class Part {

		private String name;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Part(String name) {
			this.name = name;
		}

	}

	private static class TemplatePart extends Part {

		private String operator;

		private List<String> names;

		private boolean exploded;

		public String getOperator() {
			return operator;
		}

		public void setOperator(String operator) {
			this.operator = operator;
		}

		public List<String> getNames() {
			return names;
		}

		public void setNames(List<String> names) {
			this.names = names;
		}

		public boolean isExploded() {
			return exploded;
		}

		public void setExploded(boolean exploded) {
			this.exploded = exploded;
		}

		public TemplatePart(String name, String operator, List<String> names, boolean exploded) {
			super(name);
			this.operator = operator;
			this.names = names;
			this.exploded = exploded;
		}

	}

	// Record classes for data encapsulation
	private record NameInfo(String name, boolean exploded) {
	}

}