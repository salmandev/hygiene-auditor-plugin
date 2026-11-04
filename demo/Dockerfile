# A throw-away Jenkins with the Hygiene Auditor installed, on the baseline the plugin is built against.
FROM jenkins/jenkins:2.555.3-lts-jdk21

# Plugins the auditor can use (all optional except at runtime for the matching collector), plus a dark theme.
RUN jenkins-plugin-cli --plugins \
    credentials plain-credentials cloudbees-folder workflow-aggregator git mailer \
    configuration-as-code pipeline-groovy-lib matrix-auth dark-theme

# The plugin under test, built with `mvn package` (run-demo.sh copies it here).
COPY --chown=jenkins:jenkins --chmod=644 hygiene-auditor.hpi /usr/share/jenkins/ref/plugins/hygiene-auditor.jpi
COPY --chown=jenkins:jenkins --chmod=644 casc.yaml /var/jenkins_home/casc.yaml

# No setup wizard; JCasC configures everything. Local git checkouts are allowed only so the demo can use a library
# repository that lives inside the container.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc.yaml
