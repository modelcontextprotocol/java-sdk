# Release procedure

## Off the `main` branch

Instructions for a new major, new minor, or a patch when this would make it the latest version (e.g. 2.2.1 when latest version is 2.2.0)

Ensure you have the latest changes locally, and no uncommitted changes:

```shell
git pull --ff-only
git status
```

Set the version locally:

```shell
export RELEASE_VERSION=2.2.0
export NEXT_VERSION=2.2.1-SNAPSHOT
```

Update the versions:

```shell
# Update version in pom
./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=$RELEASE_VERSION
```

Update the docs (`docs/quickstart.md` and `mkdocs.yml`) to point to the new release version:

```shell
# "-i.bak" works with both GNU and BSD (macOS) sed, unlike a bare "-i".

sed -i.bak \
  -e "s|<version>[^<]*</version>|<version>${RELEASE_VERSION}</version>|g" \
  -e "s|mcp-bom:[^\"]*\"|mcp-bom:${RELEASE_VERSION}\"|g" \
  docs/quickstart.md
rm docs/quickstart.md.bak

sed -i.bak "s|\(mcp-core/\)[0-9][^ ]*|\1${RELEASE_VERSION}|" mkdocs.yml
rm mkdocs.yml.bak
```

Commit everything and tag:

```shell
git add pom.xml mkdocs.yml docs/quickstart.md "**/pom.xml"
git commit --signoff --message "Release version $RELEASE_VERSION"
git tag --annotate "v$RELEASE_VERSION" --message "Release version $RELEASE_VERSION"
```

Review the commit and the tag (`git show "v$RELEASE_VERSION"`), then prepare the next version:

```shell
# Update version in pom
./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=$NEXT_VERSION

git add pom.xml "**/pom.xml"
git commit --signoff --message "Next development version"
```

Review the commit (`git show`).

> ⚠️ Take a good review before pushing!

Then push it all:

```shell
git push --tags origin HEAD
```

Then release from the "[Release to Maven Central](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/maven-central-release.yml)" action. Click "Run workflow", choose the newly pushed tag, then "run workflow".

Create release notes.
