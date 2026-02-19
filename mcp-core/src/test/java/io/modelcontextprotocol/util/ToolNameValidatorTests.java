/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.util.List;
import java.util.function.Consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ToolNameValidator}.
 */
class ToolNameValidatorTests {

	private final Logger logger = (Logger) LoggerFactory.getLogger(ToolNameValidator.class);

	private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		logAppender.start();
		logger.addAppender(logAppender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(logAppender);
		logAppender.stop();
	}

	@ParameterizedTest
	@ValueSource(strings = { "getUser", "DATA_EXPORT_v2", "admin.tools.list", "my-tool", "Tool123", "a", "A",
			"_private", "tool_name", "tool-name", "tool.name", "UPPERCASE", "lowercase", "MixedCase123" })
	void validToolNames(String name) {
		assertThatCode(() -> ToolNameValidator.validate(name, true)).doesNotThrowAnyException();
		ToolNameValidator.validate(name, false);
		assertThat(logAppender.list).isEmpty();
	}

	@Test
	void validToolNameMaxLength() {
		String name = "a".repeat(128);
		assertThatCode(() -> ToolNameValidator.validate(name, true)).doesNotThrowAnyException();
		ToolNameValidator.validate(name, false);
		assertThat(logAppender.list).isEmpty();
	}

	@Test
	void nullOrEmpty() {
		assertThatThrownBy(() -> ToolNameValidator.validate(null, true)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("null or empty");
		assertThatThrownBy(() -> ToolNameValidator.validate("", true)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("null or empty");
	}

	@Test
	void strictLength() {
		String name = "a".repeat(129);
		assertThatThrownBy(() -> ToolNameValidator.validate(name, true)).isInstanceOf(IllegalArgumentException.class)
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
	void strictInvalidCharacters(String name) {
		assertThatThrownBy(() -> ToolNameValidator.validate(name, true)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid characters");
	}

	@Test
	void lenientNull() {
		assertThatCode(() -> ToolNameValidator.validate(null, false)).doesNotThrowAnyException();
		assertThat(logAppender.list).satisfies(hasWarning("null or empty"));
	}

	@Test
	void lenientEmpty() {
		assertThatCode(() -> ToolNameValidator.validate("", false)).doesNotThrowAnyException();
		assertThat(logAppender.list).satisfies(hasWarning("null or empty"));
	}

	@Test
	void lenientLength() {
		assertThatCode(() -> ToolNameValidator.validate("a".repeat(129), false)).doesNotThrowAnyException();
		assertThat(logAppender.list).satisfies(hasWarning("128 characters"));
	}

	@Test
	void lenientInvalidCharacters() {
		assertThatCode(() -> ToolNameValidator.validate("invalid name", false)).doesNotThrowAnyException();
		assertThat(logAppender.list).satisfies(hasWarning("invalid characters"));
	}

	private Consumer<List<? extends ILoggingEvent>> hasWarning(String errorMessage) {
		return logs -> {
			assertThat(logs).hasSize(1).first().satisfies(log -> {
				assertThat(log.getLevel()).isEqualTo(Level.WARN);
				assertThat(log.getFormattedMessage()).contains(errorMessage);
			});
		};
	}

}
