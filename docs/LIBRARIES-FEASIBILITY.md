# Shared libraries: what can be known, and from where

Investigated on Jenkins **2.555.3** with **Pipeline: Shared Groovy Libraries (`pipeline-groovy-lib`) 806** and
Pipeline: Groovy (`workflow-cps`) 4376, by running a real pipeline that loads a real git-backed library
(`vars/deploy.groovy`, `vars/neverCalled.groovy`, `src/org/acme/Util.groovy`, `src/org/acme/Dead.groovy`) and inspecting
everything Jenkins left behind. Re-run this experiment when upgrading the baseline: the collector depends on it.

## Evidence sources

| # | Source | Available? | What it tells us |
|---|---|---|---|
| 1 | Configured libraries: `GlobalLibraries.get().getLibraries()`, and the `FolderLibraries` property of each folder | ✅ | Name, default version, implicit flag, and the **scope** (Jenkins-wide or one folder subtree) |
| 2 | `LibrariesAction` on a run (`getLibraries()` → `LibraryRecord`) | ✅ | Which libraries that run **actually loaded**, at which **resolved version**, and the **names of the global variables** the library defines (`getVariables()`) |
| 3 | The checkout inside the build directory: `<build>/libs/<hash>/vars/*.groovy`, `src/**/*.groovy` | ✅ | The exact `vars` and `src` files of the version that run used; also their source text |
| 4 | Pipeline source of a run: `CpsFlowExecution#getScript()` | ✅ | The Jenkinsfile / inline script that ran (already used by the credentials collector) |
| 5 | `CpsFlowExecution#getLoadedScripts()` | ❌ **empty for libraries** | Only scripts loaded with the `load` step. Library source is *not* here (our spike: `[]`) |
| 6 | Flow graph nodes (`DepthFirstScanner`) | ⚠️ **steps only** | A call to a global variable (`deploy('prod')`) produces **no node**. Only the steps *inside* it (`echo`) appear. |
| 7 | Static scan of scripts for `@Library(...)` and `library ...` | ✅ | Which library names (and versions) scripts ask for; `${…}` and variables are *dynamic* |

## What follows from that

* **Runtime evidence for a library**: yes, at library granularity (source 2): "the latest run of N pipeline jobs did or
  did not load library X".
* **Runtime evidence for an individual global variable or class: not feasible.** Sources 5 and 6 do not exist for them,
  so the originally planned "steps used, recovered from the flow graph" cannot identify a `vars` call or a `src` class.
  We substitute **static evidence on what actually ran**: the identifiers in the pipeline source of the latest run of
  each job (source 4) *and* in the library source those runs checked out (source 3), so a variable called only from
  another library file still counts as used.
* **An inventory exists only for libraries that some recent run loaded.** A library that no run loaded cannot be
  analysed for unused variables (we do not know its contents without checking it out ourselves, which we deliberately
  do not do: that would run SCM operations from a scan). It can still be reported as *unreferenced*.
* **Version granularity.** Runs record the resolved version, so evidence lists the versions seen. There is no separate
  "unused version" finding: a configured default version that nobody uses is reported in the library's evidence.

## Known limits (carried into every finding as "not checked")

* Only the **latest run of each pipeline job** is inspected. A library used only by older runs, by Jenkinsfiles of
  branches never built, or by jobs outside Jenkins looks unused.
* Pipeline jobs that have **never run** have no record; they lower confidence for libraries in their scope.
* **Dynamic use** (`library(someVar)`, `evaluate(...)`, `this."${name}"()`, `Class.forName`) cannot be resolved; it is
  counted and lowers confidence.
* Identifiers are matched as **words**, so a variable whose name is a common word is more likely to look used than it
  is. This errs towards "used".
* Library attribution when two scopes define the same name: the innermost folder scope wins (as Jenkins resolves it).
* Classes are keyed by file (`src/org/acme/Dead.groovy` → `org.acme.Dead`); extra classes in one file and nested
  classes are not tracked.
