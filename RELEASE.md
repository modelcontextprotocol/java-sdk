# Release procedure

## Off the `main` branch

Instructions for a new major, new minor, or a patch when this would make it the latest version (e.g. 2.2.1 when latest version is 2.2.0)

Set the version locally:

```shell
export RELEASE_VERSION=2.2.0
export NEXT_VERSION=2.2.1-SNAPSHOT
export RELEASE_BRANCH="release/${RELEASE_VERSION}"
```

Ensure you have the latest changes locally, and no uncommitted changes.
Then create a release branch.

```shell
git switch main
git pull --ff-only
git status
git switch --create "$RELEASE_BRANCH"
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

Review the commit and the tag (`git show "v$RELEASE_VERSION"`), then push it and release the
version.

> ⚠️ Take a good review before pushing, and make sure the versions are correct.

Push:

```shell
git push --tags --set-upstream origin HEAD
```

Then release from the "[Release to Maven Central](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/maven-central-release.yml)" action. Click "Run workflow", choose the newly pushed tag, then "run workflow".

> ⚠️ Wait for the release to succeed before moving forward. You can still make changes on the
> release branch before merging to main, in case anything goes wrong.

Once the release is done, you can merge your changes back into main:

```shell
git switch main
git merge --ff-only "$RELEASE_BRANCH"
git push
git push --delete origin "$RELEASE_BRANCH"
```

Then update to the next version:

```shell
# Update version in pom
./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=$NEXT_VERSION

git add pom.xml "**/pom.xml"
git commit --signoff --message "Next development version"
```

Review the commit (`git show`). Push:

```shell
git push
```

Lastly, create release notes.
