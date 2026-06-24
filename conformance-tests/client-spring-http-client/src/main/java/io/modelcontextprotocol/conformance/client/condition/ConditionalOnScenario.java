/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client.condition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Condition to include beans only when certain scenarios are active / inactive. Checks
 * the value of the {@code MCP_CONFORMANCE_SCENARIO} environment variable and matches
 * against {@link #included()} and {@link #excluded()}. Exactly one of these attributes
 * must be defined.
 * <p>
 * Usage: <pre>
 *
 * &#64;Configuration
 * &#64;ConditionalOnScenario(excluded =
 *   {
 *     "auth/pre-registration",
 *     "auth/client-credentials-basic"
 *   }
 * )
 * public class DefaultConfiguration {
 *     // ...
 * }
 * </pre>
 *
 * @author Daniel Garnier-Moiroux
 * @see OnScenarioCondition
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnScenarioCondition.class)
public @interface ConditionalOnScenario {

	String[] included() default {};

	String[] excluded() default {};

}
