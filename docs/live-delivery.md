# Bàn giao và bằng chứng live — 2026-11-07

Plugin **0.2.0-SNAPSHOT** đã chạy trọn luồng native trên Jenkins 2.568.3 / Bytebase
3.22.1 / Oracle lab. Tài khoản thực thi là `jenkins@service.bytebase.com`, lấy từ
credential Username with password `bytebase-jenkins-sa` của connection `bytebase-default`.
Pipeline native không chứa Service Key, username hay `withCredentials` Bytebase.

Bản bàn giao cuối: **0.2.1-SNAPSHOT**, bổ sung migration cấu hình XStream để job
Freestyle đã lưu trước khi có các trường inspection tiếp tục chạy sau nâng cấp.
Luồng rollout API giữ nguyên; source trước/sau và HPI0.2.0 đã lưu để đối chiếu.
Bản cuối đã cài, SHA-256 trên controller trùng HPI bàn giao; hai agent online và
không có failed plugin sau safeRestart. Lượt Freestyle #2 đã phát hiện lỗi null
khi nâng cấp, #3 xác nhận sửa mà không cần cập nhật XML job cũ.

## Kết quả kiểm chứng

| Phạm vi | Bằng chứng | Kết quả |
| --- | --- | --- |
| CLI/native, GitOps ban đầu | [Comparison #4](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/cli-native-comparison/4/) | CheckRelease giống nhau; login 200, ba API metadata 403; preflight CLI cũ fail |
| Cùng key, thêm Workspace DBA tạm | [Comparison #5](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/cli-native-comparison/5/) | CheckRelease vẫn giống nhau; metadata đều 200; preflight CLI cũ pass |
| Khôi phục GitOps | [Comparison #6](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/cli-native-comparison/6/) | CheckRelease vẫn giống nhau; metadata trở lại 403; chứng minh ảnh hưởng của quyền |
| Strict preflight → review → prepare → input → execute → DONE | [Native rollout #2](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/native-lab-rollout/2/) | SUCCESS, review 0 ERROR / 2 WARNING, không thay SQL policy |
| Hậu kiểm DONE, chặn replay và thiếu credential | [Read-only verification #4](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/native-lab-verification/4/) | SUCCESS; metadata403 là kết quả chẩn đoán dự kiến, không được ghi là preflight pass |
| Freestyle cũ sau nâng cấp, không resave XML | [Freestyle #3](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/native-sql-check-freestyle/3/) | SUCCESS trên0.2.1; hồi quy null trường mới đã sửa |
| Bản cuối0.2.1: CLI parity và hậu kiểm DONE | [Comparison #7](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/cli-native-comparison/7/), [Verification #5](https://jenkins.apps.drgdevlab.com/job/bytebase-plugin-poc/job/native-lab-verification/5/) | SUCCESS, role GitOps nguyên trạng |
| Java17/21, auth, isolation, timeout/abort, API payloads, UI config, gates | Jenkins integration harness | 15 integration tests pass; 7 generated tests pass, 1 localization test skip; SpotBugs 0 |
| Demo source selection/immutability/inspection | Python tests sau V4 | 15 tests, 1 Flyway-specific skip, không có failure |

Hai connection cùng URL nhưng khác account, sai key, override không tồn tại, denied
project không fallback, cancellation/pagination, declarative payloads được kiểm chứng bằng Jenkins harness với HTTP server kiểm soát được request.
Không giả lập các kết quả đó thành rollout live. Chưa chạy Jenkinsci/GitHub Actions
trên remote; source chưa push và plugin chưa phát hành lên Update Center.

## V4 thực sự được triển khai

- Project: `projects/database-demo-ops`.
- Target: `instances/oracle-cloud-free-sge0/databases/DEMO_APP_A`, `environments/develop`.
- File mới: `V4__jenkins_plugin_evidence.sql`, chỉ tạo bảng synthetic
  `DEMO_PLUGIN_EVIDENCE`, ba cột và primary key. Không DML, drop hoặc reset schema.
- SHA-256 SQL: `724c4c6bac6adcf36700511460143a0325629af144af519989e1762efba59ecb`.
- Release: `jenkins-plugin-evidence-20261107-RC00`, chứa đúng một file version 4.
- [Plan 104](https://bytebase.apps.drgdevlab.com/projects/database-demo-ops/plans/104),
  task 104, task run 105: **DONE**, creator `users/jenkins@service.bytebase.com`.
- Snapshot trước approval: một task đúng target, `NOT_STARTED`; API policy xác nhận
  `automatic=false`. Approval Jenkins được thực hiện qua UI bằng tài khoản đăng nhập.
- Sau approval, pipeline kiểm tra lại strict preflight và ready, rồi chạy đúng plan104.
- Oracle query xác nhận `CURRENT_SCHEMA=DEMO_APP_A`, bảng mới đọc được và có 0 hàng.
  Metadata có đủ ba cột. Revision4/sheet/release đều trùng SHA-256 của SQL archive.
- Revision1/2/3, tên resource, checksum và thời điểm tạo giữ nguyên; không replay V1–V3.

Lượt rehearsal #1 bị từ chối ở CreateRelease do template thử thiếu placeholders.
Bytebase kiểm tra template trước khi ghi resource; job thử được sửa sang
`jenkins-plugin-evidence-{date}-RC{iteration}`. Bản plugin vẫn giữ default hợp lệ
`release_{date}-RC{iteration}`. Không tự retry mutation hay thay policy để vượt lỗi.

## Trạng thái sau bàn giao

Workspace DBA chỉ được thêm tạm theo ủy quyền test, đã gỡ; Jenkins-SA trở về **GitOps
Service Agent**. Service Key và Jenkins credential không bị reset/thay. Community
không hỗ trợ custom role; không bật trial hoặc bypass license guard.

Job writer SCM chính được tạm disable trong rehearsal, rồi enable lại với nguyên
`SCMBinder`; không đổi Jenkinsfile/script path của job. Job `native-lab-rollout` đã
disable để tránh chạy lại V4, vẫn giữ toàn bộ artifacts và lịch sử. Auto-rollout và
SQL policies không thay đổi. V4 được giữ trong source demo làm bản ghi migration đã
apply trên lab; không sửa checksum, không dùng lại version4 cho migration khác.

Pipeline `Jenkinsfile.bytebase-plugin` trong repo demo vẫn mặc định
`ENABLE_ROLLOUT=false`, chưa chuyển job chính. Với quyền GitOps hiện tại, strict
metadata preflight của pipeline này sẽ fail có chủ đích; cần quyết định cấp quyền
đọc metadata trước khi dùng nó thường xuyên. Test connection chỉ xác nhận login.

Gói evidence cục bộ cùng cấp repo: `../bytebase-plugin-delivery-20261107/` gồm HPI,
source snapshots, pipeline rehearsal thực tế, logs, JSON API/Jenkins, screenshots,
`verify_evidence.py`, SHA-256 manifest và báo cáo HTML. HPI0.2.0 dùng cho rollout có SHA-256:
`f29171811f906a33e37436aadcc233f23d05de80be2fbb38b3e1068569d94e8d`.
HPI0.2.1 cuối có SHA-256:
`158bf14699fab201b540fc84c75f798b1e0416111a8efa4c727b1f9666eb0d37`.
