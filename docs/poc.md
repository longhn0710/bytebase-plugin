# Plugin verification and lab PoC

Verification date: **2026-11-07**, UTC+07.
This document preserves the early PoC results. The subsequent native V4 rollout,
plan 104/task 104 DONE, Oracle verification, and 0.2.1 upgrade fix are recorded in
[live-delivery.md](live-delivery.md). CLI/account parity is in
[cli-parity.md](cli-parity.md). Current public CI is separate from these lab artifacts.

## Automated verification

`mvn -B -ntp spotless:apply verify` completed with BUILD SUCCESS. The initial
setup used Maven 3.9.11 and Corretto JDK 21, targeting Java 17 and Jenkins 2.504.3.
Build tools were downloaded into a local cache without a system-wide installation.
The hosting preparation later raised the baseline; see pom.xml and the CI matrix.

- The final lab 0.2.1 source passed 15 integration tests on Java 17 and Java 21.
- Of 8 generated harness tests, 7 passed and 1 was skipped because no localization
  properties existed. These counts include the upgrade regression coverage added
  after the first PoC runs.
- SpotBugs: 0 bugs; Spotless clean; dependency/API checks passed.
- Generated artifact: `target/bytebase.hpi` at the repository root.
- Generated reports: `target/surefire-reports/` at the repository root.

The harness starts Jenkins and a remote agent for Pipeline testing. A controlled
HTTP server implements Bytebase Connect RPC responses from the reference schema.
It covers complete workflows, including rollout and cancellation. Live SQL review
was additionally verified against Jenkins and Bytebase in the lab.

| Scenario | Verified behavior |
| --- | --- |
| Pipeline reads SQL on a remote agent | Correct version/base64 SQL and archived JSON |
| Freestyle and UI config round-trip | Build step, connection, and credential IDs retained |
| ERROR/WARNING under three quality gates | Correct build result; output/report survives failure |
| CreateRelease → CreatePlan → CreateRollout | Correct resource names, UUID spec, release, and VCS references |
| Run through environments/test | Executes test and stops before prod |
| Existing plan with invalid files/targets | Ignores files/targets as the action does |
| No targetStage | Does not call GetRollout/BatchRunTasks |
| Missing target environment | Fails without BatchRunTasks |
| FAILED task | Build fails |
| Timeout and paginated task runs | Cancels RUNNING/PENDING runs on all pages, grouped by stage |
| Abort while waiting or reading HTTP | Build ABORTED and cancellation requested |
| Declarative mode | Checks files separately; joins rollout SQL with newlines |
| Access token/service account | Correct bearer authentication and refresh after 401 |
| Mutation returns 503 | No automatic replay |
| Missing credential | Fails before calling Bytebase |
| Traversal/symlink outside workspace | Rejected |
| Several operations in a build | All reports retained in one action |

## Initial live review-only PoC

- Jenkins 2.568.3 with Java 21; Bytebase 3.22.1.
- HPI 0.1.0-SNAPSHOT was uploaded through Plugin Manager and loaded dynamically:
  active, no failed plugins, no controller restart at this stage.
- Existing dependencies were compatible and were not upgraded in the cluster.
- Connection bytebase-lab initially had no default credential; the build step supplied
  an existing credential ID. No Bytebase credential was created, copied, or changed.
- Isolated folder bytebase-plugin-poc contained native-sql-check Pipeline and
  native-sql-check-freestyle. SQL was read on an agent; API calls ran on the controller.
- Existing jobs, Bytebase policies, and account permissions were unchanged at this stage.

| Job / build | Gate | ERROR / WARNING | Result |
| --- | --- | --- | --- |
| Pipeline #2 | SKIP | 3 / 4 | SUCCESS with all advices retained |
| Pipeline #3 | FAIL_ON_ERROR | 3 / 4 | Expected FAILURE |
| Pipeline #4 | FAIL_ON_WARNING | 0 / 11 | Expected FAILURE |
| Pipeline #5 | FAIL_ON_ERROR | 0 / 1 | SUCCESS |
| Freestyle #1 | FAIL_ON_ERROR | 0 / 1 | SUCCESS |

The initial Oracle fixture lacked comments/defaults, which were added to later
review inputs. Build #4 used quoted lowercase identifiers and triggered policy
warnings. The final uppercase fixture retained a table-naming warning, allowed by
FAIL_ON_ERROR. Policies were not changed. Build #1 failed due to setup-script quoting
before invoking the plugin; the setup was corrected.

All five verification builds archived SQL and JSON. Bytebase results returned HTTP 200
and retained checkResults after gate failure. Console output confirmed use of the
native bytebase step without bytebase-action or Docker execution. Freestyle used a
shell only to create the review fixture.

Generated evidence was saved under target/poc/ and copied into the private delivery
bundle. A clean build may remove target/. The original files included summary.json,
result JSON, console logs, and example-validation.txt.

This initial phase reviewed SQL only: no release/plan/rollout creation, DDL/DML, or
restart. Rollout/abort/timeout were initially verified in the harness. The later lab
rollout used a confirmed synthetic target, as documented in live-delivery.md.

## Reproduce the review PoC

1. Build/install the development HPI using the [reference](reference.md#repository-ci-and-local-builds).
2. Add bytebase-lab under Manage Jenkins → System → Bytebase.
3. Choose a service account credential with SQL-check access on the lab target and
   confirm Test connection. Folder credentials require a job in that folder and an
   explicit credentialsId; the global connection test cannot use that folder scope.
4. Create a Pipeline from [examples/Jenkinsfile.poc](../examples/Jenkinsfile.poc).
5. Set the actual project/target, SQL_ENGINE, and CHECK_RELEASE, then run the build.
6. Inspect the console, Bytebase results, and bytebase-poc/result.json artifact.

CheckRelease reviews the CREATE TABLE fixture without executing it. The PoC defaults
to FAIL_ON_ERROR; use FAIL_ON_WARNING for a stricter gate or SKIP to inspect reports
without enforcing advice severity. PostgreSQL and Oracle fixtures are supported;
the server's actual policy may produce additional advices.

For a lab rollout, use [examples/Jenkinsfile](../examples/Jenkinsfile) with an approved
environment and migration. Confirm auto-rollout/approval policy first. Do not change
policy just to make the build pass. Remove or disable one-off jobs through Jenkins
when their retained results are no longer needed.
