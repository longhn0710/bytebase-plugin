# Đăng ký Jenkins hosting và phát hành Bytebase

Đối chiếu ngày 2026-11-07 với [hosting guide](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/)
và [form chính thức](https://github.com/jenkins-infra/repository-permissions-updater/blob/master/.github/ISSUE_TEMPLATE/1-hosting-request.yml).

## Danh tính và repository

| Mục | Giá trị |
| --- | --- |
| Plugin ID / artifactId | `bytebase` |
| Maven groupId | `io.jenkins.plugins` |
| Repository hiện tại | `https://github.com/longhn0710/bytebase-plugin` |
| Repository đăng ký | `jenkinsci/bytebase-plugin` |
| GitHub maintainer | `@longhn0710` |
| Jenkins community username | `longhn0710` (người dùng đã xác nhận) |
| Release | JEP-229 qua GitHub Actions, bấm chạy thủ công |

POM/SCM và CODEOWNERS khai báo đích `jenkinsci` để đáp ứng hosting checks; đó chưa
phải xác nhận repo đã được fork hoặc team đã tồn tại. Git remote cá nhân giữ nguyên
cho tới khi hosting hoàn tất.

## Trình tự cần thực hiện

1. Đăng nhập [Jira Jenkins](https://issues.jenkins.io) bằng community account
   `longhn0710` ít nhất một lần. Việc tạo account chưa chứng minh Jira đã nhận user;
   dữ liệu kiểm tra có thể cập nhật theo giờ. Với JEP-229, maintainer không cần đặt
   credential release cá nhân vào workflow.
2. Review và commit/push source plugin lên repository công khai. Hiện remote ban
   đầu chỉ có LICENSE/README nên không thể gửi hosting request như đã sẵn sàng.
   Không đưa `target`, `work`, credentials hoặc gói lab evidence vào commit.
3. Chờ GitHub build matrix và Jenkins Security Scan chạy; xử lý các findings.
4. Chạy hosting checker chính thức trên source đã push, với token GitHub dùng để
   đọc API (không cần scope đặc biệt cho public repo):

   ```sh
   git clone https://github.com/jenkins-infra/repository-permissions-updater.git
   cd repository-permissions-updater
   ./bin/check-hosting.sh \
     --repository-url https://github.com/longhn0710/bytebase-plugin \
     --new-repository-name bytebase-plugin \
     --github-user longhn0710 \
     --jenkins-user longhn0710
   ```

   Tool hiện yêu cầu Java25+ và Maven. Dùng GitHub CLI đã login hoặc cấu hình token
   theo [hướng dẫn chính thức](https://github.com/jenkins-infra/repository-permissions-updater#running-the-hosting-checks-locally);
   không ghi token vào source hay hồ sơ đăng ký. Exit0 mới là toàn bộ checks hoàn tất
   và không có Required; exit1 có Required, exit2 thiếu auth, exit3 có check chưa chạy
   xong. Kiểm tra file local không thay được kiểm tra remote/account/history này.
5. Mở [Hosting request](https://github.com/jenkins-infra/repository-permissions-updater/issues/new?template=1-hosting-request.yml)
   bằng nội dung bên dưới, chỉ tick Hosting checks khi bước4 thực sự đạt.
6. Trả lời Hosting Team, sửa các yêu cầu review/security. Có thể comment
   `/hosting re-check` để chạy lại bot. Không tự xác nhận đã được security audit.
7. Khi được duyệt, nhận lời mời `jenkinsci`, xác minh source đã được fork đúng và
   release permissions PR đã merge. Nếu không có PR tự động, tạo yêu cầu theo
   [repository-permissions-updater](https://github.com/jenkins-infra/repository-permissions-updater).
   Quyền GitHub và quyền phát hành Maven là hai quyền riêng.
8. Đổi remotes theo repo mới sau khi xác minh. Nếu Hosting Team yêu cầu xử lý
   fork network bằng cách xóa/refork repo cá nhân, thực hiện riêng sau khi đã có
   bản canonical và backup; không tự động xóa repo trong script.
9. Xác minh `ci.jenkins.io` nhận repo và buildPlugin pass. PR chứa CD setup phải
   được Hosting Team review trước khi merge/bật release, theo
   [CD guide](https://www.jenkins.io/doc/developer/publishing/releasing-cd/).
10. Chạy workflow **Release plugin** với `validate_only=true` trước. Sau khi kết
    quả và release notes đúng, chạy lại với `validate_only=false` để phát hành.
    Secrets release do hạ tầng Jenkins quản lý sau khi CD được cấp phép. Không dùng
    `jenkins.cred` của Jenkins lab cho Maven/GitHub release.
11. Kiểm tra version trên Artifactory, GitHub release và
    `https://plugins.jenkins.io/bytebase/`. Hosting approval chưa đồng nghĩa plugin
    đã xuất hiện trên Update Center; cần release hợp lệ và metadata được cập nhật.

## Nội dung Hosting request

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
and persisted build reports. Bytebase retains responsibility for project access,
approval policies, and SQL execution. Optional read-only Oracle inspection validates
target metadata and rollout task state.

Compared with wrapping bytebase-action in shell steps or composing generic HTTP
requests, the plugin supplies Jenkins credential integration, workspace/agent file
access, Bytebase resource lifecycle handling, cancellation, and Pipeline/Freestyle
configuration as one implementation. It is an integration with the Bytebase control
plane rather than a separate SQL migration engine.

Please use GitHub Issues as the non-security issue tracker. The source is MIT
licensed. Linux Java21/25 buildPlugin CI and Jenkins Security Scan are configured.
JEP-229 is prepared with manual GitHub Actions releases only. Please review the CD
setup before it is enabled. Public CI and hosting/security checks must be evaluated
against the submitted commit; private lab results are supplementary.

### GitHub and Jenkins project users

@longhn0710 longhn0710

### Automated release via GitHub Actions (recommended)

Yes

### Hosting checks

- [ ] I have run the hosting checks locally against this repository and fixed all issues marked Required.

Leave this unchecked in this draft until the complete official checker succeeds.

## Bản chuẩn bị local

Build metadata đang dùng parent POM6.2250, baseline2.555.3, BOM2.555.x và Java21/25
để đáp ứng [Requirements.java của checker](https://github.com/jenkins-infra/repository-permissions-updater/blob/master/src/main/java/io/jenkins/infra/repository_permissions_updater/hosting/Requirements.java).
`999999-SNAPSHOT` là version local chuẩn cho CD; bản phát hành sẽ có dạng
`N.v<commit>`, không phải version PoC0.2.x. Không tự release bằng `mvn deploy`.

Thiết lập network giữ ở JVM/controller theo quyết định của maintainer. Khuyến nghị
của checker về tích hợp thiết lập mạng Jenkins được ghi nhận riêng; không thêm lại
trường cấu hình đã được yêu cầu xóa.

## Kết quả chuẩn bị

Checker chính thức tại commit `6b351f66e3cfd605dd8b3c67c7e0453f6d50cd60` đã được build
bằng Java25 và chạy read-only trên repo GitHub. Kết quả remote: exit1, 8 Required
thiếu file vì default branch chưa có source/config. Các bước kiểm tra GitHub user và
Jenkins project user đã hoàn tất, không báo user thiếu đăng nhập Jira.

Source local được đưa vào snapshot tạm và chạy trực tiếp bốn verifier chính thức:
RequiredFiles, Maven, Jelly, SourceCode. Kết quả: **0 Required**, 2 Warning. Đây
không phải kết quả full remote checker trên source mới; GitHub/default branch,
history và security review cần kiểm tra lại sau push. Checkbox trong hosting form
vẫn chưa được tick.

Hai Warning đang giữ để review: credential override ở advanced dùng ID nhập tay;
HTTP client dùng thiết lập JVM thay vì hook cấu hình mạng của Jenkins. Không có
bypass hoặc tắt bộ kiểm tra. GitHub Actions/Security Scan và ci.jenkins.io trên source
mới chưa chạy. Chưa gửi hosting issue, push, merge hoặc release.
