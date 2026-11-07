# Đối chiếu CLI 3.22.1 và plugin với GitOps Service Agent

Kiểm chứng ngày 2026-11-07 trên Jenkins thật và Bytebase 3.22.1.
Job: `bytebase-plugin-poc/cli-native-comparison`, build **#4 / #5 / #6**, plugin0.2.0 sau safeRestart đã được người dùng cho phép.

| Build | Role | Metadata / preflight CLI cũ | CheckRelease CLI/native |
| --- | --- | --- | --- |
| #4 | GitOps | 403 / fail | Giống nhau, 0 ERROR / 1 WARNING |
| #5 | GitOps + Workspace DBA tạm, cùng key | 200 / pass | Giống nhau, 0 ERROR / 1 WARNING |
| #6 | GitOps sau khôi phục | 403 / fail | Giống nhau, 0 ERROR / 1 WARNING |

Rollout native và hậu kiểm Oracle: [live-delivery.md](live-delivery.md).

## Cùng đầu vào

- Cùng URL, project, database target và service account `jenkins@service.bytebase.com`.
- CLI bind Jenkins credential `bytebase-jenkins-sa`; plugin chọn connection
  `bytebase-default` đang tham chiếu đúng credential đó.
- Cùng file `.comparison/V3__add_source_system.sql`, cùng version `3`, cùng
  `checkRelease=SKIP`. SQL chỉ được gửi tới CheckRelease, không thực thi.
- SHA-256 SQL: `912591dd4254cc62b7a31a021f97a039af07fd28b98666e91742e0bfe6e5aede`.
- CLI: Docker image `bytebase/bytebase-action:3.22.1`, version được ghi trong cli.log.
- Native plugin active trong lượt đối chiếu mới nhất: **0.2.0-SNAPSHOT**.
  Lượt #3 trước đó dùng 0.1.0 và cho cùng kết quả. Sau safeRestart, 0.2.0 active,
  không có failed plugin, cả hai agent đã online lại và Test connection thành công.

## Kết quả

| Kiểm tra | Kết quả |
| --- | --- |
| CLI check | Exit 0, 0 ERROR / 1 WARNING |
| Native check | SUCCESS, 0 ERROR / 1 WARNING |
| Toàn bộ `checkResults` | Giống nhau sau khi parse JSON; không chỉ so số lượng advice |
| GetCurrentUser bằng cùng account | HTTP 200 |
| GetDatabase bằng cùng account | HTTP 403, thiếu `bb.databases.get` |
| GetProject bằng cùng account | HTTP 403, thiếu `bb.projects.get` |
| GetInstance bằng cùng account | HTTP 403, thiếu `bb.instances.get` |
| Script preflight cũ của database-demo, trong cùng container | Exit 1; curl báo 403 rồi guard database mismatch |

## Kết luận được chứng minh

Credential/Service Key đăng nhập được, cả CLI và plugin đều review SQL thành công.
403 ở đây thuộc các API đọc metadata mà preflight lab bổ sung, không phải CLI
CheckRelease bị lỗi, cũng không đủ để kết luận Service Key sai.

Role [GitOps Service Agent của 3.22.1](https://github.com/bytebase/bytebase/blob/3.22.1/backend/store/predefined_roles.go#L582)
có quyền release/plan/rollout/task run cho automation, nhưng không có ba quyền đọc
metadata phía trên. Role chuyên biệt này không đồng nghĩa với Workspace DBA/Admin.
Lượt #4 giữ nguyên role. Lượt #5 thêm Workspace DBA tạm theo ủy quyền test; lượt #6 kiểm chứng sau khi gỡ. Không reset key, bỏ guard hay thay SQL policy.

Pipeline native lab vẫn giữ guard metadata của pipeline lab cũ. Nó sẽ dừng ở
preflight với role hiện tại, giống inspector cũ, cho tới khi các quyền đọc được xử lý
có chủ đích. Các step plugin `check`/`rollout` tiêu chuẩn không tự thêm GetDatabase,
GetProject hay GetInstance vào luồng của CLI.

## Bằng chứng và chạy lại

Artifacts của build gồm `comparison.json`, `cli.json`, `plugin.json`, `cli.log`,
`legacy-preflight.log` và `rpc-*-http.txt`/`rpc-*.json`. RPC response được lọc field;
không lưu login response/token/key. Container diagnostic đã được Jenkins dừng/xóa.
Bản #4 ở `target/poc/cli-native-comparison-4/` (gitignored); cả ba lượt nằm trong gói delivery cùng cấp repo.

[examples/Jenkinsfile.cli-comparison](../examples/Jenkinsfile.cli-comparison) là pipeline
diagnostic cho Oracle, sử dụng hai helper trong cùng thư mục. Chạy từ SCM có source
plugin; chọn agent có Docker/Python 3 và điền project/target/connection. Đảm bảo
connection đang chọn đúng credential ID dùng cho CLI. Account dùng chung không có
nghĩa cả hai credential có cùng email là chắc chắn cùng key; ở lượt này cùng một
Jenkins credential ID được dùng cho cả hai.

Diagnostic cần `withCredentials` để chạy CLI cũ. Pipeline sử dụng plugin bình thường
chỉ cần serverName, không bind hoặc truyền Service Key cho agent.
