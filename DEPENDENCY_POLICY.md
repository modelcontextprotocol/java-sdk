# Dependency Policy

As a library consumed by downstream projects, the MCP Java SDK takes a conservative approach to dependency updates. Production dependencies receive patch updates routinely: they carry little risk, and keep consumers that do not manage dependency versions themselves off stale releases with known bugs. Minor and major updates are avoided, as they can force downstream consumers to adopt a new version of a dependency transitively, which can be disruptive for projects with strict dependency policies.

## Update Rules

On the active development line (`main`):

- **Patch updates** of production dependencies are applied routinely.
- **Minor updates** of production dependencies are applied only with an SDK minor release, and are noted in the release notes.
- **Major updates** of production dependencies are applied only with an SDK major release, or through a separate opt-in module (for example `mcp-json-jackson3`).
- **Test dependencies, Maven plugins, and GitHub Actions** are kept up to date, across all versions. The exception is the compile dependencies of the published `mcp-test` module (JUnit, AssertJ, Mockito, Testcontainers, and others), which end up on consumers' test classpath: these do not receive major updates outside of an SDK major release.

On maintenance lines (see [VERSIONING.md](VERSIONING.md)), only patch updates are applied.

Security vulnerabilities are fixed on every supported release line. When a fix is only available in a new minor or major version of a dependency, the dependency is updated anyway, and the update is noted in the release notes.

## What We Don't Do

The SDK does not accept contributor pull requests that update dependency versions. Dependency updates are handled by Dependabot and the maintainers. If a dependency needs to be updated, for example to fix a bug affecting the SDK, please open an issue instead.

## Automated Tooling

- **GitHub security updates** are enabled at the repository level and automatically open pull requests for Maven packages with known vulnerabilities. This is a GitHub repo setting, separate from the `dependabot.yml` configuration.
- **GitHub Actions versions** are kept up to date via Dependabot on a weekly schedule (see `.github/dependabot.yml`).
- **Maven dependencies** on `main` are kept up to date via Dependabot on a weekly schedule, following the rules above (see `.github/dependabot.yml`).
- **Maven dependencies** on maintenance branches receive patch updates via Dependabot on a monthly schedule (see `.github/dependabot.yml`).
