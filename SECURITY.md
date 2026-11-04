# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue or pull request for a suspected vulnerability.

Report it privately by following the Jenkins project's process:
<https://www.jenkins.io/security/#reporting-vulnerabilities>

Include the plugin version, the Jenkins version, what you observed, and how to reproduce it. We will acknowledge
the report, work on a fix with you, and coordinate disclosure through the Jenkins security advisory process.

## What counts

Issues we especially want to hear about:

* a secret value (or a credential's username/description) appearing in a finding, page, REST response, export,
  notification, log or error message;
* a user seeing a finding they should not (job without `Item.READ`, credential without `Credentials/View`, a folder
  they have no access to, or a global finding without `Administer`);
* any way for scanned content (job names, Jenkinsfiles, configuration, JCasC) to become code or a command in a
  generated removal plan, CSV, HTML page, email or webhook;
* XML external entity or entity-expansion problems in the scanners;
* missing CSRF or permission checks on an action;
* a way to make the plugin modify or delete Jenkins data.

## Design guarantees (see the README, "Data and privacy")

The plugin is read-only; it never reads secret values; it treats scanned content as untrusted text; and every read is
filtered for the viewer. These are covered by automated tests, so a regression is a bug we will treat as a security issue.

## Supported versions

Only the latest release receives security fixes.
