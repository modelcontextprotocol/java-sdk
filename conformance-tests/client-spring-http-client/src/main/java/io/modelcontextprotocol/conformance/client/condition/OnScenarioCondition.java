/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client.condition;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.Assert;

/**
 * Condition implementation for {@link ConditionalOnScenario}.
 *
 * @author Daniel Garnier-Moiroux
 */
class OnScenarioCondition extends SpringBootCondition {

	private static final String ENV_VAR = "MCP_CONFORMANCE_SCENARIO";

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, @Nullable Object> attributes = metadata
			.getAnnotationAttributes(ConditionalOnScenario.class.getName());
		Assert.state(attributes != null, "'attributes' must not be null");

		String[] included = (String[]) attributes.get("included");
		String[] excluded = (String[]) attributes.get("excluded");

		boolean hasIncluded = included != null && included.length > 0;
		boolean hasExcluded = excluded != null && excluded.length > 0;

		Assert.state(hasIncluded ^ hasExcluded,
				"@ConditionalOnScenario must have exactly one of 'included' or 'excluded' defined");

		String scenario = System.getenv(ENV_VAR);

		if (hasIncluded) {
			List<String> includedList = Arrays.asList(included);
			boolean matches = scenario != null && includedList.contains(scenario);
			ConditionMessage message = ConditionMessage.forCondition(ConditionalOnScenario.class)
				.because("scenario '" + scenario + "' " + (matches ? "is" : "is not") + " in included list "
						+ includedList);
			return matches ? ConditionOutcome.match(message) : ConditionOutcome.noMatch(message);
		}
		else {
			List<String> excludedList = Arrays.asList(excluded);
			boolean matches = scenario == null || !excludedList.contains(scenario);
			ConditionMessage message = ConditionMessage.forCondition(ConditionalOnScenario.class)
				.because("scenario '" + scenario + "' " + (matches ? "is not" : "is") + " in excluded list "
						+ excludedList);
			return matches ? ConditionOutcome.match(message) : ConditionOutcome.noMatch(message);
		}
	}

}
