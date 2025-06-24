# Contributing to Model Context Protocol Java SDK

Thank you for your interest in contributing to the Model Context Protocol Java SDK!
This document outlines how to contribute to this project.

## Prerequisites

The following software is required to work on the codebase:

- `Java 17` or above
- `Docker`
- `npx`

## Getting Started

1. Fork the repository
2. Clone your fork:

```bash
git clone https://github.com/YOUR-USERNAME/java-sdk.git
cd java-sdk
```

3. Build from source:

```bash
./mvnw clean install -DskipTests # skip the tests
./mvnw test # run tests
```

## Reporting Issues

Please create an issue in the repository if you discover a bug or would like to 
propose an enhancement. Bug reports should have a reproducer in the form of a code 
sample or a repository attached that the maintainers or contributors can work with to 
address the problem.

## Making Changes

1. Create a new branch:

```bash
git checkout -b feature/your-feature-name
```

2. Make your changes
3. Validate your changes:

```bash
./mvnw clean test
```

### Change Proposal Guidelines

#### Principles of MCP

1. **Simple + Minimal**: It is much easier to add things to the codebase than it is to
   remove them. To maintain simplicity, we keep a high bar for adding new concepts and
   primitives as each addition requires maintenance and compatibility consideration.
2. **Concrete**: Code changes need to be based on specific usage and implementation
   challenges and not on speculative ideas. Most importantly, the SDK is meant to 
   implement the MCP specification.

## Submitting Changes

1. For non-trivial changes, please clarify with the maintainers in an issue whether 
   you can contribute the change and the desired scope of the change.
2. For trivial changes (for example a couple of lines or documentation changes) there 
   is no need to open an issue first.
3. Push your changes to your fork.
4. Submit a pull request to the main repository.
5. Follow the pull request template.
6. Wait for review.
7. For any follow-up work, please add new commits instead of force-pushing. This will 
   allow the reviewer to focus on incremental changes instead of having to restart the 
   review process.

## Code of Conduct

This project follows a Code of Conduct. Please review it in
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Questions

If you have questions, please create a discussion in the repository.

## License

By contributing, you agree that your contributions will be licensed under the MIT
License.

## Security

Please review our [Security Policy](SECURITY.md) for reporting security issues.