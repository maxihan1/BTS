<!-- FR-IM-02 Import 매핑 UI 백엔드 PR-A 스펙 — 기반(AWAITING_MAPPING) + 필드 매핑(analyze/validate/confirm) + 매핑-aware CSV 파서 -->

# FR-IM-02 — Import 매핑 UI (백엔드 PR-A: 기반 + 필드 매핑) — 스펙

- 관련 FR: FR-IM-02 | BC: search-export-import (`com.bts.search.imports`)
- **이번 범위: PR-A — 기반 + 필드 매핑** (D1-D5의 필드 매핑 부분)
- 후속: PR-B(사용자 매핑, 전 작성자) · PR-C(값 매핑 status/type/priority) · 프론트 D6/D7 마법사
- ADR: [../decisions/2026-07-03-fr-im-02-import-mapping.md](../decisions/2026-07-03-fr-im-02-import-mapping.md)
- 선행: FR-IM-01 (완료)

## 개요

FR-IM-01은 CSV 헤더를 canonical 이름(`summary`/`priority`/…)으로만 인식했다. FR-IM-02는 **임의 헤더를 BTS 대상 필드에 매핑**해 Import하게 한다. 흐름은 2단계 — **분석(analyze) → 매핑 → 실행**. PR-A는 그 **기반 + 필드 매핑**을 완성한다. 사용자 매핑(PR-B)·값 매핑(PR-C)은 후속이며, PR-A의 엔드포인트는 이를 forward-compatible하게 설계한다(요청/응답 optional 필드로 확장).

## Maxi 결정 (2026-07-03)

- 흐름: 2단계(analyze→map→run), 기존 즉시-업로드 하위호환 유지.
- 사용자 매핑: **전 작성자 필드**(reporter/assignee + 댓글/worklog/첨부/changelog 작성자) — **PR-B**.
- 값 매핑: **status/type/priority 값** 포함 — **PR-C**.
- 분할: 순차 에픽 3-PR(A/B/C) + 프론트.

## 사용자 시나리오 (Given-When-Then) — PR-A

- **S1 필드 매핑**. Given "제목,설명,우선도" 헤더 CSV. When 분석 후 제목→summary·설명→description·우선도→priority 매핑 확정. Then 워커가 그 매핑대로 컬럼을 읽어 이슈를 생성한다.
- **S2 미매핑 처리**. Given "비고" 컬럼을 미매핑. When 확정. Then "비고"는 무시(warning)되고 이슈는 정상 생성된다.
- **S3 검증 실패**. Given summary에 매핑된 컬럼 없음. When 확정 시도. Then 422 + `SUMMARY_NOT_MAPPED`, 저장·enqueue 안 함.
- **S4 하위호환**. Given 기존 `POST /api/v1/imports`. When 매핑 없이 호출. Then FR-IM-01 canonical 동작 그대로(회귀 0).
- **S5 JSON**. Given JSON 업로드. When 분석. Then 소스 필드는 canonical 고정(필드매핑 단계 스킵), 사용자/값 매핑(PR-B/C)만 대상.

## 기능 요구사항 (FR) — PR-A

- **FR1 분석**. 업로드 파일을 MinIO에 저장하고 `ImportJob(status=AWAITING_MAPPING)` 생성. CSV 헤더(소스 필드) + 샘플 행 N=5 + 대상 필드 카탈로그 반환. 크기/형식/권한 검증은 기존 accept와 동일 재사용(413/400/403).
- **FR2 대상 필드 카탈로그**. 매핑 가능한 11개 flat 필드 — `summary`(필수)·`description`·`type`·`priority`·`reporter`·`assignee`·`labels`(multi)·`component`(multi)·`status`·`fixVersion`(multi)·`affectsVersion`(multi) + `IGNORE`. 댓글/worklog/첨부/changelog는 카탈로그 제외(JSON 전용·복합 구조).
- **FR3 필드 매핑 검증 API**. 제안된 필드 매핑을 저장·전환 없이 검증. errors(차단)·warnings(허용) 반환. CSV만 필드매핑 검증 대상, JSON은 canonical이라 필드매핑 검증 스킵.
- **FR4 매핑 확정+실행**. 검증 통과 시 `import_mappings` 저장 + `AWAITING_MAPPING→PENDING` 전환 + enqueue(outbox, 영속과 동일 트랜잭션). 이후는 기존 워커 경로.
- **FR5 매핑-aware CSV 파서**. CSV는 필드 매핑(source header→target field)으로 컬럼 해석. 매핑 없으면 canonical 폴백(하위호환). JSON은 canonical 유지. 매칭은 헤더 trim+lowercase(canonical 동형).
- **FR6 CHECK 제약 확장**. `chk_import_jobs_status`에 `AWAITING_MAPPING` 추가. 워커는 `PENDING`만 클레임(AWAITING_MAPPING 실행 금지). CleanupWorker가 방치 AWAITING_MAPPING도 TTL 정리.

## API 인터페이스 (REST) — PR-A

기존(불변): `POST /api/v1/imports` · `GET /api/v1/imports/{jobId}` · `GET /api/v1/imports/{jobId}/errors`.

### 신규 1 — 분석
```
POST /api/v1/imports/analyze          (multipart: file, projectKey, format)
→ 200 { jobId, status:"AWAITING_MAPPING", format,   // 동기 완결(분석결과 body 반환)
        sourceFields:[{name}], sampleRows:[[cell,...]],   // JSON이면 sourceFields=canonical 고정
        targetFields:[{key,label,required,multi}] }
```
권한: CREATE_ISSUE coarse 게이트 fail-fast(기존 accept 동형). 분석은 전체 파싱이 아니라 **헤더 + 샘플 N=5행 조기중단** 읽기(대용량 방어) — 파서에 bounded read 추가.

### 신규 2 — 검증 (필드 매핑)
```
POST /api/v1/imports/{jobId}/mapping/validate
body { fieldMappings:[{sourceField,targetField}] }        // PR-B/C에서 userMappings/valueMappings 추가(optional)
→ 200 { valid, errors:[{code,message,field?}], warnings:[{code,message,field?}] }
```
소유권 검증(타인 job 404). `AWAITING_MAPPING` 상태에서만(그 외 409).

### 신규 3 — 확정+실행
```
POST /api/v1/imports/{jobId}/mapping
body { fieldMappings:[...], dryRun? }                      // PR-B/C에서 userMappings/valueMappings 추가(optional)
→ 200 { jobId, status:"PENDING", ... }                    // 이후 GET 폴링은 기존 경로
```
검증 실패 → 422. `AWAITING_MAPPING` 아니면 409(`MAPPING_STATE_CONFLICT`). 저장+전환+enqueue는 outbox.

### 에러/경고 코드 (PR-A)
- errors(차단): `SUMMARY_NOT_MAPPED` · `DUPLICATE_TARGET`(둘 이상 소스→같은 target) · `UNKNOWN_TARGET`(카탈로그 밖) · `UNKNOWN_SOURCE`(감지 헤더 밖) · `MAPPING_STATE_CONFLICT`(409).
- warnings(허용): `SOURCE_FIELD_IGNORED`(감지됐으나 미매핑/IGNORE).

## 데이터 모델 변경 (V606) — PR-A

```sql
-- import_jobs.status CHECK 확장 (기존 5행 모두 새 CHECK 만족 — 안전)
ALTER TABLE import_jobs DROP CONSTRAINT chk_import_jobs_status;
ALTER TABLE import_jobs ADD CONSTRAINT chk_import_jobs_status
  CHECK (status IN ('AWAITING_MAPPING','PENDING','RUNNING','COMPLETED','FAILED'));

CREATE TABLE import_mappings (
  import_job_id UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
  source_field  TEXT NOT NULL,
  target_field  TEXT NOT NULL,                 -- 카탈로그 키 또는 'IGNORE'
  PRIMARY KEY (import_job_id, source_field)
);
-- import_user_mappings(PR-B) · import_value_mappings(PR-C)은 각 PR에서 추가.
```
- FK `ON DELETE CASCADE` — CleanupWorker 하드삭제 시 매핑 동반 삭제(join-table-fk-cascade 교훈).
- `db/codegen/init_codegen.sql` jOOQ codegen 미러 필수. `SchemaMigrationImportTest` import_mappings + CHECK 검증 갱신.
- V번호(V606) 머지 직전 재확인(동시 브랜치 충돌).

## 엣지 케이스 — PR-A

- 파일이 analyze 후 변경? 불가 — analyze가 persist하므로 고정.
- 같은 소스가 2개 target에 → `DUPLICATE_TARGET`(multi 포함 각 target 1회, IGNORE 제외).
- 헤더 중복(동명 컬럼) 매핑 → 기존 buildColumnIndex putIfAbsent(첫 컬럼) 동작 유지, 문서화.
- summary 매핑 컬럼값이 빈 행 → 기존 FR-IM-01 행별 best-effort VALIDATION 실패(불변).
- AWAITING_MAPPING job 방치 → CleanupWorker TTL 정리(교착 방지).
- JSON + fieldMappings 전달 → JSON은 canonical, fieldMappings 검증/적용 no-op(무시).
- dryRun 확정 → 저장·전환·enqueue 동일, 워커가 dryRun 검증만(기존 동형).
- 확정 후 재확정(PENDING에서 confirm) → 409.

## 제약 조건

- BC 격리: `IssuePriority` 등 issue-tracking 지식 직접참조 금지(로컬 미러 유지).
- `IssueImportPort` 시그니처 불변.
- 기존 즉시 경로·워커·파서 canonical 동작 회귀 0.
- 완제품 품질(DEVELOPMENT.md §1) — 임시코드 금지.
- component/version 값은 값 매핑 대상 아님(PR-C도 status/type/priority만) — 기존 name-match best-effort 유지.

## 측정 가능한 완료 기준 — PR-A

- [ ] analyze→validate→confirm→worker→COMPLETED happy path 통합테스트(임의 헤더 CSV) 1개 이상.
- [ ] 매핑-aware 파서: 임의 헤더 CSV가 매핑대로 파싱됨 단위테스트 + canonical 폴백 회귀테스트.
- [ ] 검증 규칙 4 에러코드·1 경고코드 단위테스트.
- [ ] V606 마이그레이션 + SchemaMigrationImportTest import_mappings·CHECK 검증.
- [ ] analyze bounded read(헤더+5행 조기중단) 단위테스트.
- [ ] 기존 FR-IM-01 테스트 전부 green(회귀 0).
- [ ] ktlint/detekt clean, verify-master-plan 통과.

## 스펙 deviation (product 대비 — 전수 동기화)

- product §4.2 D3 단일 `import_mappings`는 PR-A로 충족. 사용자 매핑용 `import_user_mappings`(PR-B)·값 매핑용 `import_value_mappings`(PR-C)는 후속 확장 — product 문서 D3 본문에 3-PR 구조/확장 테이블 명시.
- FR 수 불변 → fr-index 카운트 무영향.

## Brainstorming Check

✅ 통과 (자기-비평 1회). 발견 갭 6건 스펙 반영(analyze 검증 재사용·헤더 매칭 규칙·JSON canonical 스킵·AWAITING_MAPPING 상태 제약·bounded read·샘플 N=5). 범위 경계 2건(전 작성자 사용자매핑·값 매핑)은 Maxi 결정으로 PR-B/PR-C 분리.
