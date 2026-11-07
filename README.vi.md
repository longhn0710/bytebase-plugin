# Bytebase Jenkins Plugin

Plugin Jenkins gọi trực tiếp Bytebase Connect RPC, thay thế `bytebase-action check` và
`bytebase-action rollout`. Không cần cài CLI, Go, Docker hay shell trên agent.

Plugin dùng mô hình server tập trung + Jenkins Credentials + build step như
Ansible Tower plugin. Một implementation dùng chung cho **Pipeline và Freestyle**.

- Jenkins **2.555.3+**, Java **21+**; CI dùng Java 21/25.
- API đích: Bytebase **3.22.1**, đối chiếu thêm với `../bytebase/action` trong workspace.
- Nhánh chuẩn bị hosting dùng snapshot `999999-SNAPSHOT`; các bản `0.2.x` là PoC cục bộ. Xem [phân tích tính năng](docs/action-migration.md) và [kết quả kiểm chứng / PoC](docs/poc.md).

Bằng chứng end-to-end: [bàn giao live, plan104 và đối chiếu CLI](docs/live-delivery.md).

## Build và cài đặt

```sh
mvn -B -ntp verify
```

Artifact: `target/bytebase.hpi`. Trong Manage Jenkins → Plugins → Advanced settings,
upload HPI vào mục Deploy Plugin. Jenkins sẽ thông báo nếu cần restart. Các dependency
`credentials`, `plain-credentials` phải có phiên bản tương thích.

Công cụ build theo [hướng dẫn chính thức Jenkins](https://www.jenkins.io/doc/developer/tutorial/prepare/).

## Service account và connection trong Jenkins System

Cách dùng chuẩn: cấu hình account một lần trong System, Pipeline chỉ chọn `serverName`.
Một account phục vụ nhiều job; khi cần tách nhóm project, thêm connection dùng account khác.
Các connection có thể dùng cùng URL. Quyền trên project do Bytebase kiểm soát; connection
trong System không tạo cơ chế cách ly giữa các team Jenkins.

Tạo credential trong **Manage Jenkins → Credentials → System → Global credentials**:

| Field | Giá trị |
| --- | --- |
| Kind | **Username with password** |
| ID | `bytebase-jenkins-sa` |
| Username | Email service account Bytebase, ví dụ `jenkins@service.bytebase.com` |
| Password | **Service Key** của service account đó |
| Description | `Bytebase Jenkins CI` |

Trong **Manage Jenkins → System → Bytebase → Servers**, thêm:

| Field | Giá trị |
| --- | --- |
| Connection name | `bytebase-default` |
| Bytebase base URL | `https://bytebase.example.com` |
| Service account credential | Chọn `bytebase-jenkins-sa` |

Bấm **Test connection → Save**. Test này xác nhận đăng nhập/kết nối; project permissions
được kiểm tra khi review, inspect hoặc rollout. Không reset Service Key khi đổi tên
connection hay cập nhật plugin. `jenkins.cred` dùng để truy cập Jenkins API, không phải
credential Bytebase. Không đặt service account/key vào Jenkinsfile, shell hoặc artifact.

| Connection | Credential | Phạm vi account được cấp trong Bytebase |
| --- | --- | --- |
| `bytebase-default` | `bytebase-jenkins-sa` | Các project dùng chung |
| `bytebase-team-a` | `bytebase-team-a-sa` | Nhóm project A |
| `bytebase-team-b` | `bytebase-team-b-sa` | Nhóm project B |

Đổi `serverName` để chọn account. Plugin không tự chọn account theo `project`, không
fallback sang connection/account khác khi key sai hoặc permission bị từ chối.
`credentialsId` override vẫn được giữ ở phần advanced để tương thích; ví dụ thông
thường không cần khai báo nó. Secret text access-token auth cũ vẫn được hỗ trợ cho
compatibility; chuẩn cấu hình mới là **service account email + Service Key**.

Plugin quản lý Authorization và xác minh TLS; private CA cần được tin cậy bởi JVM
của controller.

Với JCasC đã được cài:

```yaml
unclassified:
  bytebaseConfiguration:
    servers:
      - name: bytebase-default
        url: https://bytebase.example.com
        credentialsId: bytebase-jenkins-sa
```

## Pipeline

```groovy
pipeline {
  agent any
  stages {
    stage('SQL review') {
      steps {
        bytebase serverName: 'bytebase-default', command: 'check',
          project: 'projects/example',
          targets: 'instances/example/databases/example',
          filePattern: 'migrations/**/*.sql',
          checkRelease: 'FAIL_ON_ERROR',
          output: 'reports/sql-review.json'
      }
      post {
        always {
          archiveArtifacts artifacts: 'reports/sql-review.json', allowEmptyArchive: true
        }
      }
    }
  }
}
```

Step dùng `SimpleBuildStep` của Jenkins và trả về **void**. Đọc output JSON nếu cần
resource names; xem [examples/Jenkinsfile](examples/Jenkinsfile) cho luồng tạo rollout,
Jenkins input approval, rồi chạy plan đã tạo.

**Không truyền `targetStage`**: chỉ tạo release, plan và rollout; plugin không gọi
BatchRunTasks. **Bytebase server vẫn có thể tự chạy** theo policy auto-rollout.
Để dùng Jenkins input approval trước khi chạy, project Bytebase phải được cấu hình
policy phù hợp. Plugin không thay đổi hoặc bỏ qua approval/policy trên server.

**Có `targetStage`**: plugin chạy tuần tự các stage đến environment đích, dừng nếu
FAILED/CANCELED hoặc timeout. Nếu environment không tồn tại, build fail trước khi
plugin bắt đầu bất kỳ task nào. `plan` phải thuộc `project`; khi có `plan`, plugin
bỏ qua `targets` và `filePattern`.

Freestyle: Add build step → **Bytebase: check SQL or rollout database changes**.
Các tùy chọn cũng có trong Pipeline Syntax / Snippet Generator.

## Tùy chọn

| Tùy chọn | Mặc định / hành vi |
| --- | --- |
| `serverName`, `command`, `project` | Bắt buộc; `command`: `check`, `rollout` hoặc `inspect` |
| `targets` | Chuỗi ngăn cách bằng dấu phẩy, khoảng trắng hoặc xuống dòng; bắt buộc trừ rollout với plan |
| `filePattern` | Ant glob tương đối với workspace; hỗ trợ `**/*.sql` |
| `credentialsId` | Advanced override; rỗng dùng credential của connection |
| `expectedUrl` | Guard tùy chọn: URL connection phải khớp trước mọi RPC |
| `inspectionMode` | `preflight`, `ready`, `done`; chỉ dùng với `inspect` |
| `expectedHost`, `expectedPort`, `expectedService` | Bắt buộc cho Oracle preflight |
| `declarative` | `false`; tính năng experimental của Bytebase |
| `checkRelease` | `SKIP`, `FAIL_ON_WARNING`, `FAIL_ON_ERROR` |
| `customRules` | Rỗng; natural-language AI rules, áp dụng khi check |
| `plan` | Rỗng; resource `projects/{project}/plans/{plan}` |
| `targetStage` | Rỗng; resource `environments/{environment}` |
| `releaseIdTemplate` | `release_{date}-RC{iteration}`; server xử lý template |
| `releaseIdTimezone` | `UTC`; IANA timezone |
| `output` | `bytebase-result.json`; chuỗi rỗng tắt ghi workspace |
| `httpTimeoutSeconds` | `120`; từ 1 đến 600 giây cho mỗi RPC |
| `waitTimeoutSeconds` | `3600`; thời gian chờ rollout, kiểm tra giữa các RPC/poll |
| `pollIntervalSeconds` | `5`; từ 1 đến 300 giây |
| `vcsType`, `vcsUrl` | Tùy chọn, phải truyền cùng nhau; metadata commit cho release |

Target hỗ trợ `instances/{instance}/databases/{database}`,
`projects/{project}/instances/{instance}/databases/{database}`, hoặc **một**
`projects/{project}/databaseGroups/{group}`. Không trộn database group với database.

Versioned SQL lấy version theo `^[vV]?(\d+(\.\d+)*)`, ví dụ
`V3__add_source_system.sql` → `3`. File không có version được bỏ qua và ghi log;
không có migration hợp lệ thì fail. File được sắp xếp theo đường dẫn như action.
Declarative check giữ từng file; declarative rollout ghép các file bằng newline,
dùng version UTC `yyyyMMdd.HHmmss`. Các giới hạn SQL/schema engine do Bytebase áp dụng.

## Kết quả và vận hành

Build có trang **Bytebase results** lưu kết quả của mọi lần gọi step, kể cả quality
gate fail. Output JSON tương thích action: `checkResults` hoặc `release`, `plan`,
`rollout`. Khi một bước tạo resource thất bại, các resource đã tạo vẫn có trong kết quả.
Dùng `archiveArtifacts` để giữ JSON từ workspace agent sau khi workspace bị xóa.

Credentials chỉ dùng trong controller; không truyền sang agent hoặc command line.
SQL được đọc bằng Jenkins FilePath, hỗ trợ remote agent. Pattern/output không được
thoát workspace bằng đường dẫn tuyệt đối, `..` hoặc symlink. Giới hạn mỗi thao tác:
1.000 file, tổng SQL 16 MiB, response 16 MiB. Ghi file không bảo vệ được trước một
process đồng thời chủ động đổi symlink; chỉ chạy checkout tin cậy khi dùng credential deploy.

HTTP không follow redirect. Login bằng service account được refresh một lần khi API
trả 401. RPC đọc/check retry tối đa 3 lần cho HTTP 429/502/503/504; RPC tạo/chạy/hủy
không tự retry lỗi transient/network vì có thể nhân đôi mutation. Khi mất response
mutation, kiểm tra Bytebase và output đã lưu trước khi chạy lại build.

Abort/rollout timeout yêu cầu hủy task run RUNNING/PENDING, theo đủ các trang và nhóm
theo stage. Đây là best effort: lỗi mạng hoặc server có thể khiến task tiếp tục chạy;
hủy không rollback SQL đã thực thi. Plugin không xóa resource Bytebase đã tạo.

Build step đồng bộ này **không resume rollout qua restart controller**. Lên lịch
restart khi không có build đang chạy; sau restart, kiểm tra trạng thái Bytebase rồi
chạy lại với `plan` hiện có. Không tự động phát lại tạo release/plan.

Plugin không tự đăng comment lên GitHub/GitLab/Bitbucket/Azure. Dùng JSON trong bước
publish hiện có nếu cần. Không tự suy đoán PR author/VCS identity từ Jenkins user;
server vẫn kiểm soát truy cập bằng service account/token đã cấu hình.

## Native inspection cho pipeline Oracle lab

`command: 'inspect'` chỉ gọi API đọc, không tạo release/plan/rollout hoặc chạy task.
Authentication dùng cùng connection/credential như các lệnh khác, trên controller.

- `preflight`: yêu cầu một database target và `targetStage`; xác minh project, ACTIVE,
  environment và sync OK; kiểm tra Oracle ADMIN datasource khớp expected host/port/service.
  Chỉ xuất các field cần kiểm tra, không ghi password/data source secrets vào report.
- `ready`: yêu cầu plan thuộc project, đúng một stage/target và task DATABASE_MIGRATE;
  mọi task phải NOT_STARTED/PENDING. Đây là kiểm tra sau khi rollout được tạo, không
  thay thế việc xác nhận policy auto-rollout trước khi tạo rollout.
- `done`: kiểm tra cùng mapping, có task và mọi task DONE. SKIPPED bị từ chối.

Output inspect chứa `serverVersion`, `database`, `instance` cho preflight hoặc
`rollout` và `rolloutSnapshot` cho ready/done, được giữ cả khi validation fail.
Preflight này dành cho Oracle lab; check/rollout vẫn hỗ trợ các engine Bytebase cung cấp.

## CI của repository plugin

`Jenkinsfile` ở gốc dùng `buildPlugin` của
[jenkins-infra/pipeline-library](https://github.com/jenkins-infra/pipeline-library),
theo [chuẩn CI plugin Jenkins](https://www.jenkins.io/doc/developer/publishing/continuous-integration/).
Matrix Linux Java 21/25 khớp baseline 2.555.3. Jenkins riêng cần cấu hình thư viện,
agent/tool theo quy ước đó; không chỉ copy Jenkinsfile vào một job không có library.
Repo hiện thuộc tài khoản cá nhân, chưa tự động được onboard lên ci.jenkins.io.

GitHub Actions build/test bằng `mvn clean verify` trên Java 21/25, lưu HPI và test
reports; Jenkins Security Scan dùng reusable workflow chính thức; Dependabot cập
nhật Maven/actions. Workflow không publish release hay deploy Jenkins/Bytebase.
`examples/Jenkinsfile*` là pipeline sử dụng plugin, tách khỏi CI build plugin.

Đối chiếu thực tế CLI 3.22.1 / plugin / GitOps Service Agent: [docs/cli-parity.md](docs/cli-parity.md).
