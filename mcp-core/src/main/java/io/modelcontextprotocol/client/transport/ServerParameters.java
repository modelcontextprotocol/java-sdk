/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.modelcontextprotocol.util.Assert;

/**
 * Server parameters for stdio client. This is not a wire type; Jackson annotations are
 * intentionally omitted.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public class ServerParameters {

	// Environment variables to inherit by default
	private static final List<String> DEFAULT_INHERITED_ENV_VARS = System.getProperty("os.name")
		.toLowerCase()
		.contains("win")
				? Arrays.asList("APPDATA", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA", "PATH", "PROCESSOR_ARCHITECTURE",
						"SYSTEMDRIVE", "SYSTEMROOT", "TEMP", "USERNAME", "USERPROFILE")
				: Arrays.asList("HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER");

	private static final Duration DEFAULT_PROCESS_TERMINATION_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration MAX_PROCESS_TERMINATION_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE);

	private String command;

	private List<String> args = new ArrayList<>();

	private Map<String, String> env;

	private Duration processTerminationTimeout;

	private ServerParameters(String command, List<String> args, Map<String, String> env,
			Duration processTerminationTimeout) {
		Assert.notNull(command, "The command can not be null");
		Assert.notNull(args, "The args can not be null");

		this.command = command;
		this.args = args;
		this.env = new HashMap<>(getDefaultEnvironment());
		if (env != null && !env.isEmpty()) {
			this.env.putAll(env);
		}
		this.processTerminationTimeout = processTerminationTimeout;
	}

	public String getCommand() {
		return this.command;
	}

	public List<String> getArgs() {
		return this.args;
	}

	public Map<String, String> getEnv() {
		return this.env;
	}

	/**
	 * How long {@link StdioClientTransport#closeGracefully()} waits for the server
	 * process to exit after the termination request before destroying it forcibly.
	 * @return the termination timeout, 10 seconds by default
	 */
	public Duration getProcessTerminationTimeout() {
		return this.processTerminationTimeout;
	}

	public static Builder builder(String command) {
		return new Builder(command);
	}

	public static class Builder {

		private String command;

		private List<String> args = new ArrayList<>();

		private Map<String, String> env = new HashMap<>();

		private Duration processTerminationTimeout = DEFAULT_PROCESS_TERMINATION_TIMEOUT;

		public Builder(String command) {
			Assert.notNull(command, "The command can not be null");
			this.command = command;
		}

		public Builder args(String... args) {
			Assert.notNull(args, "The args can not be null");
			this.args = Arrays.asList(args);
			return this;
		}

		public Builder args(List<String> args) {
			Assert.notNull(args, "The args can not be null");
			this.args = new ArrayList<>(args);
			return this;
		}

		public Builder arg(String arg) {
			Assert.notNull(arg, "The arg can not be null");
			this.args.add(arg);
			return this;
		}

		public Builder env(Map<String, String> env) {
			if (env != null && !env.isEmpty()) {
				this.env.putAll(env);
			}
			return this;
		}

		public Builder addEnvVar(String key, String value) {
			Assert.notNull(key, "The key can not be null");
			Assert.notNull(value, "The value can not be null");
			this.env.put(key, value);
			return this;
		}

		/**
		 * Sets how long {@link StdioClientTransport#closeGracefully()} waits for the
		 * server process to exit after the termination request before destroying it
		 * forcibly. Defaults to 10 seconds.
		 * @param processTerminationTimeout the timeout, must be positive and fit in
		 * {@code Long.MAX_VALUE} nanoseconds (about 292 years)
		 * @return this builder
		 */
		public Builder processTerminationTimeout(Duration processTerminationTimeout) {
			Assert.notNull(processTerminationTimeout, "The processTerminationTimeout can not be null");
			Assert.isTrue(!processTerminationTimeout.isNegative() && !processTerminationTimeout.isZero(),
					"The processTerminationTimeout must be positive");
			// The timeout is scheduled in nanoseconds; a longer Duration would overflow
			// when closeGracefully() runs
			Assert.isTrue(processTerminationTimeout.compareTo(MAX_PROCESS_TERMINATION_TIMEOUT) <= 0,
					"The processTerminationTimeout must not exceed Long.MAX_VALUE nanoseconds");
			this.processTerminationTimeout = processTerminationTimeout;
			return this;
		}

		public ServerParameters build() {
			return new ServerParameters(command, args, env, processTerminationTimeout);
		}

	}

	/**
	 * Returns a default environment object including only environment variables deemed
	 * safe to inherit.
	 */
	private static Map<String, String> getDefaultEnvironment() {
		return System.getenv()
			.entrySet()
			.stream()
			.filter(entry -> DEFAULT_INHERITED_ENV_VARS.contains(entry.getKey()))
			.filter(entry -> entry.getValue() != null)
			.filter(entry -> !entry.getValue().startsWith("()"))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

}