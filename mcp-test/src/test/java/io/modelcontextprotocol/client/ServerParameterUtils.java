/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.modelcontextprotocol.client.transport.ServerParameters;

/**
 * Provides the {@link ServerParameters} used to launch the {@code server-everything} MCP
 * server for the stdio client tests.
 *
 * <p>
 * Those tests spawn a fresh server process per test, so the launch cost is paid dozens of
 * times per build. Going through {@code npx} costs roughly 3 seconds per spawn even with
 * a warm cache, because npx re-resolves the package every time, and it inserts two extra
 * processes ({@code npx} &rarr; {@code npm exec} &rarr; {@code node}) between the test
 * and the server. The latter also means the process the transport owns is not the server,
 * so closing the transport leaves the server behind.
 *
 * <p>
 * Instead, the package is installed once per JVM into a version-keyed directory under the
 * temporary directory, and the server is launched directly with {@code node}, which
 * brings the per-spawn cost down to roughly 0.4 seconds.
 */
public final class ServerParameterUtils {

	private static final String SERVER_EVERYTHING_VERSION = "2025.12.18";

	private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

	private static final Path SERVER_SCRIPT = resolveServerScript();

	private static final String NODE_EXECUTABLE = resolveNodeExecutable();

	private ServerParameterUtils() {
	}

	public static ServerParameters createServerParameters() {
		return ServerParameters.builder(NODE_EXECUTABLE).args(SERVER_SCRIPT.toString(), "stdio").build();
	}

	private static Path resolveServerScript() {
		Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
		Path installDir = tmpDir.resolve("mcp-server-everything-" + SERVER_EVERYTHING_VERSION);
		if (!Files.isRegularFile(serverScriptIn(installDir))) {
			install(tmpDir, installDir);
		}
		Path script = serverScriptIn(installDir);
		if (!Files.isRegularFile(script)) {
			throw new IllegalStateException("server-everything was not installed at " + script);
		}
		return script;
	}

	private static Path serverScriptIn(Path installDir) {
		return installDir
			.resolve(Paths.get("node_modules", "@modelcontextprotocol", "server-everything", "dist", "index.js"));
	}

	/**
	 * Installs into a staging directory and moves it into place atomically, so that
	 * concurrent builds sharing the temporary directory can never observe a partially
	 * installed tree.
	 */
	private static void install(Path tmpDir, Path installDir) {
		Path staging;
		try {
			staging = Files.createTempDirectory(tmpDir, "mcp-server-everything-staging-");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		try {
			run(npmCommand("install", "--prefix", staging.toString(), "--no-save", "--no-audit", "--no-fund",
					"--loglevel=error", "@modelcontextprotocol/server-everything@" + SERVER_EVERYTHING_VERSION));
			try {
				Files.move(staging, installDir, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException e) {
				// Another build won the race and installed it first, which is fine as
				// long as the result is usable.
				if (!Files.isRegularFile(serverScriptIn(installDir))) {
					throw new UncheckedIOException("Failed to install server-everything to " + installDir, e);
				}
			}
		}
		finally {
			deleteRecursively(staging);
		}
	}

	private static void deleteRecursively(Path path) {
		if (!Files.exists(path)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(path)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				}
				catch (IOException e) {
					// Leftovers in the temporary directory are harmless.
				}
			});
		}
		catch (IOException e) {
			// Leftovers in the temporary directory are harmless.
		}
	}

	/**
	 * Asks node for its own absolute path, so that spawning the server does not go
	 * through a {@code node} wrapper script on the {@code PATH} (as installed by nvm and
	 * friends), which would add an extra shell process per spawn.
	 */
	private static String resolveNodeExecutable() {
		String node = IS_WINDOWS ? "node.exe" : "node";
		try {
			return run(List.of(node, "-p", "process.execPath")).trim();
		}
		catch (RuntimeException e) {
			return node;
		}
	}

	private static List<String> npmCommand(String... args) {
		List<String> command = new ArrayList<>();
		if (IS_WINDOWS) {
			command.add("cmd.exe");
			command.add("/c");
			command.add("npm.cmd");
		}
		else {
			command.add("npm");
		}
		command.addAll(List.of(args));
		return command;
	}

	private static String run(List<String> command) {
		try {
			// Note: the output is redirected to a file rather than inherited, because
			// writing to the native streams from a forked JVM corrupts the Surefire fork
			// channel. A file rather than a pipe also keeps the timeout below effective,
			// which draining the pipe first would not.
			Path log = Files.createTempFile("mcp-test-", ".log");
			try {
				Process process = new ProcessBuilder(command).redirectErrorStream(true)
					.redirectOutput(log.toFile())
					.start();
				if (!process.waitFor(5, TimeUnit.MINUTES)) {
					process.destroyForcibly();
					throw new IllegalStateException("Timed out running " + command);
				}
				String output = Files.readString(log);
				if (process.exitValue() != 0) {
					throw new IllegalStateException(
							command + " failed with exit code " + process.exitValue() + ":\n" + output);
				}
				return output;
			}
			finally {
				Files.deleteIfExists(log);
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
