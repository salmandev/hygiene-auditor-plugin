// Step 2: the rest of the sample data. DEMO ONLY: some timestamps are rewritten so that "400 days ago" exists
// immediately. On a real controller time does this for you.
import jenkins.model.Jenkins
import hudson.model.*
import hudson.slaves.*
import java.nio.file.*
import com.cloudbees.hudson.plugins.folder.Folder
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.libs.*
import jenkins.plugins.git.GitSCMSource
import io.jenkins.plugins.hygieneauditor.scan.*
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions
import com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty as FolderMatrix
import org.jenkinsci.plugins.matrixauth.PermissionEntry

def j = Jenkins.get()
final long DAY = 24L * 3600 * 1000
final long NOW = System.currentTimeMillis()

def setField = { obj, String name, value ->
    def c = obj.getClass()
    while (c != null) { try { def f = c.getDeclaredField(name); f.setAccessible(true); f.set(obj, value); return } catch (NoSuchFieldException e) { c = c.superclass } }
    throw new IllegalStateException("no field $name on ${obj.getClass()}")
}
def folder = { String n -> (Folder) (j.getItem(n) ?: j.createProject(Folder, n)) }
def legacy = folder("legacy")
def platform = folder("platform")

// ---- 'lead' may see the legacy folder (jobs, credentials, hygiene findings) and nothing else
if (legacy.getProperties().get(FolderMatrix) == null) {
    def m = new FolderMatrix([])
    [Item.READ, HygieneAuditorPermissions.VIEW, CredentialsProvider.VIEW].each { m.add(it, PermissionEntry.user("lead")) }
    legacy.addProperty(m)
}

// ---- a real git-backed shared library, plus one that nobody uses
def libRoot = new File(j.rootDir, "demo-libs")
def mkRepo = { String name, Map<String, String> files ->
    def dir = new File(libRoot, name)
    if (dir.exists()) return dir
    files.each { path, text -> def f = new File(dir, path); f.parentFile.mkdirs(); f.text = text }
    ["git init -q -b main", "git add -A", "git -c user.email=demo@example.com -c user.name=demo commit -q -m init"].each {
        def p = new ProcessBuilder(it.split(" ") as List).directory(dir).redirectErrorStream(true).start(); p.inputStream.text; p.waitFor() }
    return dir
}
def acme = mkRepo("acme", [
    "vars/deploy.groovy": "def call(String what) { notifyTeam(); echo \"deploying \${what}\" }\n",
    "vars/notifyTeam.groovy": "def call() { echo 'notified' }\n",
    "vars/legacyNotify.groovy": "def call() { echo 'old chat integration' }\n",
    "vars/oldVersionCheck.groovy": "def call() { echo 'unused' }\n",
    "src/org/acme/Util.groovy": "package org.acme\nclass Util implements Serializable { String hi() { 'hi' } }\n",
    "src/org/acme/OldFormatter.groovy": "package org.acme\nclass OldFormatter implements Serializable { }\n"])
def dormant = mkRepo("dormant", ["vars/x.groovy": "def call() { echo 'x' }\n"])
def cfg = { String n, File dir -> def c = new LibraryConfiguration(n, new SCMSourceRetriever(new GitSCMSource(dir.absolutePath))); c.defaultVersion = "main"; c }
GlobalLibraries.get().libraries = [cfg("acme", acme), cfg("dormant", dormant)]

// ---- credentials
def rootStore = CredentialsProvider.lookupStores(j).iterator().next()
def add = { store, String id, String desc ->
    store.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, id, desc, "svc-" + id, "demo-secret-" + id)) }
if (CredentialsProvider.lookupStores(j).iterator().next().getCredentials(Domain.global()).isEmpty()) {
    add(rootStore, "deploy-key", "Production deploy (used by platform/nightly)")
    add(rootStore, "old-ftp-login", "FTP for the retired CMS")
    add(rootStore, "shared-signing-key", "Release signing")
    add(rootStore, "prod-registry-token", "Registry (exempt by an ignore rule)")
}
def legacyStore = CredentialsProvider.lookupStores(legacy).iterator().next()
if (legacyStore.getCredentials(Domain.global()).isEmpty()) { add(legacyStore, "legacy-ssh", "SSH to the old build host") }

// ---- jobs
def pipeline = { parent, String n, String script ->
    def p = parent.getItem(n) ?: parent.createProject(WorkflowJob, n)
    p.definition = new CpsFlowDefinition(script, true); p }
def nightly = pipeline(platform, "nightly", """@Library('acme@main') _
import org.acme.Util
def cfg = [credentialsId: 'deploy-key']
deploy('production')
echo new Util().hi()
""")
def dyn = pipeline(platform, "generic-deploy", "def cfg = [credentialsId: params.CREDENTIAL]\necho 'deploy with a credential chosen at run time'\n")
2.times { nightly.scheduleBuild2(0).get() }
dyn.scheduleBuild2(0).get()

def oldReport = legacy.getItem("old-report") ?: legacy.createProject(FreeStyleProject, "old-report")
def b = oldReport.scheduleBuild2(0).get()
setField(b, "timestamp", NOW - 400 * DAY); setField(b, "startTime", NOW - 400 * DAY); b.save()
// the legacy credential was last used by that long-ago build
def sshCred = legacyStore.getCredentials(Domain.global()).find { it.id == "legacy-ssh" }
CredentialsProvider.track(b, sshCred)
def fp = CredentialsProvider.getFingerprintOf(sshCred); setField(fp, "timestamp", new Date(NOW - 400 * DAY)); fp.save()

def never = legacy.getItem("never-built") ?: legacy.createProject(FreeStyleProject, "never-built")
never.configFile.file.setLastModified(NOW - 420 * DAY)
def retired = legacy.getItem("retired-deploy") ?: legacy.createProject(FreeStyleProject, "retired-deploy")
retired.disable(); retired.configFile.file.setLastModified(NOW - 300 * DAY)

// ---- agents
def agentOffline = { String n, boolean onDemand, int days, String why ->
    if (j.getNode(n) != null) return
    def s = new DumbSlave(n, "/home/jenkins/" + n, new JNLPLauncher(true))
    if (onDemand) s.retentionStrategy = new RetentionStrategy.Demand(1, 1)
    j.addNode(s)
    def cause = new OfflineCause.ByCLI(why)
    setField(cause, "timestamp", NOW - days * DAY)
    j.getComputer(n).setTemporarilyOffline(true, cause)
}
agentOffline("build-agent-07", false, 120, "decommissioned, hardware returned")
agentOffline("edge-agent", true, 60, "scale to zero")

// ---- a trend: a year of weekly scans that settled at today's level (sample history, clearly marked in the README)
def hdir = new File(j.rootDir, "hygiene-auditor"); hdir.mkdirs()
def hf = new File(hdir, "history.json")
if (!hf.exists()) {
    def entries = (0..13).collect { i ->
        def end = new Date(NOW - (14 - i) * 7 * DAY).toInstant().toString()
        int jobs = [2, 3, 3, 5, 6, 8, 9, 9, 8, 7, 6, 5, 4, 4][i], creds = [1, 1, 2, 3, 3, 4, 5, 5, 4, 4, 3, 3, 2, 2][i], agents = [0, 0, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2][i], high = [0, 0, 0, 1, 1, 2, 3, 2, 2, 1, 1, 1, 1, 1][i]
        '{"scanId":"sample-' + i + '","trigger":"scheduled","end":"' + end + '","state":"COMPLETED","openByCategory":{"AGENTS":' + agents + ',"CREDENTIALS":' + creds + ',"JOBS":' + jobs + ',"PLUGINS":0,"SHARED_LIBRARIES":0},"openBySeverity":{"HIGH":' + high + ',"INFO":0,"LOW":' + (jobs + agents) + ',"MEDIUM":' + (creds) + '}}'
    }
    hf.text = '{"schemaVersion":1,"scans":[' + entries.join(",") + ']}'
}

// ---- scan, then record two human decisions
def rec = ScanService.get().start(new ScanRequest([] as Set, "manual")).result().get()
println "scan " + rec.state() + ": " + rec.changes()
def store = FindingStoreHolder.get().store()
def byName = { String n -> store.all().find { it.subject().fullName().endsWith(n) } }
def ack = byName("retired-deploy"); if (ack) store.setStatus(ack.id(), io.jenkins.plugins.hygieneauditor.model.Status.ACKNOWLEDGED, "Kept for audit until Q3", null, "admin", java.time.Instant.now())
def ign = byName("legacyNotify"); if (ign) store.setStatus(ign.id(), io.jenkins.plugins.hygieneauditor.model.Status.IGNORED, "Still used by the on-call tooling", java.time.Instant.now().plusSeconds(30L * 86400), "admin", java.time.Instant.now())
println "open findings: " + store.all().count { it.status().name() == "OPEN" }
