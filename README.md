# Hygiene Auditor Plugin

A **read-only, evidence-based** auditor for long-lived Jenkins controllers. It reports what is *likely* unused —
credentials nobody references, jobs that have not run in a year, long-offline agents — together with **how confident
it is and why**. It never deletes or changes anything. At most it writes a removal plan (a script or checklist) that
an administrator reviews and runs by hand.

> **Never claim certainty.** Every finding carries a category, a severity, a confidence level (HIGH / MEDIUM / LOW)
> and a list of evidence, including the things that were *not* checked.

## Status

This is an early release. What exists today:

| Area | Status |
|---|---|
| Jobs and folders: never built, stale, long-disabled | ✅ |
| Agents: permanent agents offline for a long time | ✅ |
| Credentials: unused, from fingerprints plus a static scan | ✅ |
| Dashboard, finding details, per-folder page, trend chart | ✅ |
| Scheduled / manual / Pipeline-step scans, cancel, progress | ✅ |
| Ignore rules, thresholds, retention (UI and JCasC) | ✅ |
| REST API, JSON and CSV export | ✅ |
| Removal-plan generator (Groovy, shell, checklist) | ✅ |
| Notifications: webhook and email (Mailer) | ✅ |
| Shared libraries: unreferenced libraries, unused `vars/` variables and `src` classes | ✅ (see limits) |
| Plugin collector (orphaned data, plugins without observable use) | ⏳ not yet |
| Never-used agents, unrequested labels, unprovisioned cloud templates | ⏳ not yet |
| Orphaned multibranch branches, build/workspace footprint | ⏳ not yet |
| Prometheus metrics (optional Metrics plugin) | ⏳ not yet |
| User-scoped credentials | ⏳ not yet |

Not yet built means not scanned: the dashboard only ever reports on the collectors listed as ✅.

## Requirements

* Jenkins **2.555.3** or newer (the build baseline), Java 21.
* Optional plugins, each enabling one feature: **Credentials** (credentials collector), **Folders** (per-folder page),
  **Pipeline** (`hygieneScan` step, and reading the Jenkinsfile of recent runs), **Mailer** (email notifications),
  **Configuration as Code** (JCasC).

## What the confidence levels mean

Confidence answers one question: *how likely is it that the subject is really unused, given what the auditor could
see?* It is **computed from evidence, never assigned by hand**.

Each piece of evidence has a weight. The weights are summed; then each *caveat* lowers the result by one level.

| Sum of weights | Level |
|---|---|
| 70 or more | **HIGH** |
| 40 to 69 | **MEDIUM** |
| below 40 | **LOW** |

| Finding | Evidence and weights | Caveats (each lowers one level) |
|---|---|---|
| `job.never_built` | never built **35**; config unchanged for ≥ grace period **30**; … and for over a year **+15** | — |
| `job.stale` | last build older than the threshold **50**; older than twice the threshold **+20** | build records were discarded |
| `job.disabled` | disabled and unchanged for the threshold **50**; never built or last build older **+20** | — |
| `agent.offline` | offline for the threshold **60**; for three times the threshold **+15** | on-demand / scheduled retention strategy (being offline is normal) |
| `library.unreferenced` | no run of any in-scope pipeline job loaded it **35**; no script names it **25**; ≥ 20 jobs inspected **+15** | dynamic `library(...)` requests; in-scope jobs that never ran; unreadable items |
| `library.unused_variable` | no inspected script and no other library file mentions it **50**; ≥ 10 jobs inspected **+15** | dynamic invocation (`evaluate`, computed method names); unreadable items |
| `library.unused_class` | as above **45** (+15) | dynamic invocation; a wildcard import of its package; unreadable items |
| `credential.unused` | no reference found **45**; fingerprint says last used long ago **+30**, or tracking is on and records no use **+20** | dynamic references in scope; sources that could not be read |

In practice: HIGH needs *independent* evidence (for example a fingerprint that shows the credential was last used long
ago, or a job that has not been built for two years). A single weak signal tops out at MEDIUM. Every finding also lists
what was **not** checked, so a reader knows the limits of that particular verdict.

Severity is separate from confidence: it says how much attention the subject deserves if the verdict is right. Unused
credentials are MEDIUM (HIGH when confidence is HIGH); stale jobs and agents are LOW.

## Limits of static analysis

The credentials collector reads configuration and scripts as **text**. It never runs them. That makes it safe, and
it also means it cannot know everything.

* **Dynamic references.** `credentialsId: params.CRED`, `"deploy-${env.STAGE}"`, `credentials(someVariable)` and
  credential *parameters* cannot be resolved. They are counted and **lower the confidence of every credential that
  could be affected** — those owned by the same folder or an ancestor of the job that contains them.
* **Any string equal to an id counts as a use**, even inside a comment. This errs towards "used".
* **Not scanned:** user-scoped credentials, triple-quoted strings, ids built by concatenation,
  remote JCasC sources, and anything outside Jenkins that uses a credential by id.
* **Fingerprints.** A credential that was used recently is never reported, whatever the static scan found. If
  fingerprint tracking is disabled (`-DCredentialsProvider.fingerprintEnabled=false`) there is no usage history and
  the finding says so. If the tracking history cannot be trusted (too many jobs, or a discarded build record that could
  hide a recent use), the collector makes **no** usage claim at all.
* **Incomplete scans emit nothing.** If a scan is cut short by a time or item budget, the credentials collector
  reports nothing rather than claiming "unused" from partial data, and older findings are not auto-resolved.
* **Age is approximate.** Job age and "disabled since" come from the `config.xml` modification time; agent offline time
  comes from Jenkins' offline cause and **restarts when the controller restarts**. Both can only under-report.

### Shared libraries: what is, and is not, knowable

Jenkins records, for each run, which libraries it loaded (with the resolved version and the global variables each defines)
and keeps the checked-out library source in the build directory. The collector uses exactly that, for the **latest run of
each pipeline job**: a library no such run loaded and no script names is `library.unreferenced`; for libraries that were
loaded, a `vars/` variable or `src` class that no inspected script and no other library file mentions is reported.

* **No runtime evidence exists for an individual variable or class.** A call to a global variable leaves no node in the
  flow graph (only the steps inside it do), so those verdicts are *static, over the code that actually ran*. Because of
  that a variable or class tops out at MEDIUM; only an unreferenced library can reach HIGH.
* A library can only be analysed for unused content if some recent run loaded it. The scan never checks out a library itself.
* Matching is by word: a variable with a very common name is more likely to look used than it is. Names that appear only
  as an `@Library(...)` argument or in a `package`/qualified class name do not count as uses.
* Library findings are visible to administrators only. Details: [docs/LIBRARIES-FEASIBILITY.md](docs/LIBRARIES-FEASIBILITY.md).

## Using it

**Manage Jenkins → Hygiene Auditor** shows the dashboard: counts by category, severity, confidence and status; a
filterable, sortable findings table; per-finding evidence with the weight of each observation; and, for
administrators, a trend of open findings per scan. People who hold *Hygiene Auditor/View* on a folder (and nowhere
else) get a **Hygiene Auditor** tab on that folder, limited to its own findings.

Findings can be **acknowledged**, or **ignored with a reason** and an optional snooze. A snooze that expires reopens
the finding. A finding that is no longer observed becomes **resolved** and is removed after the retention period.

> Screenshots: `docs/images/dashboard.png`, `finding-detail.png`, `folder-page.png`, `settings.png` — *placeholders,
> to be added before the first release.*

### Permissions

| Permission | Allows | Scope |
|---|---|---|
| `Hygiene Auditor/View` | See findings, limited to what the user may otherwise see | Jenkins or a folder |
| `Hygiene Auditor/Scan` | Also start and cancel scans; acknowledge or ignore findings | Jenkins |
| `Hygiene Auditor/Configure` | Also change settings | Jenkins |

Each implies the one above it in this list, and `Administer` implies all of them. The scan itself runs as `SYSTEM`; **every
read is then filtered for the viewer**:

* job findings need `Item.READ` on the job *and* View on it (or an enclosing folder);
* credential findings need `Credentials/View` on the owning folder or Jenkins, plus View;
* agent, library and plugin findings, and findings about deleted items, need `Administer`;
* a finding you may not see answers 404, exactly like one that does not exist.

### Pipeline

```groovy
def r = hygieneScan(categories: ['CREDENTIALS', 'JOBS'], failOnHigh: true)
echo "open: ${r.open}, high: ${r.high}, new: ${r.created}"
```

`categories` (default: all) and `failOnHigh` (default `false`; fails the build if any open HIGH-severity finding
exists in the scanned categories). The step needs `Hygiene Auditor/Scan` for the build's identity, waits for a scan
that is already running, stops its own scan if the build is aborted, and returns only counts.

### REST

All under `/manage/hygiene-auditor/`; standard `tree=` and `depth=` parameters work.

| Request | Returns |
|---|---|
| `GET api/json` | counts by category/severity/confidence/status, scan state |
| `GET findings/api/json?category=&status=&minConfidence=&minSeverity=&folder=&limit=&offset=` | filtered, paged list (default `status=OPEN`, `limit=500`, max 5000; `status=all` for everything) |
| `GET finding/<id>/api/json` | one finding with its evidence |
| `GET exportJson?…`, `GET exportCsv?…` | every matching finding as a download (same filters, no paging) |
| `GET removalPlan?format=groovy\|cli\|checklist&minConfidence=HIGH` | a removal plan (see below) |
| `POST scan?categories=`, `POST cancel`, `POST setStatus?id=&status=&reason=&snoozeDays=` | actions; need Scan and a CSRF crumb |

Folder users have the same read endpoints under `<folder>/hygiene-auditor/` (always confined to that folder).

CSV cells that begin with `=`, `+`, `-`, `@` or a tab are prefixed with an apostrophe so spreadsheets do not execute them.

### Removal plan

`removalPlan` writes text for **you** to read and run. The plugin never executes it.

* **Groovy** (for the Script Console): a fixed program plus a data list of string literals. Finding data can only ever
  appear inside a literal, never as code. It starts with `DRY_RUN = true`, and even when switched off it refuses to
  touch a job built, or an agent brought online, *after the plan was generated*. Running it twice is harmless.
* **Shell**: `jenkins-cli` commands, all commented out. Values are single-quoted; names containing control characters
  are skipped. Credential commands are generated for the root store only — confirm the store ids in your Credentials
  plugin version first.
* **Checklist** (Markdown): backup steps, evidence, suggested action.

Only OPEN findings at or above the confidence floor (default **HIGH**) are included. Always take a backup first, and for
credentials revoke the secret at its source *before* deleting it.

### Notifications

After a scan, findings that are **new** (first seen, or back after being resolved or after a snooze) and at least
`notifyMinSeverity` (default HIGH) are sent as **one batch**. A finding that stays open is announced once.

* **Webhook**: JSON `POST`; at most 50 findings; no evidence text. With a secret, the body is signed
  (`X-Hygiene-Signature: sha256=<hex HMAC-SHA256>`). It honours Jenkins' proxy settings, uses 5 s / 10 s timeouts and
  **does not follow redirects**. Only `http(s)` URLs without embedded credentials are accepted.
* **Email**: one message through the Mailer plugin's SMTP settings; no evidence in the body.

Recipients of notifications see instance-wide results: they are not filtered per user. A failing channel is logged
(never with the URL or any secret) and never affects the scan.

### Configuration as Code

```yaml
unclassified:
  hygieneAuditor:
    scanCron: "H 3 * * 0"
    disabledCollectors: ["agents"]   # ids: jobs, agents, credentials, libraries
    staleJobDays: 365
    ignoreRules:
    - category: "CREDENTIALS"
      subject: "^prod-.*"
      reason: "Managed by the platform team"
    - folder: "sandbox"
```

The complete, annotated example is [`docs/examples/jcasc.yaml`](docs/examples/jcasc.yaml); a test loads it on every
build. Invalid values (a negative threshold, a bad regex, a rule that would match everything, a non-http webhook) are
rejected with a clear message and leave the previous configuration in place.

An **ignore rule** matches when *every* field you set matches: `category`, `type` (`*` wildcard), `subject` (a regex,
matched against a credential's id, otherwise the subject name), `folder` (the folder and everything below it). Matching
findings are never recorded. A regex that takes longer than 50 ms to evaluate counts as "no match" and is logged.

## Data and privacy

* Findings are stored in `$JENKINS_HOME/hygiene-auditor/` (`findings.json`, `history.json`): versioned, written
  atomically, bounded by the retention settings. An unreadable file is moved aside, never overwritten; a file from a
  newer plugin version is refused and left untouched.
* **Secret values are never read or stored.** Only credential ids, types, scopes and fingerprint timestamps are used.
  Evidence, reasons and error messages additionally pass through a redactor, and tests assert that known secrets, usernames
  and descriptions appear in no stored file, REST response, page, export or log.
* Scanned content (job configuration, Jenkinsfiles, JCasC files) is treated as **untrusted input**: XML is parsed with
  DTDs and external entities disabled; scripts and YAML are matched with bounded patterns and never evaluated.

## Large controllers

Scans iterate lazily and read at most one build record per job (never full histories). Each collector has a time, item
and finding budget; the scan runs on one dedicated minimum-priority thread, pauses briefly every 100 items, uses no
build executor, supports cancel, and reports progress. Only one scan runs at a time.

## Building and contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: [SECURITY.md](SECURITY.md). Releasing and hosting:
[docs/RELEASING.md](docs/RELEASING.md).

```
export JAVA_HOME=<a JDK 21>
mvn spotless:apply
mvn clean verify
mvn hpi:run
```

## License

[MIT](LICENSE)
