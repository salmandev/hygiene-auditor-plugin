// Step 1: plant a job whose stored configuration names a plugin that is not installed, then reload from disk so that
// Jenkins itself reports "unreadable data" (this is what the Plugins collector picks up).
import jenkins.model.Jenkins
import hudson.model.*
import com.cloudbees.hudson.plugins.folder.Folder

def j = Jenkins.get()
def legacy = j.getItem("legacy") ?: j.createProject(Folder, "legacy")
if (legacy.getItem("uses-removed-plugin") == null) {
    def p = legacy.createProject(FreeStyleProject, "uses-removed-plugin")
    def f = p.configFile.file
    f.text = f.text.replace("<builders/>",
        '<builders><com.example.sonar.SonarBuilder plugin="sonar-legacy@2.1"><server>old</server></com.example.sonar.SonarBuilder></builders>')
}
println "orphan planted; reloading configuration from disk"
Thread.start { Thread.sleep(500); Jenkins.get().reload() }
