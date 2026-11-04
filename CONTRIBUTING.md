# Contributing

Thanks for helping. This plugin makes claims about other people's infrastructure ("this is probably unused"), so the
bar is: **be honest about uncertainty, and never become dangerous.**

## Set up

* JDK **21** (the Jenkins 2.555 baseline needs it to build; the formatter fails on newer JDKs).
* Maven 3.9+.

```
export JAVA_HOME=<a JDK 21>
mvn spotless:apply        # format: palantir-java-format via the parent POM
mvn clean verify          # compiles, tests, SpotBugs (max effort), Spotless check
mvn hpi:run -Dport=8081   # a throw-away Jenkins at http://localhost:8081/jenkins
```

`mvn clean verify` must be green before a pull request. Fix SpotBugs findings; do not suppress them without a comment
explaining why the warning is wrong.

## Ground rules (these are tested; please keep them true)

1. **Read-only.** Nothing in the plugin deletes or modifies Jenkins data. The removal plan is *text*.
2. **No secrets, ever.** Do not call secret accessors. Anything user-visible (evidence, reasons, logs, exceptions) goes
   through `Redactor`. Add a test that plants a recognisable fake secret and asserts it never appears.
3. **Scanned content is hostile.** Never evaluate it. XML: use `ReferenceScanner`-style StAX with DTDs off. Anything
   derived from it that ends up in a script, CSV or shell line must be escaped by the existing helpers, and you must
   *execute* the result in a test, not just look at it.
4. **Every read is filtered for the viewer** (`FindingAccess`). New finding kinds must say who may see them.
5. **Confidence is computed** (`ConfidenceScorer`) from evidence weights and caveats. Do not hard-code a level. Every
   finding must also say what it did *not* check.
6. **Incomplete scan ⇒ no claim.** If you cannot be sure you looked everywhere, say less (emit nothing, return
   `CollectorResult.partial`) rather than guess.
7. **Stable public contracts.** Finding ids, `type` strings, REST field names, persisted JSON and JCasC keys are API.
   Changing them needs a schema migration (`SchemaMigrator`) and a note in the release.

## Adding a collector

Extend `HygieneCollector` and annotate with `@Extension` (third-party plugins can do the same):

* `getId()` is stable and used in configuration; `getCategory()` groups findings.
* Call `ctx.checkpoint()` once per examined item; it enforces time/item budgets and cancellation.
* Emit `FindingCandidate`s via `ctx.emit`. Build the confidence with `ConfidenceScorer.score(evidence, caveats)`.
* Keep references to optional plugins' classes in their own small class, so your collector still loads without them.
* Iterate as SYSTEM (`Items.allItems2(ACL.SYSTEM2, …)`), lazily, and never load full build histories.

Tests belong beside the code: use `CollectorTestSupport` to run the collector through the real `ScanRunner`, and move
time with its `daysFromNow` argument instead of sleeping.

## Tests

JUnit 5 + `JenkinsRule` (`@WithJenkins`). Prefer a real Jenkins over mocks for anything touching Jenkins APIs. Use clearly
fake secrets (`S3cr3t-Hunter2-value`). Run a single class with `mvn test -Dtest=ClassName`.

## Pull requests

* Small and focused; describe the behaviour change and any new limit of the analysis.
* New user-visible strings go in `Messages.properties` or the Jelly `${%…}` mechanism (no parentheses in the key).
* Update the README table of what exists and the confidence tables if weights change.

## Code of conduct

Be kind and assume good faith. The Jenkins project's code of conduct applies.
