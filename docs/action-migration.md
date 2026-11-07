# Phân tích và mapping bytebase/action → Jenkins

Nguồn đối chiếu: `../../bytebase/action/{command,world,github,gitlab,azure,bitbucket}`,
`../../ansible-tower-plugin/src/main/java/org/jenkinsci/plugins/ansible_tower`.
Đối chiếu thêm source action tag `3.22.1` vì đó là Bytebase đang chạy trong cụm.
Không sửa các repository tham chiếu.

## Luồng thực tế trong source

`root.go` đăng ký check/rollout, validate flags/auth/targets và client timeout.
`api.go` dùng Connect RPC, bearer token, login refresh, retry transient và task-run
pagination. Plugin dùng cùng protocol với JSON protobuf, SQL bytes encode base64.

`check.go`: đọc file → CheckRelease (không tạo release) → tạo output theo CI platform
→ áp dụng quality gate. Mỗi advice ERROR/WARNING ảnh hưởng gate; affected rows/risk
được giữ trong response JSON. Plugin lưu report/output trước khi lỗi gate đi qua
ranh giới build step, và ghi tổng số lỗi/cảnh báo vào console.

`rollout.go`: có plan thì GetPlan; không có thì CreateRelease → CreatePlan với một
changeDatabaseConfig spec (UUID, release, targets). Sau đó CreateRollout với parent
plan, không gửi target để tạo đủ stage. Nếu có targetStage, refresh rollout, chạy các
task NOT_STARTED từng stage và chờ DONE/SKIPPED; dừng khi đến environment đích.
Source không gọi IssueService trực tiếp dù README action nói tới rollout issue.

Khi context bị cancel, action liệt kê đủ task run, chọn RUNNING/PENDING, nhóm theo
stage rồi BatchCancelTaskRuns. Plugin ánh xạ sang interrupt/timeout của Jenkins.

`file.go`: recursive glob, sort đường dẫn, lấy version từ đầu filename và bỏ qua file
không có version. Declarative check gửi file riêng; rollout ghép bằng newline.
Source hiện tại **không đọc comment migration-type gh-ost**; protobuf Release.File
cũng không có trường migration type. Vì vậy plugin không tuyên bố hỗ trợ feature đó.

## Mapping

| Action | Plugin Jenkins | Trạng thái |
| --- | --- | --- |
| check, --check-release | command=check, checkRelease | Đã chuyển |
| rollout từ SQL | command=rollout, filePattern, targets | Đã chuyển |
| rollout từ plan | plan | Đã chuyển; bỏ qua files/targets |
| --target-stage | targetStage | Đã chuyển; fail nếu environment không tồn tại |
| service-account / secret | Username with password credential | Đã chuyển; không dùng env secret |
| access-token | Secret text credential | Đã chuyển; nhận token đã exchange |
| --timeout | httpTimeoutSeconds | Đã chuyển |
| --project, --targets | project, targets | Đã chuyển; không mặc định vào sample/prod |
| --file-pattern | filePattern, workspace FilePath | Ant glob; không phải mọi cú pháp doublestar |
| --declarative | declarative | Đã chuyển; engine/SQL restrictions do server |
| --custom-rules | customRules | Đã chuyển |
| release ID template/timezone | releaseIdTemplate/releaseIdTimezone | Đã chuyển |
| output JSON | output + Bytebase results build action | Đã chuyển; giữ resource tạo một phần |
| VCS release source | vcsType + vcsUrl | Truyền rõ commit URL từ Jenkins checkout |
| VCS PR creator lookup | Không tự lookup | Chưa chuyển; cần integration riêng theo SCM |
| GitHub comment/annotations/step summary | Build action + JSON | Không tự đăng comment |
| GitLab Code Quality JSON | checkResults JSON | Không tạo Code Quality format riêng |
| Azure logging / Bitbucket reports | Build log + JSON | Không gọi API nền tảng khác |
| CLI/server version window | Plugin API baseline 3.22.1 | Không áp dụng version của CLI vào version plugin |
| Cancel on context termination | Cancel task runs khi abort/timeout | Đã chuyển; best effort, đủ pagination |
| Transient retries | Retry đọc/check; mutation chỉ refresh auth | Tránh tự phát lại mutation bị mất response |

## Khác biệt có chủ đích

- Không có default project/target sample: tránh chạy nhầm database khi thiếu cấu hình.
- Không có file version hợp lệ: fail thay vì gửi release rỗng.
- Target stage không tồn tại: fail thay vì báo thành công mà chưa chạy stage yêu cầu.
- Plan phải thuộc project, database group phải thuộc project; không trộn group với database.
- Validate timeout, URL, workspace path trước khi dùng. Không có tùy chọn tắt TLS.
- Overall rollout wait timeout được thêm vào; synchronous step không resume qua restart.
- Bytebase approvals/policies vẫn được server thực thi. Jenkins input là một cổng bổ sung,
  không tự tạo hoặc tự approve Bytebase Issue.

## Kiến trúc

`BytebaseGlobalConfiguration` và `BytebaseServer` quản lý server/credential IDs như
Tower installations. `BytebaseBuilder` là Jenkins SimpleBuildStep với symbol
`bytebase`, dùng chung Pipeline/Freestyle/Snippet Generator. `WorkspaceFiles` đọc SQL
qua remoting, `BytebaseClient` gọi RPC trên controller và resolve credentials trong
scope build, `BytebaseRunner` điều phối check/rollout/cancel. `BytebaseResultAction`
lưu report trong build, còn output JSON nằm trên workspace để archive.

Không copy TowerConnector vì protocol/auth/domain khác. Không copy toàn bộ CLI Go
hoặc kéo dependency Bytebase backend vào JVM. Không dùng shell để ghép tham số/secret.
