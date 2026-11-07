# Live lab delivery evidence — 2026-11-07

This report records the historical PoC, not the current hosting baseline.
Plugin **0.2.0-SNAPSHOT** completed the native workflow on Jenkins 2.568.3,
Bytebase 3.22.1, and an Oracle lab. It used `jenkins@service.bytebase.com` through
Username with password credential `bytebase-jenkins-sa` on `bytebase-default`.
The native pipeline contained no Bytebase username, Service Key, or withCredentials.

The final lab artifact was **0.2.1-SNAPSHOT**. It added XStream migration defaults
so Freestyle jobs saved before the inspection fields existed could still execute.
Rollout API behavior stayed unchanged; source hashes and the 0.2.0 HPI were retained
for comparison. The installed controller archive matched the delivered HPI hash;
both agents were online with no failed plugins after safeRestart. Freestyle #2
exposed the null-field upgrade bug; #3 verified the fix without rewriting job XML.

## Verification matrix

| Scope | Evidence | Result |
| --- | --- | --- |
| Original GitOps CLI/native parity | [Comparison #4](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/cli-native-comparison/4/) | Equal CheckRelease; login 200, metadata 403; old preflight fails |
| Same key with temporary Workspace DBA | [Comparison #5](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/cli-native-comparison/5/) | Equal CheckRelease; metadata 200; old preflight passes |
| Restored GitOps role | [Comparison #6](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/cli-native-comparison/6/) | Equal CheckRelease; metadata returns to 403 |
| Strict preflight → review → prepare → input → execute → DONE | [Native rollout #2](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/native-lab-rollout/2/) | SUCCESS; 0 ERROR / 2 WARNING; SQL policy unchanged |
| DONE read-back, replay guard, missing credential | [Read-only verification #4](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/native-lab-verification/4/) | SUCCESS; expected metadata denial is not a preflight pass |
| Existing Freestyle job after upgrade | [Freestyle #3](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/native-sql-check-freestyle/3/) | SUCCESS on 0.2.1 without resaving XML |
| Final lab 0.2.1 parity and DONE read-back | [Comparison #7](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/cli-native-comparison/7/), [Verification #5](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/native-lab-verification/5/) | SUCCESS with the restored GitOps role |
| Authentication, account isolation, timeout/abort, payloads, UI config, gates | Jenkins integration harness | 15 integration tests pass; 7 generated tests pass, 1 localization test skipped; SpotBugs 0 |
| Demo source selection, immutability, inspection | Python tests after V4 | 15 tests; 1 Flyway-specific skip; no failures |

Controlled Jenkins harness tests covered distinct accounts on one URL, invalid
keys, missing overrides, denied-project behavior without fallback, cancellation,
pagination, and declarative payloads. These results are not presented as live SQL
execution. At the lab handoff, remote repository CI and publication had not run;
subsequent hosting work is tracked in [publishing.md](publishing.md).

## The migration executed

- Project: `projects/database-demo-ops`.
- Target: `instances/oracle-cloud-free-sge0/databases/DEMO_APP_A`, environment `environments/develop`.
- New file: `V4__jenkins_plugin_evidence.sql`; creates synthetic table
  `DEMO_PLUGIN_EVIDENCE` with three columns and a primary key. No DML, drop, or reset.
- SQL SHA-256: `724c4c6bac6adcf36700511460143a0325629af144af519989e1762efba59ecb`.
- Release: `jenkins-plugin-evidence-20261107-RC00`, containing exactly one version-4 file.
- [Plan 104](https://bytebase.apps.drgdevlab.com/projects/database-demo-ops/plans/104),
  task 104, task run 105: **DONE**, creator `users/jenkins@service.bytebase.com`.
- Before approval, the snapshot showed one expected NOT_STARTED task and policy
  `automatic=false`. Jenkins approval used the logged-in UI account.
- After approval, strict preflight and ready inspection ran again before executing plan 104.
- Oracle read-back confirmed `CURRENT_SCHEMA=DEMO_APP_A`, the new table with 0 rows,
  and all three columns. Revision 4, sheet, release, and archived SQL hashes matched.
- Revisions 1/2/3 resource names, checksums, and creation timestamps were unchanged.
  V1–V3 were not replayed.

Rehearsal #1 was rejected at CreateRelease because its trial template lacked the
required placeholders. Bytebase validates that template before writing resources.
The job was corrected to `jenkins-plugin-evidence-{date}-RC{iteration}`; the plugin's
default remained `release_{date}-RC{iteration}`. No mutation retry or policy bypass
was used to get past the rejection.

## State restored after testing

Temporary Workspace DBA was removed, restoring Jenkins-SA to **GitOps Service
Agent**. Neither the Service Key nor Jenkins credential was reset/replaced.
Community did not support custom roles; no trial or license bypass was enabled.

The original SCM writer was disabled during rehearsal and restored afterward with
the same SCMBinder and script path. The one-off native-lab-rollout job was disabled
to prevent V4 replay, retaining its artifacts/history. Auto-rollout and SQL policies
were unchanged. V4 remains in the demo source as an applied lab migration: preserve
its checksum and do not reuse version 4.

The demo's Jenkinsfile.bytebase-plugin still defaults to ENABLE_ROLLOUT=false;
the original main job was not switched. GitOps-only permission intentionally fails
strict metadata preflight. Additional metadata read access must be decided before
using that pipeline routinely. Test connection verifies login only.

The private bundle beside the repository, `../bytebase-plugin-delivery-20261107/`,
contains HPIs, source snapshots, the actual rehearsal pipeline, logs, API/Jenkins JSON,
screenshots, an offline verifier, SHA-256 manifest, and HTML report. This local bundle
is not part of the public Git repository.

| Lab HPI | SHA-256 |
| --- | --- |
| 0.2.0 used for rollout | `f29171811f906a33e37436aadcc233f23d05de80be2fbb38b3e1068569d94e8d` |
| Final lab 0.2.1 | `158bf14699fab201b540fc84c75f798b1e0416111a8efa4c727b1f9666eb0d37` |
