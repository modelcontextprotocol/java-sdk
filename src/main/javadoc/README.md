# Javadoc Documentation

This directory contains documentation that is included in the generated Javadoc.

## Adding Documentation

### Overview Page

Edit `overview.md` in this directory for the main project overview page.

### Additional Pages

Place markdown files in `mcp-core/src/main/javadoc/io/modelcontextprotocol/doc-files/`.

Example: `mcp-core/src/main/javadoc/io/modelcontextprotocol/doc-files/getting-started.md`

## Requirements

- **JDK 23+** is required to generate javadocs with markdown support (JEP 467)
- Files use CommonMark syntax with GitHub-flavored tables
- Generate docs locally: `mvn -Pjavadoc javadoc:aggregate`

## Linking to Documentation

From javadoc comments, link to doc-files:

```java
/**
 * See the <a href="doc-files/getting-started.html">Getting Started guide</a>.
 */
```

Note: Link to `.html` even though source is `.md` - the javadoc tool converts them.

From the overview page, use relative links:

```markdown
See [Getting Started](io/modelcontextprotocol/doc-files/getting-started.html)
```

## Markdown Syntax

The javadoc tool supports CommonMark with these extensions:

- GitHub-flavored tables
- Fenced code blocks with syntax highlighting
- Links to Java elements: `[text][java.util.List]`

For full details, see [JEP 467: Markdown Documentation Comments](https://openjdk.org/jeps/467).
