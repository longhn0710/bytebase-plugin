# Jenkins hosting and Bytebase releases

This guide was checked on 2026-11-07 against the
[hosting guide](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/)
and [official request form](https://github.com/jenkins-infra/repository-permissions-updater/blob/master/.github/ISSUE_TEMPLATE/1-hosting-request.yml).

The submitted request is [Hosting Request #5434](https://github.com/jenkins-infra/repository-permissions-updater/issues/5434).
Use that issue for current review status; submission and automated checks do not
constitute hosting approval or a plugin release.

## Identity and repositories

| Item | Value |
| --- | --- |
| Plugin ID / artifactId | `bytebase` |
| Maven groupId | `io.jenkins.plugins` |
| Current repository | `https://github.com/longhn0710/bytebase-plugin` |
| Requested repository | `jenkinsci/bytebase-plugin` |
| GitHub maintainer | `@longhn0710` |
| Confirmed Jenkins community username | `longhn0710` |
| Release method | JEP-229 through manually triggered GitHub Actions |

POM/SCM and CODEOWNERS declare the requested jenkinsci target to satisfy hosting
checks. This does not assert that the fork or developer team exists. Keep the
personal Git remote until hosting is confirmed.

## Hosting and release procedure

1. Sign in to [Jenkins Jira](https://issues.jenkins.io) at least once with community
   account longhn0710. Account creation alone does not prove Jira has received the
   user; the checker's account data may refresh hourly. JEP-229 does not require
   personal release credentials in the workflow.
2. Review, commit, and push the plugin source to the public repository. Exclude
   target/, work/, credentials, and private lab delivery bundles. The repository
   initially contained only LICENSE/README; source was published through
   [PR #1](https://github.com/longhn0710/bytebase-plugin/pull/1).
3. Let the GitHub build matrix and Jenkins Security Scan complete and address findings.
4. Run the official hosting checker against the pushed source with a GitHub API
   token. No special token scope is required to read a public repository:

   ```sh
   git clone https://github.com/jenkins-infra/repository-permissions-updater.git
   cd repository-permissions-updater
   ./bin/check-hosting.sh \
     --repository-url https://github.com/longhn0710/bytebase-plugin \
     --new-repository-name bytebase-plugin \
     --github-user longhn0710 \
     --jenkins-user longhn0710
   ```

   The checker currently requires Java 25+ and Maven. Use an authenticated GitHub
   CLI or configure the token as described in the
   [official instructions](https://github.com/jenkins-infra/repository-permissions-updater#running-the-hosting-checks-locally).
   Never put it in source or a hosting request. Exit 0 means checks completed with
   no Required findings; exit 1 means Required findings, exit 2 means authentication
   is missing, and exit 3 means checks did not all complete. A source-only local
   audit does not replace remote, account, and history verification.
5. Open the [Hosting request form](https://github.com/jenkins-infra/repository-permissions-updater/issues/new?template=1-hosting-request.yml)
   with the values below. Check the hosting-checks confirmation only after step 4 passes.
6. Respond to the Hosting Team and address review/security requests. The documented
   `/hosting re-check` comment reruns the bot. Do not claim human security approval
   based only on a local or automated scan.
7. After approval, accept the jenkinsci invitation, verify the fork, and confirm
   that the release-permissions PR has merged. If no PR is generated, follow the
   [permissions repository instructions](https://github.com/jenkins-infra/repository-permissions-updater).
   GitHub access and Maven release permissions are separate.
8. Update remotes only after verifying the new repository. If the Hosting Team
   requests deleting/reforking the personal repository to fix the fork network,
   handle that separately after confirming the canonical copy and a backup.
   Do not automate repository deletion.
9. Verify that ci.jenkins.io discovers the repository and buildPlugin passes. The
   Hosting Team must review CD setup before it is merged/enabled for release,
   following the [CD guide](https://www.jenkins.io/doc/developer/publishing/releasing-cd/).
10. Run **Release plugin** with validate_only=true first. After validation and release
    notes are correct, run with validate_only=false to publish. Jenkins infrastructure
    manages release secrets after CD permission is granted. Lab Jenkins API credentials
    are unrelated to Maven/GitHub release authentication.
11. Verify the version in Artifactory, the GitHub release, and
    `https://plugins.jenkins.io/bytebase/`. Hosting approval alone does not put a
    plugin in the Update Center; a valid release and metadata refresh are required.

## Hosting request fields

Title: **Hosting request for Bytebase**

### Repository URL

https://github.com/longhn0710/bytebase-plugin

### New Repository Name

bytebase-plugin

### Description

Bytebase provides native SQL review and staged database rollout for Jenkins
Pipeline and Freestyle jobs. It calls Bytebase Connect RPC directly, without
requiring bytebase-action, Go, or a container runtime on build agents.

Named Jenkins connections select service account credentials stored in Jenkins.
The plugin supports SQL quality gates, creating releases/plans/rollouts, executing
an existing plan through a selected environment, bounded polling and cancellation,
and persisted build reports. Bytebase enforces project access, approval policies,
and SQL execution. Optional read-only Oracle inspection validates target metadata
and rollout task state.

Compared with wrapping bytebase-action in shell steps or composing generic HTTP
requests, the plugin supplies Jenkins credential integration, workspace/agent file
access, Bytebase resource lifecycle handling, cancellation, and Pipeline/Freestyle
configuration as one implementation. It integrates with the Bytebase control plane
rather than providing a separate SQL migration engine.

Use GitHub Issues for non-security reports. The source is MIT licensed. Linux
Java 21/25 buildPlugin CI and Jenkins Security Scan are configured. JEP-229 is prepared
for manual GitHub Actions releases. The Hosting Team must review that setup before
release permissions are enabled. Public CI and hosting/security checks must be
assessed against the submitted commit; private lab results are supplementary.

### GitHub and Jenkins project users

@longhn0710 longhn0710

### Automated release via GitHub Actions (recommended)

Yes

### Hosting checks

- [ ] I have run the hosting checks locally against this repository and fixed all issues marked Required.

This reusable example stays unchecked. The actual submission may check it only
when the full official checker has succeeded for the submitted source.

## Build and versioning configuration

The hosting preparation uses parent POM 6.2250, baseline 2.555.3, BOM 2.555.x, and
Java 21/25 to meet the checked
[hosting requirements](https://github.com/jenkins-infra/repository-permissions-updater/blob/master/src/main/java/io/jenkins/infra/repository_permissions_updater/hosting/Requirements.java).
`999999-SNAPSHOT` is the local CD development version; published versions use
`N.v<commit>`, rather than the lab's 0.2.x PoC versions. Do not release with an
ad hoc `mvn deploy` command.

Network configuration remains a JVM/controller concern. The checker's recommendation
about integrating Jenkins network settings is documented for review; removed
per-plugin settings have not been reintroduced.

## Recorded verification stages

The official checker at commit `6b351f66e3cfd605dd8b3c67c7e0453f6d50cd60` was built
with Java 25. Before source publication, it returned exit 1 with eight missing-file
requirements against the old default branch. GitHub and Jenkins account verification
completed without a missing-Jira-user finding.

A temporary local snapshot then ran the official RequiredFiles, Maven, Jelly, and
SourceCode verifiers: 0 Required, 2 Warning. That source-only audit did not cover
remote accounts/history or constitute hosting approval.

After PR #1 merged, the full remote checker returned **exit 0 with 0 Required**
against master commit `506fc68d4c5721eed7e8a9eaf968b6558a5f2b76`. The
[master Java 21/25 build](https://github.com/longhn0710/bytebase-plugin/actions/runs/42363494005)
and [master security scan](https://github.com/longhn0710/bytebase-plugin/actions/runs/42363494240)
passed; processed SARIF contained 0 findings. The hosting infrastructure's
[security scan](https://github.com/jenkins-infra/repository-permissions-updater/actions/runs/42363866951)
also passed and assigned security-audit-done to the submitted request.

Two recommendations remain recorded for Hosting Team review: the advanced credential
override accepts a manually entered ID, and the HTTP client follows JVM settings
rather than the Jenkins network-settings hook. Checks were not bypassed or disabled.
The hosting issue remains the authority for later review, fork, and permission decisions.
