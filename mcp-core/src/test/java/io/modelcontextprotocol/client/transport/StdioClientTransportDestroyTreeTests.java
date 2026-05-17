/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * Tests for {@link StdioClientTransport#destroyProcessTree(Process)}.
 *
 * <p>
 * Regression coverage for the case where {@code closeGracefully()} previously left
 * orphaned descendant processes (e.g. {@code node} spawned beneath {@code npx} on
 * Windows). The tree-aware destroy must terminate the entire descendant chain, not just
 * the root.
 */
class StdioClientTransportDestroyTreeTests {

	@Test
	@DisabledOnOs(OS.WINDOWS) // sh + & + wait is POSIX; CI is ubuntu-latest
	void destroyProcessTreeTerminatesRootAndDescendants() throws Exception {
		// `sh` forks two long-sleeping children. Killing only `sh` would leave both
		// children alive — exactly the bug reported in #496 (but reproduced portably).
		Process root = new ProcessBuilder("sh", "-c", "sleep 60 & sleep 60 & wait").start();

		await().atMost(Duration.ofSeconds(3)).until(() -> root.descendants().count() >= 2);
		List<ProcessHandle> descendants = root.descendants().collect(Collectors.toList());
		assertThat(descendants).hasSizeGreaterThanOrEqualTo(2);

		StdioClientTransport.destroyProcessTree(root);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(root.isAlive()).as("root sh process").isFalse();
			for (ProcessHandle d : descendants) {
				assertThat(d.isAlive()).as("descendant pid=%s", d.pid()).isFalse();
			}
		});
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void destroyProcessTreeTerminatesRootWithNoDescendants() throws Exception {
		Process root = new ProcessBuilder("sleep", "60").start();

		assertThat(root.isAlive()).isTrue();

		StdioClientTransport.destroyProcessTree(root);

		await().atMost(Duration.ofSeconds(5)).until(() -> !root.isAlive());
	}

	@Test
	void destroyProcessTreeIsSafeOnAlreadyTerminatedProcess() throws Exception {
		// Pick a command that exists on every supported OS and exits immediately.
		String[] cmd = System.getProperty("os.name").toLowerCase().contains("win")
				? new String[] { "cmd.exe", "/c", "exit", "0" } : new String[] { "sh", "-c", "exit 0" };
		Process root = new ProcessBuilder(cmd).start();
		root.waitFor();
		assertThat(root.isAlive()).isFalse();

		assertThatCode(() -> StdioClientTransport.destroyProcessTree(root)).doesNotThrowAnyException();
	}

}
