# Mapping bytebase/action to Jenkins

The implementation was compared with Bytebase's `action/{command,world,github,gitlab,azure,bitbucket}`
source and the Ansible Tower Jenkins plugin's configuration patterns. The Bytebase
action at tag `3.22.1` was also reviewed because that version runs in the lab.
The reference repositories were not modified.

## Source behavior

`root.go` registers check/rollout commands and validates flags, authentication,
targets, and HTTP timeout. `api.go` implements Connect RPC, bearer authentication,
login refresh, transient retries, and task-run pagination. The plugin uses the
same protocol with protobuf JSON and base64-encoded SQL bytes.

`check.go` reads files, calls CheckRelease without creating a release, formats the
CI platform's output, and applies the quality gate. ERROR/WARNING advices affect
the gate; affected-row and risk information remain in the response. The plugin
persists its report/output before propagating a gate failure and logs advice counts.

`rollout.go` calls GetPlan when a plan is supplied. Otherwise, it calls CreateRelease
and CreatePlan with one changeDatabaseConfig spec containing a UUID, release, and
targets. CreateRollout uses the plan as parent without restricting the created
stages. With targetStage set, the action refreshes the rollout, starts NOT_STARTED
tasks stage by stage, waits for DONE/SKIPPED, and stops at the selected environment.
The source does not call IssueService directly, despite the action README's mention
of a rollout issue.

On cancellation, the action lists all task-run pages, selects RUNNING/PENDING runs,
groups them by stage, and calls BatchCancelTaskRuns. Jenkins interruption and
rollout timeout drive the plugin's corresponding behavior.

`file.go` expands recursive globs, sorts paths, extracts versions from filenames,
and ignores files without a version. Declarative check sends files separately;
declarative rollout joins them with newlines. The reviewed source does not read
gh-ost migration-type comments, and protobuf Release.File has no migration-type
field. The plugin therefore does not claim that feature.

## Feature mapping

| Action | Jenkins plugin | Status |
| --- | --- | --- |
| check, --check-release | command=check, checkRelease | Implemented |
| Rollout from SQL | command=rollout, filePattern, targets | Implemented |
| Rollout from a plan | plan | Implemented; ignores files/targets |
| --target-stage | targetStage | Implemented; fails for a missing environment |
| Service account / secret | Username with password credential | Implemented; no secret environment variables |
| Access token | Secret text credential | Implemented; accepts an existing access token |
| --timeout | httpTimeoutSeconds | Implemented |
| --project, --targets | project, targets | Implemented; no sample/production defaults |
| --file-pattern | filePattern, workspace FilePath | Ant glob; not every doublestar expression |
| --declarative | declarative | Implemented; server enforces engine/SQL restrictions |
| --custom-rules | customRules | Implemented |
| Release ID template/timezone | releaseIdTemplate/releaseIdTimezone | Implemented |
| Output JSON | output + Bytebase results action | Implemented; retains partially created resources |
| VCS release source | vcsType + vcsUrl | Explicit commit URL from the Jenkins checkout |
| VCS PR creator lookup | No automatic lookup | Requires a separate SCM integration |
| GitHub comments/annotations/step summary | Build action + JSON | Does not post comments automatically |
| GitLab Code Quality JSON | checkResults JSON | No separate Code Quality format |
| Azure logging / Bitbucket reports | Build log + JSON | Does not call those platform APIs |
| CLI/server version window | Bytebase API baseline 3.22.1 | CLI version rules are not applied to plugin versions |
| Cancel on context termination | Cancel task runs on abort/timeout | Best effort; includes pagination |
| Transient retries | Retry reads/check; refresh auth for mutations | Does not replay mutations after ambiguous failures |

## Intentional differences

- Project and target have no sample defaults, preventing an omitted setting from selecting a database.
- No valid versioned files causes failure rather than an empty release.
- A missing target environment fails instead of reporting success without running it.
- Plans and database groups must belong to the project; groups cannot be mixed with individual databases.
- Timeout, URL, and workspace paths are validated. There is no TLS-verification bypass.
- An overall rollout wait timeout is provided. The synchronous step cannot resume across controller restarts.
- Bytebase enforces its approvals and policies. Jenkins input is an additional gate; the plugin does not create or approve a Bytebase issue.

## Architecture

`BytebaseGlobalConfiguration` and `BytebaseServer` manage named connections and
credential IDs. `BytebaseBuilder` is a SimpleBuildStep with the `bytebase` symbol,
shared by Pipeline, Freestyle, and Snippet Generator. `WorkspaceFiles` reads SQL
through remoting; `BytebaseClient` calls RPCs on the controller and resolves
credentials in the build's scope. `BytebaseRunner` coordinates check, rollout,
and cancellation. `BytebaseResultAction` retains build reports, while workspace
JSON can be archived.

TowerConnector was not copied because its protocol, authentication, and domain
are different. The plugin does not embed the Go CLI or Bytebase backend, or use
shell commands to assemble API arguments and secrets.
