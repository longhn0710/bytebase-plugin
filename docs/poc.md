# Kiểm chứng plugin và PoC trong cụm

Ngày kiểm chứng: **2026-11-07**, múi giờ UTC+07.

Đối chiếu mới nhất với CLI và role GitOps: [cli-parity.md](cli-parity.md).
Plugin 0.2.0 đã active sau safeRestart được người dùng cho phép. Lượt đối chiếu CLI
build #4 dùng 0.2.0; các lượt SQL check ban đầu bên dưới dùng 0.1.0.

Bàn giao mới nhất: [live-delivery.md](live-delivery.md). Native rollout V4 đã SUCCESS,
plan104/task104 DONE và đã đối chiếu Oracle. Các phần PoC 0.1.0 bên dưới là lịch sử
review-only; không mô tả trạng thái hoàn thành mới nhất.

## Kết quả tự động đã chạy

`mvn -B -ntp spotless:apply verify` hoàn tất **BUILD SUCCESS**.
Môi trường: Maven 3.9.11, Corretto JDK 21; biên dịch target Java 17, Jenkins 2.504.3.
Các công cụ build được tải vào cache cục bộ, không cài Java/Maven toàn hệ thống.

- 15 integration test của plugin0.2.1: pass toàn bộ trên Java17 và Java21.
- 8 test do Jenkins plugin harness sinh: 7 pass, 1 skip vì không có properties localization.
- SpotBugs: 0 bugs; Spotless: sạch; dependency/API checks: pass.
- Artifact: `../target/bytebase.hpi` tính từ thư mục docs của repo.
- Báo cáo test: `../target/surefire-reports/` tính từ thư mục docs.

Harness khởi động Jenkins thật và một remote agent cho test Pipeline. Bytebase được
mô phỏng bằng HTTP server theo schema Connect RPC của source tham chiếu. Phần này
kiểm chứng toàn bộ luồng, gồm rollout/cancellation. SQL check đã được kiểm chứng thêm
trên Jenkins và Bytebase thật trong phần PoC live bên dưới.

| Tình huống | Kết quả đã xác nhận |
| --- | --- |
| Pipeline đọc SQL trên remote agent | Version và SQL base64 chính xác, JSON được archive |
| Freestyle + UI config save/reload | Các giá trị build step/server/credential ID được giữ |
| SQL ERROR/WARNING với ba quality gate | Đúng trạng thái build; output/report còn sau fail |
| CreateRelease → CreatePlan → CreateRollout | Đúng resource names, UUID spec, release reference, VCS source |
| Chạy đến environments/test | Chạy stage test và dừng trước prod |
| Có plan, files/targets không hợp lệ | Bỏ qua files/targets như action |
| Không có targetStage | Không gọi GetRollout/BatchRunTasks |
| Environment đích không tồn tại | Fail, không gọi BatchRunTasks |
| Task FAILED | Build fail |
| Timeout và task-run pagination | Hủy RUNNING/PENDING trên đủ trang, nhóm theo stage |
| Abort lúc chờ/đọc HTTP response | Build ABORTED, yêu cầu hủy task run |
| Declarative | Check từng file, rollout ghép có newline |
| Auth token/service account | Đúng bearer, refresh service account khi 401 |
| Mutation trả 503 | Không tự phát lại mutation |
| Credential không tồn tại | Fail trước khi gọi API |
| Path traversal/symlink ngoài workspace | Bị từ chối |
| Nhiều operation trong một build | Giữ đầy đủ report trong một build action |

## PoC live đã chạy trong cụm

- Jenkins **2.568.3**, Java 21; Bytebase **3.22.1**.
- HPI **0.1.0-SNAPSHOT** đã upload bằng Jenkins Plugin Manager, nạp động thành công;
  plugin active, không có failed plugin, không restart controller.
- Các dependency hiện có tương thích; không nâng cấp dependency trên cụm.
- Thêm server `bytebase-lab`, để credential mặc định trống và truyền credential ID
  hiện có qua build step. Không tạo, sao chép hoặc thay đổi credential Bytebase.
- Tạo folder riêng `bytebase-plugin-poc`, chứa Pipeline `native-sql-check` và Freestyle
  `native-sql-check-freestyle`. SQL được đọc trên remote agent, API gọi từ controller.
- Các job hiện có được giữ nguyên. Không thay đổi policy hay quyền trong Bytebase.

| Job / build | Quality gate | ERROR / WARNING | Kết quả |
| --- | --- | --- | --- |
| Pipeline #2 | SKIP | 3 / 4 | SUCCESS; trả đầy đủ advices |
| Pipeline #3 | FAIL_ON_ERROR | 3 / 4 | FAILURE đúng kỳ vọng |
| Pipeline #4 | FAIL_ON_WARNING | 0 / 11 | FAILURE đúng kỳ vọng |
| Pipeline #5 | FAIL_ON_ERROR | 0 / 1 | SUCCESS |
| Freestyle #1 | FAIL_ON_ERROR | 0 / 1 | SUCCESS |

Fixture Oracle ban đầu thiếu comments/defaults; fixture sau bổ sung các phần đó.
Build #4 dùng quoted lowercase identifiers và bị policy Oracle cảnh báo. Fixture
cuối dùng uppercase identifiers, còn một warning về quy tắc tên bảng; FAIL_ON_ERROR
cho phép warning này theo đúng cấu hình. Không đổi policy để làm build pass.
Build #1 lỗi quoting trong script setup PoC, đã sửa trước khi plugin được gọi.

Cả năm build kiểm chứng đều archive SQL và JSON. Trang **Bytebase results** trả HTTP
200 và giữ checkResults cả khi quality gate làm build fail. Console xác nhận dùng
step `bytebase`, không gọi `bytebase-action` hoặc Docker để chạy Bytebase.
Freestyle dùng shell chỉ để tạo SQL fixture trước build step native.

Bằng chứng cục bộ, nằm trong `target/` đã được gitignore:

- `target/poc/summary.json`: job URLs, build status, parameters, advice counts, artifacts.
- `target/poc/*-result.json`: response Bytebase thật.
- `target/poc/*-console.txt`: console từng build.
- `target/poc/example-validation.txt`: Jenkins declarative linter chấp nhận Jenkinsfile mẫu.

PoC live **chỉ review SQL**: không tạo release/plan/rollout, không thực thi DDL/DML
trên database và không restart Jenkins. Rollout/abort/timeout đã pass trong integration
harness; chưa chạy rollout live vì target hiện có là database ứng dụng. Cần chọn
project/database lab và credential có quyền deploy trước khi kiểm chứng phần đó.

## Chạy PoC trực tiếp

1. Cài `target/bytebase.hpi` qua Jenkins Plugin Manager, theo hướng dẫn trong README.
2. Thêm server `bytebase-lab` trong Manage Jenkins → System → Bytebase.
3. Chọn Jenkins credential của service account Bytebase có quyền SQL check trên lab
   project/target. Test connection phải thành công. Nếu credential nằm trong folder,
   đặt job PoC trong folder đó, để credential mặc định của server trống và truyền
   `credentialsId` vào step; nút Test connection toàn cục không dùng được folder credential.
4. Tạo Pipeline lab dùng [examples/Jenkinsfile.poc](../examples/Jenkinsfile.poc).
5. Điền project/database resource thật, chọn `SQL_ENGINE` và `CHECK_RELEASE`, rồi Build.
6. Kiểm tra console, trang **Bytebase results**, artifact `bytebase-poc/result.json`.

PoC này chỉ gọi CheckRelease; câu CREATE TABLE là input để server review, không được
plugin thực thi. Mặc định quality gate là `FAIL_ON_ERROR`; có thể chọn
`FAIL_ON_WARNING` theo policy CI hoặc `SKIP` để quan sát report dù SQL có lỗi.
Fixture hỗ trợ PostgreSQL và Oracle; policy thực tế vẫn có thể trả advices.

Để PoC rollout vào database lab, dùng [examples/Jenkinsfile](../examples/Jenkinsfile),
chọn đúng environment và SQL migration lab. Cấu hình Bytebase auto-rollout/policy cần
phù hợp với Jenkins approval flow; không thay policy chỉ để làm build xanh. Hủy hoặc
xóa job PoC qua Jenkins UI khi không cần giữ kết quả nữa.
