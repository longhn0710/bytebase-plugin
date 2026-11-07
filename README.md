# Bytebase

Run Bytebase SQL review and staged database rollouts from Jenkins Pipeline or
Freestyle jobs. The plugin calls Bytebase directly; agents do not need the
Bytebase CLI, Go, or a container runtime.

[Configuration reference](docs/reference.md) · [Pipeline example](examples/Jenkinsfile) ·
[CLI feature mapping](docs/action-migration.md) · [Publishing](docs/publishing.md)

The plugin is being prepared for Jenkins hosting and is not yet available from
the Jenkins Update Center. The canonical repository URL in the POM and CODEOWNERS
team are the requested Jenkins hosting targets; hosting has not yet been granted.

## Configure a connection

1. Create a service account in Bytebase and grant it access to the projects it will
   manage. Obtain its **Service Key**.
2. In Jenkins Credentials, create **Username with password**:
   - **ID:** `bytebase-ci`
   - **Username:** the Bytebase service account email, such as `ci@service.bytebase.com`
   - **Password:** the service account's Service Key
3. Under **Manage Jenkins → System → Bytebase**, add a connection:
   - **Connection name:** `bytebase-default`
   - **Bytebase base URL:** `https://bytebase.example.com`
   - **Service account credential:** `bytebase-ci`
4. Select **Test connection**, then save.

One connection can serve multiple jobs. To use another account for a team or group
of projects, add another named connection; multiple connections may use the same
Bytebase URL. Pipelines select a connection through `serverName`. The plugin does
not select accounts by project or fall back to a different account after failure.
Named connections do not provide team isolation within Jenkins.

A successful connection test confirms authentication and connectivity. Bytebase
still checks the account's permissions for each operation. For example, its GitOps
Service Agent role can review and roll out SQL but does not grant every metadata
read permission needed by the optional Oracle preflight inspection.

Jenkins Configuration as Code configuration, when JCasC is installed:

```yaml
unclassified:
  bytebaseConfiguration:
    servers:
      - name: bytebase-default
        url: https://bytebase.example.com
        credentialsId: bytebase-ci
```

Credentials stay on the controller and are not passed to agent commands. A
legacy Secret text credential containing an existing access token is also
supported; the plugin does not renew static tokens. The advanced `credentialsId`
step option overrides the connection credential for that operation.

## Review SQL in Pipeline

```groovy
pipeline {
  agent any
  stages {
    stage('SQL review') {
      steps {
        bytebase serverName: 'bytebase-default', command: 'check',
          project: 'projects/example',
          targets: 'instances/example/databases/example',
          filePattern: 'migrations/**/*.sql',
          checkRelease: 'FAIL_ON_ERROR', output: 'reports/review.json'
      }
      post {
        always {
          archiveArtifacts artifacts: 'reports/review.json', allowEmptyArchive: true
        }
      }
    }
  }
}
```

The step returns `void`. Results are available in the output JSON and in the
build's **Bytebase results** page, including when the quality gate fails.
`checkRelease` accepts `SKIP`, `FAIL_ON_ERROR`, and `FAIL_ON_WARNING`; set it
explicitly in your pipeline. Its default is `SKIP`.

Freestyle jobs use **Add build step → Bytebase: check SQL, rollout or inspect**.
Pipeline Syntax / Snippet Generator also exposes the step options.

## Roll out an approved plan

[examples/Jenkinsfile](examples/Jenkinsfile) shows SQL review, preparation,
Jenkins approval, and execution of the prepared plan. Its `readJSON` call requires
Pipeline Utility Steps.

- `command: 'rollout'` with SQL files creates a release, plan, and rollout.
- Without `targetStage`, the plugin does not start tasks. **Bytebase auto-rollout
  policies can still execute them independently.** Confirm the server policies
  before using a Jenkins approval gate.
- With `targetStage: 'environments/test'`, the plugin runs stages in order through
  the selected environment, waits, then stops before later stages.
- To run an existing plan, supply `plan: 'projects/example/plans/123'`. The plan
  must belong to `project`; files and targets are ignored in this mode.
- Missing environments, failed or canceled tasks, and timeout fail the build.
  Aborting requests task-run cancellation; cancellation is best effort and does
  not roll back SQL already executed.

Bytebase approvals and policies remain enforced by the server. The plugin does
not create or approve Bytebase issues on behalf of a user. Mutations are not
replayed after transient network failures; inspect the saved resource names
before retrying.

## Inputs and inspection

SQL paths are workspace-relative Ant globs. Versioned migrations use the version
at the start of the filename, for example `V3__add_column.sql` → `3`. Targets are
comma/whitespace-separated database resource names, or one project database group.
Do not mix groups with individual databases. Declarative mode is experimental and
subject to the Bytebase engine's restrictions.

`command: 'inspect'` performs read-only checks:

- `inspectionMode: 'preflight'`: validate one Oracle target's project, environment,
  active/synchronized state, and expected ADMIN data source host/port/service.
- `inspectionMode: 'ready'`: validate a plan's single stage and target, with tasks
  still `NOT_STARTED` or `PENDING`.
- `inspectionMode: 'done'`: require the expected tasks to be `DONE`; `SKIPPED` is
  rejected.

The Oracle preflight is optional and specific to that inspection workflow;
review and rollout support the engines exposed by Bytebase. See the
[configuration reference](docs/reference.md#step-options) for all step parameters and defaults.

## Operation and limits

SQL is read from agents using Jenkins FilePath. Each operation allows up to 1,000
files and 16 MiB of SQL; responses are also bounded to 16 MiB. Absolute paths,
traversal, and symlinks outside the workspace are rejected. Use trusted workspaces
for deployment jobs; concurrent hostile filesystem changes are outside that guard.

The step runs synchronously and **does not resume across controller restarts**.
Inspect Bytebase state after an interrupted build and use the existing plan when
recovering. Do not recreate a release blindly. The plugin does not delete created
Bytebase resources or post SCM comments automatically.

## Development

Build and run tests with `mvn -B -ntp clean verify`; the artifact is
`target/bytebase.hpi`. The Linux CI matrix and Jenkins baseline are declared in
`Jenkinsfile`, the GitHub workflow, and `pom.xml`. The root `Jenkinsfile` uses the Jenkins project's
`buildPlugin` library. GitHub Actions provide build verification and Jenkins
Security Scan. Examples are consumer pipelines, separate from repository CI.

API compatibility has been exercised against Bytebase 3.22.1. Harness tests cover
credentials, connection selection, quality gates, resource creation, staged
execution, interruption, and inspection. [Lab evidence](docs/live-delivery.md)
records the versions tested in the earlier PoC; those results do not replace
public CI or the Jenkins Hosting Team's security review.

Release builds are prepared for JEP-229 through GitHub Actions, with manual
`workflow_dispatch` only. A release requires approved hosting, release permissions,
and a successful Jenkins CI build; the workflow cannot publish from the personal
repository. See [publishing](docs/publishing.md) for the submission and release steps.

## Support and license

Report reproducible non-security issues in the repository's GitHub Issues.
Report suspected vulnerabilities privately through the
[Jenkins security reporting process](https://www.jenkins.io/security/reporting/).

[MIT license](LICENSE).
