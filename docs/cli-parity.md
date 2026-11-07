# CLI 3.22.1 parity with the GitOps Service Agent

Verified on 2026-11-07 using Jenkins and Bytebase 3.22.1.
Job: `bytebase-plugin-poc/cli-native-comparison`, builds **#4, #5, and #6** used
plugin 0.2.0 after an authorized safeRestart. Build #7 repeated parity on 0.2.1.

| Build | Role | Metadata / legacy CLI preflight | CLI/native CheckRelease |
| --- | --- | --- | --- |
| #4 | GitOps | 403 / fail | Identical; 0 ERROR / 1 WARNING |
| #5 | GitOps + temporary Workspace DBA, same key | 200 / pass | Identical; 0 ERROR / 1 WARNING |
| #6 | GitOps after restoration | 403 / fail | Identical; 0 ERROR / 1 WARNING |

Native rollout and Oracle verification are recorded in [live-delivery.md](live-delivery.md).

## Identical inputs

- Same URL, project, database target, and service account `jenkins@service.bytebase.com`.
- The CLI binds Jenkins credential `bytebase-jenkins-sa`; the plugin selects
  connection `bytebase-default`, which references that same credential ID.
- Same `.comparison/V3__add_source_system.sql`, version `3`, and `checkRelease=SKIP`.
  SQL was sent only to CheckRelease and was not executed.
- SQL SHA-256: `912591dd4254cc62b7a31a021f97a039af07fd28b98666e91742e0bfe6e5aede`.
- CLI image: `bytebase/bytebase-action:3.22.1`; its version is recorded in cli.log.
- Builds #4–#6 used native plugin **0.2.0-SNAPSHOT**. Earlier build #3 used 0.1.0
  with the same outcome. After safeRestart, 0.2.0 was active, no plugins failed,
  both agents were online, and Test connection succeeded.

## Results with the original GitOps role

| Check | Verified result |
| --- | --- |
| CLI check | Exit 0; 0 ERROR / 1 WARNING |
| Native check | SUCCESS; 0 ERROR / 1 WARNING |
| Complete `checkResults` | Equal after JSON parsing, not just equal advice counts |
| GetCurrentUser with the same account | HTTP 200 |
| GetDatabase with the same account | HTTP 403; missing `bb.databases.get` |
| GetProject with the same account | HTTP 403; missing `bb.projects.get` |
| GetInstance with the same account | HTTP 403; missing `bb.instances.get` |
| Legacy database-demo preflight in the same container | Exit 1; curl 403 followed by the database mismatch guard |

The credential authenticated successfully, and both implementations reviewed SQL.
The 403 responses came from metadata reads added by lab preflight. They were not
CheckRelease failures and did not demonstrate an invalid Service Key.

The [3.22.1 GitOps Service Agent role](https://github.com/bytebase/bytebase/blob/3.22.1/backend/store/predefined_roles.go#L582)
grants release, plan, rollout, and task-run operations for automation, but not the
three metadata reads above. It is not equivalent to Workspace DBA/Admin. Build #4
kept the role unchanged; #5 temporarily added Workspace DBA under test authorization;
#6 verified the restored role. No key reset, guard removal, or SQL-policy change
was used to make the checks pass.

The native lab pipeline retains the legacy metadata guards and stops at preflight
until the account has the required read permissions. Standard plugin check/rollout
does not add GetDatabase, GetProject, or GetInstance to the CLI workflow.

## Evidence and reproduction

Build artifacts include `comparison.json`, `cli.json`, `plugin.json`, `cli.log`,
`legacy-preflight.log`, and `rpc-*-http.txt`/`rpc-*.json`. RPC responses were filtered;
login responses, tokens, and keys were not saved. Jenkins stopped and removed the
diagnostic containers. Evidence was copied into the private delivery bundle beside
the plugin repository; generated `target/poc/` copies are excluded from Git and may
be removed by a clean build.

[examples/Jenkinsfile.cli-comparison](../examples/Jenkinsfile.cli-comparison) uses
the two helpers in that directory. Run it from a checkout of this repository on an
agent with Docker/Python 3, and supply the project, target, and connection. The
connection must reference the exact credential ID used by the CLI: matching account
emails alone does not prove that two credential records contain the same key.

Only the diagnostic CLI comparison needs Bytebase `withCredentials`. Normal native
pipelines select `serverName` without binding a Service Key on the agent.
