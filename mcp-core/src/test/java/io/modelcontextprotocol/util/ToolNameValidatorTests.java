/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ToolNameValidator}.
 */
class ToolNameValidatorTests {

	@ParameterizedTest
	@ValueSource(strings = { "getUser", "DATA_EXPORT_v2", "admin.tools.list", "my-tool", "Tool123", "a", "A",
			"_private", "tool_name", "tool-name", "tool.name", "UPPERCASE", "lowercase", "MixedCase123" })
	void validToolNames_strictMode(String name) {
		assertThatCode(() -> ToolNameValidator.validate(name, false)).doesNotThrowAnyException();
	}

	@Test
	void validToolName_maxLength() {
		String name = "a".repeat(128);
		assertThatCode(() -> ToolNameValidator.validate(name, false)).doesNotThrowAnyException();
	}

	@Test
	void invalidToolName_null_strictMode() {
		assertThatThrownBy(() -> ToolNameValidator.validate(null, false)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("null or empty");
	}

	@Test
	void invalidToolName_empty_strictMode() {
		assertThatThrownBy(() -> ToolNameValidator.validate("", false)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("null or empty");
	}

	@Test
	void invalidToolName_tooLong_strictMode() {
		String name = "a".repeat(129);
		assertThatThrownBy(() -> ToolNameValidator.validate(name, false)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("128 characters");
	}

	@ParameterizedTest
	@ValueSource(strings = { "tool name", // space
			"tool,name", // comma
			"tool@name", // at sign
			"tool#name", // hash
			"tool$name", // dollar
			"tool%name", // percent
			"tool&name", // ampersand
			"tool*name", // asterisk
			"tool+name", // plus
			"tool=name", // equals
			"tool/name", // slash
			"tool\\name", // backslash
			"tool:name", // colon
			"tool;name", // semicolon
			"tool'name", // single quote
			"tool\"name", // double quote
			"tool<name", // less than
			"tool>name", // greater than
			"tool?name", // question mark
			"tool!name", // exclamation
			"tool(name)", // parentheses
			"tool[name]", // brackets
			"tool{name}", // braces
			"tool|name", // pipe
			"tool~name", // tilde
			"tool`name", // backtick
			"tool^name", // caret
			"tööl", // non-ASCII
			"工具" // unicode
	})
	void invalidToolNames_specialCharacters_strictMode(String name) {
		assertThatThrownBy(() -> ToolNameValidator.validate(name, false)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid characters");
	}

	@Test
	void invalidToolName_skipValidation_doesNotThrow() {
		// skip=true means warn only, should not throw
		assertThatCode(() -> ToolNameValidator.validate("invalid name", true)).doesNotThrowAnyException();
		assertThatCode(() -> ToolNameValidator.validate(null, true)).doesNotThrowAnyException();
		assertThatCode(() -> ToolNameValidator.validate("", true)).doesNotThrowAnyException();
		assertThatCode(() -> ToolNameValidator.validate("a".repeat(129), true)).doesNotThrowAnyException();
	}

}
