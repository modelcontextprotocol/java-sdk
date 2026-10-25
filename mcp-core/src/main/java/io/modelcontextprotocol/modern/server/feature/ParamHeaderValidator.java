/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Annotation;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Type;
import io.modelcontextprotocol.modern.server.McpParamHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks the {@code Mcp-Param-{Name}} headers of a {@code tools/call} against the
 * arguments the tool annotates with {@code x-mcp-header}.
 *
 * @author Daniel Garnier-Moiroux
 * @see <a href="https://modelcontextprotocol.io/seps/2243-http-standardization">SEP-2243:
 * HTTP Standardization</a>
 */
final class ParamHeaderValidator {

	private static final Logger logger = LoggerFactory.getLogger(ParamHeaderValidator.class);

	private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

	private static final Valid VALID = new Valid();

	private ParamHeaderValidator() {
	}

	/**
	 * Validates every annotation, in order, and reports the first failure.
	 * <p>
	 * Headers that no annotation declares are ignored, even when malformed. Error
	 * messages name the header and the argument but never their values, which are logged
	 * at {@code DEBUG} instead.
	 * @param annotations the tool's annotations
	 * @param callToolArguments the arguments for tools/call, empty if the call has none
	 * @param headers the request's headers
	 * @return {@link Valid}, or {@link Invalid} with the first failure
	 */
	static Result validate(List<Annotation> annotations, Map<String, Object> callToolArguments,
			McpParamHeaders headers) {
		for (Annotation annotation : annotations) {
			Object argument = resolve(callToolArguments, annotation.path());
			String problem = check(annotation, argument, headers);
			if (problem != null) {
				logger.debug("Rejecting tools/call: {} (header: {}, argument: {})", problem,
						describe(headers, annotation.name()), argument);
				return new Invalid(problem);
			}
		}
		return VALID;
	}

	// null if the header agrees with the argument.
	private static String check(Annotation annotation, Object argument, McpParamHeaders headers) {
		boolean present = headers.contains(annotation.name());
		String mismatch = header(annotation) + " header does not match argument '" + annotation.argument() + "'";
		if (argument == null) {
			return present
					? header(annotation) + " header present but argument '" + annotation.argument() + "' is absent"
					: null;
		}
		if (argument instanceof Map<?, ?> || argument instanceof List<?>) {
			// Can't be mirrored: a header is a mismatch, and without one the input
			// schema validation reports the wrong type.
			return present ? mismatch : null;
		}
		if (!present) {
			return "Missing required header: " + header(annotation);
		}
		if (headers.isMalformed(annotation.name())) {
			return header(annotation) + " header is malformed";
		}
		return matches(annotation.type(), argument, headers.value(annotation.name())) ? null : mismatch;
	}

	private static Object resolve(Map<String, Object> arguments, List<String> path) {
		Object value = arguments;
		for (String key : path) {
			if (!(value instanceof Map<?, ?> object)) {
				return null;
			}
			value = object.get(key);
		}
		return value;
	}

	// Integers are compared numerically, so 42, 42.0 and 4.2e1 are equal whatever form
	// the client and the JSON parser used.
	//
	// The spec asks servers to reject integers beyond +/-(2^53-1). That rule exists
	// because JavaScript can't represent larger integers exactly. Java's Long and
	// BigInteger can, so large values are compared exactly instead; tool authors who
	// want a bound can declare minimum and maximum.
	private static boolean matches(Type type, Object argument, String header) {
		if (type == Type.INTEGER && argument instanceof Number number) {
			BigDecimal value = decimal(number);
			return value != null && DECIMAL.matcher(header).matches() && value.compareTo(new BigDecimal(header)) == 0;
		}
		return String.valueOf(argument).equals(header);
	}

	// null for NaN and infinities.
	private static BigDecimal decimal(Number number) {
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

	private static String header(Annotation annotation) {
		return McpParamHeaders.HEADER_PREFIX + annotation.name();
	}

	private static String describe(McpParamHeaders headers, String name) {
		if (headers.isMalformed(name)) {
			return "<malformed>";
		}
		String value = headers.value(name);
		return (value != null) ? value : "<absent>";
	}

	/**
	 * The outcome of {@link #validate}.
	 */
	sealed interface Result {

	}

	/**
	 * Every annotated header agrees with its argument.
	 */
	record Valid() implements Result {

	}

	/**
	 * A header disagrees with its argument.
	 *
	 * @param problem names the header and the argument, never their values
	 */
	record Invalid(String problem) implements Result {

	}

}
