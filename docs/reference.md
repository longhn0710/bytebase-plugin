# Configuration and operation reference

The plugin calls Bytebase Connect RPC directly and shares one SimpleBuildStep
implementation across Pipeline and Freestyle. Its API compatibility was exercised
against Bytebase 3.22.1. The current Jenkins baseline and build versions are declared
in `pom.xml`; the 0.2.x artifacts in the lab reports are historical PoC versions.

See [README](../README.md) for setup and a minimal review pipeline,
[feature mapping](action-migration.md) for CLI behavior, and
[lab evidence](live-delivery.md) for the version-specific rehearsal results.

## Service account and named connections

Create a Jenkins **Username with password** credential. Username is the Bytebase
service account email; Password is its Service Key. For example, the lab uses
credential `bytebase-jenkins-sa` for `jenkins@service.bytebase.com`.

Under **Manage Jenkins → System → Bytebase → Connections**, give the connection a
name, Bytebase base URL, and credential ID. Select **Test connection**, then save.
The test verifies authentication/connectivity; operations still require permission
on the selected Bytebase project. Jenkins API credentials are separate from these
Bytebase credentials. Do not put a Service Key in a Jenkinsfile or artifact, or
reset it merely to rename a connection or upgrade the plugin.

| Connection | Credential | Account access in Bytebase |
| --- | --- | --- |
| `bytebase-default` | `bytebase-jenkins-sa` | Shared CI projects |
| `bytebase-team-a` | `bytebase-team-a-sa` | Team A projects |
| `bytebase-team-b` | `bytebase-team-b-sa` | Team B projects |

Several connections may use the same URL. Pipelines choose an execution identity
with `serverName`; there is no project-based account routing or credential fallback.
These System connections do not provide team isolation inside Jenkins.

An advanced `credentialsId` override remains available. A legacy Secret text
credential may hold an existing access token; static tokens are not renewed.
Service account login refreshes once after HTTP 401. TLS verification is enabled;
private CAs must be trusted by the controller JVM. HTTP connections honor the shared
Jenkins controller network settings; there are no per-connection proxy or custom
header fields.

With Configuration as Code installed:

```yaml
unclassified:
  bytebaseConfiguration:
    servers:
      - name: bytebase-default
        url: https://bytebase.example.com
        credentialsId: bytebase-jenkins-sa
```

## Pipeline behavior

The step returns `void`. Read its output JSON for resource names. The
[rollout example](../examples/Jenkinsfile) reviews SQL, prepares a rollout,
requests Jenkins input approval, and executes the prepared plan.

Without `targetStage`, rollout creates a release, plan, and rollout without calling
BatchRunTasks. Bytebase auto-rollout policies may still execute tasks independently.
Confirm server policy before relying on Jenkins input as an execution gate.

With `targetStage`, the plugin runs stages in order through the selected environment.
FAILED/CANCELED tasks or timeout fail the build. A missing environment fails before
Jenkins starts any task. An existing `plan` must belong to `project`; filePattern
and targets are ignored in that rollout mode.

Freestyle uses **Add build step → Bytebase: check SQL, rollout or inspect**.
Pipeline Syntax / Snippet Generator exposes the same options.

## Step options

| Option | Default / behavior |
| --- | --- |
| `serverName`, `command`, `project` | Required; command is `check`, `rollout`, or `inspect` |
| `targets` | Comma/whitespace/newline-separated; required except rollout with an existing plan |
| `filePattern` | Workspace-relative Ant glob, including `**/*.sql` |
| `credentialsId` | Advanced override; empty uses the connection credential |
| `expectedUrl` | Optional connection guard checked before any RPC |
| `inspectionMode` | `preflight`, `ready`, or `done`; inspect only |
| `expectedHost`, `expectedPort`, `expectedService` | Required for Oracle preflight |
| `declarative` | `false`; experimental Bytebase feature |
| `checkRelease` | `SKIP` by default; also `FAIL_ON_WARNING` and `FAIL_ON_ERROR` |
| `customRules` | Empty; natural-language AI rules for check |
| `plan` | Empty; `projects/{project}/plans/{plan}` |
| `targetStage` | Empty; `environments/{environment}` |
| `releaseIdTemplate` | `release_{date}-RC{iteration}`; interpreted by Bytebase |
| `releaseIdTimezone` | `UTC`; IANA timezone |
| `output` | `bytebase-result.json`; empty disables workspace output |
| `httpTimeoutSeconds` | `120`; 1–600 seconds per RPC |
| `waitTimeoutSeconds` | `3600`; overall wait, checked between RPCs/polls |
| `pollIntervalSeconds` | `5`; 1–300 seconds |
| `vcsType`, `vcsUrl` | Optional; both must be supplied for release commit metadata |

Targets accept `instances/{instance}/databases/{database}`,
`projects/{project}/instances/{instance}/databases/{database}`, or one
`projects/{project}/databaseGroups/{group}`. Groups cannot be mixed with databases.

Versioned filenames use `^[vV]?(\d+(\.\d+)*)`: `V3__add_source_system.sql` becomes
version `3`. Unversioned files are ignored with a log message; no valid migrations
causes failure. Paths are sorted as in the action. Declarative check keeps separate
files; declarative rollout joins them with newlines and uses UTC version
`yyyyMMdd.HHmmss`. Bytebase enforces engine and SQL/schema restrictions.

## Reports, limits, and recovery

**Bytebase results** retains every operation's report, including gate failures.
Workspace JSON contains `checkResults` or release/plan/rollout resource names. If
resource creation fails partway through, names already obtained remain available.
Archive the JSON before removing the agent workspace.

Credentials remain on the controller. SQL is read through FilePath/remoting.
Each operation permits up to 1,000 files, 16 MiB of SQL, and a 16 MiB response.
Absolute paths, `..`, and symlinks outside the workspace are rejected. A concurrent
hostile process can race filesystem changes, so deployment requires trusted checkouts.

HTTP redirects are not followed. Read/check RPCs retry at most three attempts for
429/502/503/504. Create/run/cancel RPCs do not retry transient or network failures:
an ambiguous response may already have created resources. Inspect Bytebase and the
saved output before retrying a mutation.

Abort/timeout requests cancellation of RUNNING/PENDING task runs across every page,
grouped by stage. Cancellation is best effort and cannot roll back executed SQL.
The plugin does not delete created Bytebase resources.

The synchronous step does not resume after a controller restart. Schedule restarts
outside active builds; after interruption, inspect Bytebase and recover using the
existing plan instead of blindly creating another release/plan.

The plugin does not post SCM comments or infer a PR author from the Jenkins user.
Use the JSON in an existing reporting step when needed. Bytebase authorizes the
configured service account/token.

## Read-only Oracle lab inspection

Inspection uses the same connection and controller-side authentication without
creating resources or running tasks.

- `preflight` requires one target and a targetStage. It checks project, ACTIVE state,
  environment, sync status, and the expected Oracle ADMIN host/port/service. Only
  necessary metadata is reported; data source secrets are excluded.
- `ready` requires a project-owned plan with one expected stage/target and
  DATABASE_MIGRATE tasks in NOT_STARTED/PENDING. This post-creation guard does not
  replace confirming auto-rollout policy before creating a rollout.
- `done` checks the same mapping and requires every task to be DONE. SKIPPED is rejected.

Preflight output includes serverVersion, database, and instance. Ready/done output
includes rollout and rolloutSnapshot. Results remain available after a validation
failure. Oracle preflight is specific to this workflow; check/rollout retain broader
Bytebase engine support.

## Repository CI and local builds

Run `mvn -B -ntp verify`; the artifact is `target/bytebase.hpi`. For a development
installation, use Jenkins Plugin Manager's upload facility and follow its restart
instructions. The credentials and plain-credentials dependencies must be compatible.
See the [Jenkins build-environment guide](https://www.jenkins.io/doc/developer/tutorial/prepare/).

The root Jenkinsfile uses [buildPlugin](https://github.com/jenkins-infra/pipeline-library).
The Linux Java 21/25 matrix matches the hosting baseline. A private Jenkins needs
that library and its agent/tool conventions; copying the file alone is insufficient.
GitHub Actions run Maven verification, archive results/HPI, and run Jenkins Security
Scan; Dependabot maintains Maven/action dependencies. Release configuration and the
hosting process are described in [publishing](publishing.md). Consumer pipelines
under `examples/` are separate from plugin build/release CI.
