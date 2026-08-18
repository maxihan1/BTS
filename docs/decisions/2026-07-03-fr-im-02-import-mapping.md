<!-- FR-IM-02 Import 매핑 UI의 2단계 흐름(analyze→map→run) + AWAITING_MAPPING 상태 + 명시적 사용자 매핑 지속화 + CSV 자유 필드매핑 결정 ADR -->

# ADR — FR-IM-02 Import 매핑 UI: 2단계 흐름(analyze→map→run) + AWAITING_MAPPING + 명시적 사용자 매핑 지속화

- 날짜: 2026-07-03
- 상태: 제안 (게이트 1 승인 대기)
- 관련 FR: FR-IM-02 (선행 FR-IM-01 완료)
- BC: search-export-import
- 선행 ADR: [2026-07-02-fr-im-01-csv-json-import.md](2026-07-02-fr-im-01-csv-json-import.md)

## 맥락 (Context)

product §4.2는 FR-IM-02를 `ImportMapping` 도메인 · 필드 매핑 + 사용자 매핑 + 미매핑 처리 · `import_mappings(import_job_id, source_field, target_field)` · 매핑 검증 API · 다단계 매핑 마법사 프론트 · E2E로 명세한다. 선행은 FR-IM-01(CSV/JSON Import) — 전체 완료.

FR-IM-02는 FR-IM-01이 **설계 시점에 명시적으로 예정한 확장**이다. 코드베이스 조사 결과.

1. **FR-IM-01 파서는 canonical 컬럼명만 하드코딩 인식.** `ImportRowParser`가 `HEADER_SUMMARY="summary"` 등 상수로 CSV 헤더를 대소문자 무시 매칭한다. KDoc L36 명시 — *"임의 Jira 헤더·자유 매핑은 FR-IM-02(매핑 UI) 몫 — 본 PR은 canonical 컬럼명만 인식한다."*
2. **커맨드 객체가 확장 대비 설계됨.** `IssueImportCommand` KDoc L13 — *"위치 인자 다중 파라미터 대신 커맨드 객체를 채택한 이유는 후속 확장(FR-IM-02 명시적 필드 매핑)에서 포트 시그니처를 변경하지 않고 필드를 추가할 수 있기 때문."* 즉 매핑 결과가 이 커맨드로 흘러들어도 `IssueImportPort` 시그니처는 불변.
3. **현재 흐름은 업로드→즉시 처리.** `POST /api/v1/imports`(multipart)가 파일을 MinIO에 저장하고 `ImportJob`을 `PENDING`으로 생성 후 pgmq enqueue → `ImportJobWorker`가 `RUNNING`으로 클레임해 파싱·생성 → `COMPLETED`/`FAILED`. `ImportJobStatus` = {PENDING, RUNNING, COMPLETED, FAILED}. 사용자가 컬럼을 확인·조정할 지점이 없다.
4. **사용자 매핑은 현재 이메일 자동해석뿐.** `IssueImportCommand.reporterEmail`/`assigneeEmail`을 구현체가 `UserLookupPort.resolveByEmails`로 해석, 미매칭 시 reporter→requester·assignee→미배정 폴백. 사용자가 개입해 재지정할 수 없다.
5. **DB status CHECK 제약 존재.** `V604__import_jobs.sql`의 `chk_import_jobs_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED'))`. 상태 추가 시 확장 필수. `SchemaMigrationImportTest`가 스키마를 검증한다.
6. **search 모듈 마이그레이션 대역 V600~. 현재 V605.** 다음 V606. `db/codegen/init_codegen.sql` jOOQ codegen 미러 필수. 머지 직전 V번호 재확인(동시 브랜치 충돌).
7. **작업 범위.** Maxi 결정으로 백엔드는 **순차 에픽 3-PR**로 분할(사용자/값 매핑 확장으로 범위 증가). 이 ADR은 FR-IM-02 전체 아키텍처를 담되, **이 PR은 PR-A(기반 + 필드 매핑)** 만 구현한다.
   - **PR-A (이 PR)**. V606(status CHECK + import_mappings), AWAITING_MAPPING, analyze, 매핑-aware CSV 파서, 필드 매핑 validate/confirm, 워커 통합.
   - **PR-B (후속)**. 사용자 매핑 — **전 작성자 필드**(reporter/assignee + 댓글/worklog/첨부/changelog 작성자, Maxi 결정). `import_user_mappings` + distinct 수집 + 프로세서 해석 + VO `authorUserId`.
   - **PR-C (후속)**. 값 매핑 — **status/type/priority 값**(Maxi 결정). `import_value_mappings` + distinct 값 수집 + 프로세서 값 치환.
   - **프론트 D6/D7 (후속)**. 다단계 매핑 마법사(필드/사용자/값) + E2E.

## 결정 (Decision)

### D1. 2단계 흐름 — analyze → map → run (기존 즉시 경로 하위호환 유지)

기존 `POST /api/v1/imports`(canonical 자동매핑, 즉시 enqueue)는 **변경 없이 유지**한다(하위호환). 매핑을 원하는 사용자를 위한 새 경로를 추가한다.

```
analyze(업로드+persist) ─▶ AWAITING_MAPPING ─▶ [필드/사용자 매핑 확정] ─▶ PENDING ─▶ (기존 워커) RUNNING ─▶ COMPLETED/FAILED
```

- **분석(analyze)**. 파일을 MinIO에 저장하고 `ImportJob`을 신규 상태 `AWAITING_MAPPING`으로 생성. 파서로 소스 필드(CSV 헤더)와 샘플 행을 감지해 반환. 파일을 두 번 업로드하지 않도록 이 단계에서 persist(대용량 UX).
- **매핑 확정**. 사용자가 확정한 필드 매핑 + 사용자 매핑을 검증 후 저장하고 `AWAITING_MAPPING → PENDING` 전환 + enqueue. 이후는 기존 워커 경로 재사용.
- **검증(validate)**. 매핑을 저장·전환 없이 검증만 하는 API(product D4 "매핑 검증 API"). 마법사가 각 단계에서 호출.

근거. 서버가 파싱의 진실 원천(대용량·JSON 중첩 안전). 파일 1회 업로드로 왕복 최소화. `import_mappings.import_job_id`가 job 선존을 전제하므로 자연스럽게 정합.

### D2. `ImportJobStatus`에 `AWAITING_MAPPING` 추가 + DB CHECK 확장

Kotlin enum에 `AWAITING_MAPPING` 추가(카운트 가드 없음 — 안전). V606 마이그레이션으로 `chk_import_jobs_status`를 `('AWAITING_MAPPING','PENDING','RUNNING','COMPLETED','FAILED')`로 확장. `SchemaMigrationImportTest` 동반 갱신. **워커는 `PENDING`만 클레임** — `AWAITING_MAPPING`은 폴링 대상 아님(사용자 확정 전 실행 금지). `ImportJobCleanupWorker` TTL이 방치된 `AWAITING_MAPPING` job도 정리하는지 확인(교착 방지).

### D3. 3-매핑 차원 데이터 모델 — `import_mappings`(A) + `import_user_mappings`(B) + `import_value_mappings`(C)

product §4.2 D3의 `import_mappings(import_job_id, source_field, target_field)`는 **필드 매핑** 전용. Maxi가 사용자 매핑(전 작성자)·값 매핑을 확장 결정해, 3개 매핑 차원을 각 PR에서 테이블로 추가한다.

- **(PR-A)** `import_mappings(import_job_id, source_field, target_field)` — 필드 매핑. `source_field`=CSV 헤더, `target_field`=대상 필드 키 또는 `IGNORE`.
- **(PR-B)** `import_user_mappings(import_job_id, source_identifier, target_user_id)` — 사용자 매핑. `source_identifier`=파일 내 사용자 이메일/이름, `target_user_id`=BTS 사용자 UUID(미해석 시 NULL → 폴백). **전 작성자 필드**(reporter/assignee + 댓글/worklog/첨부/changelog 작성자)에서 distinct 수집.
- **(PR-C)** `import_value_mappings(import_job_id, target_field, source_value, target_value)` — 값 매핑. status/type/priority에 한정(component/version은 기존 name-match 유지).

**스펙 deviation**. product D3 단일 테이블 → 3 테이블. product 문서 D3 본문 갱신(전수 동기화). FR 수 불변(fr-index 무영향).

사용자 매핑 흐름(PR-B). analyze 후 필드 매핑이 정해지면, 작성자로 매핑된 컬럼/JSON 노드에서 **distinct 식별자 수집** → `UserLookupPort.resolveByEmails` 자동해석 → 미해석/재지정을 사용자가 확정 → `import_user_mappings` 저장. 워커는 프로세서에서 이 매핑을 1회 로드해 행별 in-memory 해석 → `IssueImportCommand.reporterUserId`/`assigneeUserId` + VO `authorUserId`로 전달. 어댑터는 userId 우선(없으면 이메일 폴백=하위호환).

### D4. CSV 자유 필드매핑 + JSON canonical

`ImportRowParser`를 매핑-aware로 확장. **CSV** 는 하드코딩 `HEADER_*` 대신 필드 매핑(source header → target field)으로 컬럼을 해석 — 임의 헤더 자유 매핑. **JSON** 은 Jira REST export 고정 구조라 canonical 유지(필드 매핑 대상 제외). 사용자 매핑은 CSV+JSON 공통 적용. 매핑 미제공(기존 즉시 경로)이면 CSV도 canonical 폴백 — 하위호환.

### D5. 순서 의존 — 필드 매핑 먼저, 사용자 매핑 나중

사용자 식별자는 reporter/assignee로 매핑된 컬럼 안에 있다. 따라서 마법사/API 순서는 **필드 매핑 → (그 매핑 기준) distinct 사용자 수집 → 사용자 매핑 → 확정**. distinct 사용자 수집 API는 필드 매핑을 파라미터로 받는 2차 분석이다(전 컬럼 distinct 값 사전수집은 대용량 비용 → 회피).

## 결과 (Consequences)

### 긍정

- 사용자 CSV가 Jira 관례 컬럼명을 안 써도 됨(핵심 가치). 사용자 매핑으로 이메일 불일치 대량 마이그레이션 정확도 상승.
- 기존 즉시 경로·워커·`IssueImportPort` 시그니처 불변 — 회귀면 최소.
- job 선존 2단계 흐름이 `import_mappings.import_job_id` FK와 자연 정합.

### 부정 / 위험

- `AWAITING_MAPPING` job이 확정 없이 방치될 수 있음 → CleanupWorker TTL로 정리 필요(교착 방지).
- 상태 추가가 DB CHECK·SchemaMigrationTest·워커 폴링 술어 3곳에 파급 — 전수 반영 필요.
- 사용자 매핑 저장 테이블 신설 — product 문서 D3 deviation 동기화 필요.

### 후속 (이 PR 범위 밖)

- **PR-B** 사용자 매핑(전 작성자) · **PR-C** 값 매핑(status/type/priority) · **프론트 D6/D7** 다단계 마법사 + E2E.
- 매핑 템플릿 재사용(여러 job 간 매핑 저장/재적용)은 현재 범위 아님(YAGNI — job별 매핑만).

## 대안 (Alternatives)

- **단일 요청(프론트 파싱)**. 마법사가 브라우저에서 헤더 파싱 후 매핑을 POST에 동봉. 분석 단계 없음. → 프론트가 CSV/JSON 파싱 중복 구현 + 대용량 브라우저 부담. 기각(Maxi 결정 1).
- **경량 사용자 오버라이드(비지속)**. 이메일 자동해석 유지 + 미해석만 요청 시 전달. → 매핑 이력·재현 불가. 기각(Maxi 결정 2).
- **JSON도 자유 필드매핑**. 중첩 경로 노출. → 마법사/파서 복잡도 급증, Jira JSON 고정 구조라 실익 낮음. 기각(Maxi 결정 3).
