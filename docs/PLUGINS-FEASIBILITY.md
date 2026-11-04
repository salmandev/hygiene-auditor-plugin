# Plugins: what can be known, and from where

Investigated on Jenkins **2.555.3** by planting a job configuration that names a class from a plugin that is not
installed, reloading, and inspecting what Jenkins records. Re-run this when the baseline changes.

| # | Source | Available? | What it tells us |
|---|---|---|---|
| 1 | `OldDataMonitor.getData()` (core) | ✅ | Each affected `Saveable` plus an explanation. For missing classes: `CannotResolveClassException: com.example.gone.GoneBuilder`. Data from a *newer* version has no explanation text. |
| 2 | `plugin="name@version"` attribute that Jenkins writes on serialised configuration | ✅ | The plugin that wrote an element, with no class loading. Present in item `config.xml` (a pipeline job carried `workflow-cps` and `workflow-job`). The stamp of an unreadable element **survives on disk** until the item is saved, so a missing plugin can be named. |
| 3 | Root settings files named after a class (`hudson.plugins.git.GitTool.xml`) | ✅ | That class's plugin saved settings. File-name rule is tested; class → plugin attribution uses `PluginManager.whichPlugin` and is **not provable in a test environment** whose classpath also contains the plugin jars (classes then load from the application loader). |
| 4 | Flow-graph step descriptors of a run → `PluginManager.whichPlugin(descriptor.getClass())` | ✅ | The plugin that supplied each step the run executed (`echo`, `sleep` → `workflow-basic-steps`). Steps defined by core map to no plugin. |
| 5 | `PluginWrapper` (`getDependents`, `isBundled`, `isEnabled`) | ✅ | Whether another plugin needs it (most installed plugins are libraries), whether it ships with Jenkins, whether it is disabled. |

## Consequences

* "Orphaned data" is Jenkins' own diagnosis, but it also results from a plugin *upgrade* that removed a class, and the
  list only covers data loaded since the last restart. The finding says both.
* "No observable usage" can only ever be a weak signal: there is **no** source that says a plugin is unused. A plugin
  that contributes an extension needing no configuration, a build-time tool, a theme or a UI helper leaves no trace.
  The collector therefore caps confidence at LOW and severity at INFO by construction (its only positive evidence weighs
  less than the MEDIUM threshold), excludes libraries, bundled and disabled plugins, and says what it did not check.
* Only the **latest run** of each pipeline job contributes steps, and Jenkinsfile text is not searched.
