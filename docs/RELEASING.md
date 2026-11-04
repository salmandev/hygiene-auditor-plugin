# Hosting and releasing checklist

Sources: the Jenkins project pages *Guide to Plugin Hosting*
(<https://www.jenkins.io/doc/developer/publishing/requesting-hosting/>) and *Setting up plugin releases through GitHub*
(<https://www.jenkins.io/doc/developer/publishing/releasing-cd/>), and the README of
<https://github.com/jenkins-infra/repository-permissions-updater>, read when this file was written. Those processes
change: **re-read them before you start** and trust them over this file.

Coordinates used here: `groupId` **io.jenkins.plugins**, `artifactId` **hygiene-auditor**, repository
**hygiene-auditor-plugin**, Maven path `io/jenkins/plugins/hygiene-auditor`.

## 0. Before asking for hosting

- [ ] Put the code in a **public GitHub repository** (it is not under git yet). Suggested name: `hygiene-auditor-plugin`.
- [ ] Read the preparation and style-guide pages linked from the hosting guide (naming conventions).
- [ ] Replace the placeholder copyright line in `LICENSE`. Screenshots are in `docs/images/` (demo data); refresh them if the UI changes.
- [ ] Decide the security contact/process described in `SECURITY.md` and confirm the link is still correct.
- [ ] `mvn clean verify` is green on a JDK 21 and the `Jenkinsfile` builds on ci.jenkins.io-style agents.
- [ ] Review the README **Status** table: it must list exactly what ships.
- [ ] Open items worth deciding first (see the end of this file).

## 1. Run the official pre-check

From a clone of `repository-permissions-updater`:

```
./bin/check-hosting.sh \
  --repository-url https://github.com/<you>/hygiene-auditor-plugin \
  --new-repository-name hygiene-auditor-plugin \
  --github-user <your-github-user> \
  --jenkins-user <your-jenkins-account>
```

It needs a recent JDK (the README currently says 25+), Maven and a GitHub token. Add `--no-cd` only if you will not use
GitHub Actions releases. Fix everything it reports; the Hosting Checker repeats these checks on your request.

## 2. File the hosting request

- [ ] Log in once to Artifactory and Jira with your Jenkins community account (needed for upload permissions).
- [ ] Open an issue in `jenkins-infra/repository-permissions-updater` with the template **1-hosting-request.yml**
      (<https://github.com/jenkins-infra/repository-permissions-updater/issues/new?template=1-hosting-request.yml>).
- [ ] Answer review comments from the Hosting team (usually within a few days).
- [ ] When approved the repository is forked into the `jenkinsci` organisation and you are invited. Delete your original
      repository and re-fork from `jenkinsci` if you want a fork (or ask for a manual transfer in the request).
- [ ] A pull request adding release permissions is normally created automatically. If not, create it as in step 4.

## 3. Prepare continuous delivery (JEP-229) in the plugin repository

Do this on a **branch with a pull request**. Do not push straight to the default branch.

- [ ] `pom.xml`: `<groupId>io.jenkins.plugins</groupId>`, `<version>${changelist}</version>`, and in `<properties>`
      `<changelist>999999-SNAPSHOT</changelist>`. (Already the case. Released versions look like `123.vabcdef456789`.)
- [ ] `.mvn/maven.config`, exactly:
      ```
      -Pmight-produce-incrementals
      -Dchangelist.format=%d.v%s
      ```
- [ ] `.mvn/extensions.xml`: copy it from a freshly generated Jenkins plugin archetype project. (The CD page expects it
      to exist but does not show its content, so it is not reproduced here.) It uses the git-changelist Maven extension,
      which **requires the project to be a git checkout**: that is why these two files are not committed yet.
- [ ] Create the workflow from the official template:
      ```
      mkdir -p .github/workflows
      curl --silent --show-error --location --output .github/workflows/cd.yaml \
        https://raw.githubusercontent.com/jenkinsci/.github/master/workflow-templates/cd.yaml
      ```
      Remove the `check_run:` trigger if you want manual releases only (keep `workflow_dispatch:`).
- [ ] **Do not use Release Drafter** with CD: release notes come from PR titles and labels. If you add its config for any
      reason, remove `.github/release-drafter.y*ml` and `.github/workflows/release-drafter.y*ml` again.
- [ ] `.github/dependabot.yml` exists. Add a `github-actions` ecosystem entry (the CD page suggests monthly) if it is
      not there; skip Dependabot if you use Renovate.
- [ ] Check the computed version: `mvn validate help:evaluate -Dexpression=project.version -Dset.changelist -Dignore.dirt`.
- [ ] Open the PR, give it a category label (`enhancement`, `bug`, `developer`, `breaking`, …) and merge **after** the
      Hosting team has reviewed it.

## 4. Release permissions: `repository-permissions-updater`

Add `permissions/plugin-hygiene-auditor.yml` in a pull request:

```yaml
---
name: "hygiene-auditor"
github: "jenkinsci/hygiene-auditor-plugin"
paths:
  - "io/jenkins/plugins/hygiene-auditor"
developers:
  - "<your-jenkins-ldap-username>"
cd:
  enabled: true
```

- `name` is the artifactId; `paths` is the groupId with slashes, then the artifactId (derived from the README's rule —
  double-check it against the Hosting Checker's output).
- `cd.enabled: true` needs at least one developer listed. By default CD is exclusive: the listed accounts are *not*
  granted manual upload, but every committer to the repository can release by merging PRs. Add `exclusive: false` if you
  also want manual uploads.
- Link the plugin PR in the permissions PR. Show commit access (or have an existing committer approve).
- [ ] After it merges, watch `https://github.com/jenkinsci/hygiene-auditor-plugin/settings/secrets/actions` until
      `MAVEN_TOKEN` and `MAVEN_USERNAME` appear.

## 5. Releasing

- Automatic: a successful default-branch build by ci.jenkins.io releases if at least one merged PR carries a
  user-interesting label (`enhancement`, `bug`, …; not `chore`).
- Manual: `https://github.com/jenkinsci/hygiene-auditor-plugin/actions/workflows/cd.yaml` → *Run workflow* (needs a
  passing Jenkins check on the commit).
- Incompatible change (for example renaming a persisted field without migration): set `<hpi.compatibleSinceVersion>` and
  label the PR `breaking`.
- Errors: 401 = expired secrets (open a helpdesk issue); 403 = wrong upload path or the version already exists.

## 6. After the first release

- [ ] Categorise the plugin and check the generated page on plugins.jenkins.io (it renders the README).
- [ ] Add the GitHub topic `jenkins-plugin` and enable issues (or choose Jira and set up the autolink).
- [ ] Join the Jenkins developer mailing list.

## Things to decide or verify before going public

These are honest gaps, not formalities.

1. **Scope.** Never-used agents, label and cloud
   analysis, orphaned multibranch branches, workspace footprint and Prometheus metrics are not built (README, *Status*).
   Decide whether a first release without them is acceptable, and keep the README table accurate.
2. **Unverified on a real fleet:** the fingerprint-tracking switch name
   (`-DCredentialsProvider.fingerprintEnabled`, read from the plugin's bytecode), and the CLI credential store ids in the
   shell removal plan. Test both against your real Credentials plugin version.
3. **Visual review.** The pages were checked in Chrome (light, dark, 390px wide) against the Docker demo only; check them on your Jenkins theme.
4. **The settings page** requires Jenkins' `MANAGE` permission, not the plugin's `Configure` permission (a
   `GlobalConfiguration` limitation).
5. **Authorize Project:** the `hygieneScan` step checks `Scan` for the build identity; it has not been tested with a
   non-SYSTEM build identity.
6. **Evidence text is English.** It is stored as text at scan time, so only labels and suggested actions are translated.
7. **License holder** in `LICENSE`, and the plugin's `<developers>` and `<scm>` once the real repository exists.
