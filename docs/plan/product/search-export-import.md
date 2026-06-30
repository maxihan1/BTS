<!-- search-export-import BC — AQL 검색 + Export + Import + REST API + Webhook + PAT 12 FR -->

# search-export-import BC

**소속 FR**. 12개 (SR 4 + EX 2 + IM 2 + API 4).
**책임**. 이슈 검색(AQL/필터/형태소) + Export(CSV/XLSX/비동기) + Import(Jira) + REST API + Webhook + Personal Access Token.
**SDD 참조**. 10장 (검색/Export/Import), 11장 (API).
**다른 BC와의 경계**. issue-tracking BC의 이슈를 검색/Export. 다른 모든 BC의 REST API 엔드포인트는 각 BC에서 정의하되, 본 BC는 그 API의 **표준 규약** (페이지네이션/벌크/에러 응답 포맷)을 책임. **import 금지 — API 호출만**.

## §0 진입 조건

- [ ] identity-access §2.9 (FR-AU-09 PAT 토큰) 완료
- [ ] issue-tracking §2~§6 (검색/Export 대상 데이터 안정) 완료
- [ ] §1 기술 검증 통과 (아래)

## §1 기술 검증

### §1.1 AQL 파서 + PostgreSQL 변환 PoC (3일)

**SDD**. 10장. **checklist.md 위임**. §1.2. **ADR 후보**. 없음.

- [ ] ANTLR 4 문법 정의 (`backend/modules/search-export-import/aql/Aql.g4`)
- [ ] AST → jOOQ Condition 변환기 1차 구현
- [ ] JQL 기본 키워드 (AND/OR/`=`/`!=`/`IN`/`~`/`ORDER BY`) 동작
- [ ] 단위 테스트 50개 통과 (JQL 호환)
- [ ] `pg_trgm` 확장 설치 (텍스트 매칭 가속)

## §2 검색 (FR-SR, 4개)

### §2.1 FR-SR-01 — 이슈 필터 (다중 필드 조합)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `search/filter`

> **백엔드(D1~D5) 완료 (2026-06-23, PR #180)**. 새 BC 신설 없이 **issue-tracking 모듈 확장**으로 구현(ADR `docs/decisions/2026-06-23-fr-sr-01-issue-filter-bc.md` — 논리 소속은 search-export-import 유지, 물리 구현은 issue-tracking). 기존 `GET /api/v1/issues`에 status/assignee/label/component 필터 추가(필드 내 OR + 필드 간 AND). `BoardCardFilter`(shared-kernel) statusKeys 확장 + `buildStatusCondition` + `IssueFilterQueryParser` 신규. visibility 보안 술어 위 AND 결합(우회 불가) + count/content 단일 Condition 재사용. V029 부분 인덱스 `(project_id, current_state_key)`/`(project_id, assignee_id)`. errorCode 결함(400→INTERNAL_ERROR) 동반 교정. **D6 프론트·D7 E2E는 후속 PR**.

- [x] D1. 도메인 — Filter VO (책임. backend-engineer)
- [x] D2. 명세 — 필드 조합 + AND/OR (책임. backend-engineer)
- [x] D3. 데이터 모델 — PostgreSQL 인덱스 (status, assignee_id, project_id, label) (책임. db-engineer)
- [x] D4. 백엔드 — `GET /api/v1/issues?filter=...` jOOQ 동적 쿼리 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 필터 패널 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §2.2 FR-SR-02 — AQL 텍스트 쿼리 (JQL 호환)

**우선순위**. 필수 | **선행**. §1, §2.1 | **Plan slug**. `search/aql`

> **백엔드 코어 MVP 완료 (2026-06-25, PR #189)**. search-export-import 모듈 신설(7번째 BC). 손수 작성 재귀하강 파서(ANTLR 미도입 — ADR `docs/decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md`). 핵심 키워드(`=`,`!=`,`~`,`IN`,`NOT IN`, `AND`/`OR`/`NOT`+괄호 중첩, `ORDER BY`) + 지원 필드(status/label/summary/priority) + AST→jOOQ 변환(IssueSearchPort, issue-tracking 어댑터) + visibility 보안 술어 AND 자동 결합(우회 불가) + BROWSE 권한 게이트 + `POST /api/v1/search/aql` + 단위 50+.
> **D3/D6/D7 완료 (2026-06-25, PR #190) — FR-SR-02 전체 종료**. D3 `pg_trgm` 확장 + `issues.summary` **표현식 trigram GIN 인덱스**(`gin(lower(summary) gin_trgm_ops)` — 백엔드 `~`의 `likeIgnoreCase` lower() 매칭, EXPLAIN 인덱스 사용 검증) + init_codegen 미러. D6 `/search` 라우트 — **자체 경량 토크나이저**(의존성 0) + textarea/overlay syntax highlight(DESIGN.md syntax 토큰 5종 + `--font-mono`, IME composition + 스크롤 동기화) + `searchAql` API/Zod + 결과목록/에러분기/페이지네이션 + Header 검색 아이콘. D7 E2E 4(정상/문법오류/0건/한글 IME). **후속 PR**(범위 외). 함수(`currentUser()`/`now()`/상대날짜), `is EMPTY`/`is NOT EMPTY`, label `~` 인덱스.

- [x] D1. 도메인 — AqlQuery (책임. backend-engineer)
- [x] D2. 명세 — JQL 호환 키워드 목록 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `pg_trgm` 인덱스 (책임. db-engineer)
- [x] D4. 백엔드 — `POST /api/v1/search/aql` AST 변환 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — JQL 50개 쿼리 대응 (책임. backend-engineer)
- [x] D6. 프론트 UI — AQL 입력창 + syntax highlight (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| AQL 100만건 검색 | 1s | ___ |

### §2.3 FR-SR-03 — 필터 저장 및 공유

**우선순위**. 필수 | **선행**. §2.1, §2.2 | **Plan slug**. `search/saved-filters`

> **백엔드 완료 — PR1(#191) + PR2(#193, 2026-06-26)**. PR1. search-export-import **첫 영속성 부트스트랩**(flyway/jOOQ codegen/Testcontainers, V600~V699 — DATA.md) + SavedFilter **CRUD(PRIVATE 전용)** + 실행(`GET /api/v1/filters/{id}/search`, viewer 권한 재실행, 권한상승 불가) + 이름 owner내 유니크(409 dual-catch) + OCC + 하드삭제. **PR2(공유)**. `saved_filter_shares`(V601, PROJECT=project_key/GROUP=group UUID/AUTHENTICATED) + 공유 **본문 임베드**(POST/PUT replace-all+OCC) + **가시성 OR 4경로**(읽기 get/list/search) + 쓰기는 소유자만(가시-비소유 **403** `SavedFilterForbiddenException`/비가시 **404** 은닉) + 신규 `GET /api/v1/filters/shared`(페이지네이션) + cross-BC 멤버십 포트 2종(shared-kernel `GroupMembershipPort`/`ProjectMembershipPort` + identity-access 구현, **fail-closed**, project_key는 `ProjectDirectory` read-only 선례 매핑). 즐겨찾기는 기존 FR-UX-02 favorites(FILTER) 재사용. ADR `docs/decisions/2026-06-26-fr-sr-03-saved-filters.md`. **D6/D7 완료 — 프론트 PR(#196, 2026-06-27)**. `/search` AQL 페이지 통합(저장 버튼 + 내 필터/공유받은 필터 드롭다운) + `?filterId=` 딥링크 로드·실행 + 공유 모달(AUTHENTICATED+PROJECT, GROUP 보존, replace-all) + 별표(FILTER) 활성화 + Header ⭐ 노출. Maxi 결정 — /search 통합만·GROUP 공유 보류(그룹/프로젝트 목록 API 부재). **FR-SR-03 전체 완료.**

- [x] D1. 도메인 — SavedFilter + SavedFilterShare/ShareType (책임. backend-engineer)
- [x] D2. 명세 — 공유 권한 PROJECT/GROUP/AUTHENTICATED + 가시성 4경로 (책임. backend-engineer + security-engineer)
- [x] D3. 데이터 모델 — `saved_filters`(V600) + `saved_filter_shares`(V601) (책임. db-engineer)
- [x] D4. 백엔드 — CRUD + 공유 임베드 + `/shared` API (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 가시성 매트릭스·403/404·CASCADE·parity 통합 (책임. backend-engineer)
- [x] D6. 프론트 UI — 필터 저장/공유 모달 + 별표(FILTER) (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §2.4 FR-SR-04 — 한글 형태소 기반 전문 검색

**우선순위**. 필수 | **선행**. §2.2 | **Plan slug**. `search/korean-morpheme`

> **[구현 방식 확정 — FR-SR-04, PR #195 예정]** 형태소 분석기 미도입 결정 — **`simple` tsvector + pg_trgm 하이브리드(zero-dep, Docker 무변경)**. D2 "Mecab-ko vs Lucene-Kr 도입"은 ADR `docs/decisions/2026-06-26-fr-sr-04-korean-fts.md`로 superseded(FR-SR-02 ADR이 ANTLR 4를 superseded한 것과 동형). 구현 방식. AQL 신규 `text` 가상 필드(`~` 전용, summary+description 전문 검색) + `issues.search_vector` STORED generated column(V032, `to_tsvector('simple', coalesce(summary,'')||' '||coalesce(description,''))`) + GIN 인덱스(`idx_issues_search_vector`) + description trigram 인덱스(`idx_issues_description_trgm`, V031 summary 동형). BC 격리. 논리 search-export-import / 물리 issue-tracking(FR-SR-01/02 패턴 계승, 새 BC 신설 0). visibility 보안 술어 자동 AND(우회 불가). **D1~D5 백엔드 완료(PR #195).** **D6/D7 완료 — 프론트 PR(#199, 2026-06-27).** 프론트 토크나이저 `AQL_FIELDS`에 `text` 추가(백엔드 `AqlFields.MVP_FIELDS` 5필드 정합) → `text ~ "검색어"`의 `text`가 기존 `text-syntax-field` 색으로 강조(신규 색·토큰 없음, FR-SR-02 검색창 그대로 재사용). placeholder에 `text ~ "로그인"` 전문검색 예시 추가("AQL 쿼리를 입력하세요" 접두사 보존 → E2E `placeholder*=` 부분매칭 무회귀). best-effort 강조(판정 정본=백엔드 파서), 연산자 제약(`text`는 `~` 전용)은 백엔드 400 판정. D7 E2E 1(`text ~` 강조+검색 실행). **FR-SR-04 전체 완료.**

- [x] D1. 도메인 (책임. backend-engineer)
- [x] D2. 명세 — **`simple` tsvector + pg_trgm 결정**(Mecab-ko/Lucene-Kr 미도입 — [ADR docs/decisions/2026-06-26-fr-sr-04-korean-fts.md](../decisions/2026-06-26-fr-sr-04-korean-fts.md)). AQL `text` 가상 필드(`~` 전용). PostgreSQL FTS 통합 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `issues.search_vector` STORED generated column(V032) + GIN 인덱스 + description trigram 인덱스 (책임. db-engineer)
- [x] D4. 백엔드 — AQL `text` 분기(FTS + trigram 하이브리드) + 빈 검색어 결과 0 + `ORDER BY text` 거부 400 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 한글 FTS/trigram 케이스 30개(조사변형·부분문자열·혼용·다중토큰, 활용형 미매칭 명시 + 양성 대조군) (책임. backend-engineer)
- [x] D6. 프론트 UI — (§2.2와 통합 — 검색창 동일, `text` 키워드 syntax highlight 추가) (책임. frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

## §3 Export (FR-EX, 2개)

### §3.1 FR-EX-01 — 필터 결과 CSV/XLSX Export

**우선순위**. 필수 | **선행**. §2.1, §2.2 | **Plan slug**. `search/export-csv-xlsx`

> **전체 완료 (2026-06-29, PR #203)**. AQL 쿼리 기반 동기 Export — `POST /api/v1/search/export`(검색 하위 네임스페이스, FR-EX-02 비동기는 `/search/export-jobs`로 진화). 입력=AQL(`IssueSearchPort` 재사용, **issue-tracking·shared-kernel 변경 0**), 컬럼=`IssueSearchHit` 9필드(영문 표준 라벨) 부분선택, 동기 상한 1만 행(count-first `IssueSearchPage.total` 확인 후 페이지 순회, best-effort 스냅샷). CSV=UTF-8 BOM + RFC 4180(zero-dep), XLSX=**Apache POI poi-ooxml:5.4.0 신규 의존성**(Maxi 승인, log4j-to-slf4j 브리지). 보안. formula injection 방어(`ExportCellSanitizer` `=+-@\t\r`→`'` prefix, CSV·XLSX 공통) + Content-Disposition 헤더 인젝션 방어(projectKey 영숫자+하이픈 패턴) + BROWSE 권한·visibility 보안 술어를 `IssueSearchPort` 경유 **구조적 상속**(전용 쿼리 경로 없음). 전용 `ExportExceptionHandler`(`assignableTypes=[ExportController]`, 401→500 변질 차단·`SEARCH_` prefix). ADR `docs/decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md`. 데이터 모델 변경 0(D3 활용 — export_jobs는 FR-EX-02). 두 독립 plan 리뷰(eng/devex) BLOCKER 5건 코드 실증 + codereview C1(Clock 기본값) hot-fix.

- [x] D1. 도메인 — ExportRequest (책임. backend-engineer)
- [x] D2. 명세 — 필드 선택 + 한글 인코딩 (UTF-8 BOM) (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [x] D4. 백엔드 — `POST /api/v1/search/export` (CSV + Apache POI XLSX) (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — Excel 검증 (책임. backend-engineer)
- [x] D6. 프론트 UI — Export 다이얼로그 + 진행률 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §3.2 FR-EX-02 — 대용량(>1만건) 비동기 Export

**우선순위**. 높음 | **선행**. §3.1 | **Plan slug**. `search/export-async`

> **백엔드 D1~D5 완료 (2026-06-29, PR #204)**. 비동기 Export — `POST /api/v1/search/export-jobs`(202+jobId) → pgmq `q_export_jobs` worker → 스트리밍 직렬화(CSV=OutputStream / XLSX=SXSSF, 임시파일) → MinIO `bts-exports` 저장 → `GET .../{id}` 폴링 + `GET .../{id}/download` 자체 프록시 스트리밍. **스트리밍 10만행 상한**(초과 FAILED), **24h TTL 후 DB+MinIO 하드삭제**(ExportJobCleanupWorker). BulkOperation(FR-IS-05) pgmq consumer 패턴 1:1 복제(claimForRun CAS·dead-letter archive·outbox MANDATORY enqueue·VT=300<stale=600 정합)·FR-EX-01 writer/sanitizer/`IssueSearchPort`(BROWSE+visibility 구조적 상속) 재사용. search 모듈 **자체 MinIO 클라이언트**(`exportMinioClient` 빈 분리, io.minio 기승인, BC 격리). job 소유권 404 은닉·actor SecurityContext 추출·Content-Disposition CRLF 방어. **process() @Transactional 밖**(분단위 I/O 커넥션 점유 차단). V602 export_jobs(15컬럼)+pgmq 큐·Tembo pg16-pgmq 이미지(ADR 2026-05-22). ADR `docs/decisions/2026-06-29-fr-ex-02-async-export-jobs.md`. 두 독립 리뷰(code-reviewer BLOCKER `@EnableScheduling` 누락 + adversarial P1 리소스누수/download 0바이트200) 적발·수정. 프론트 D6/D7은 별도 PR.

> **전체 완료 (2026-06-30, PR #206)**. 프론트 D6/D7 — **자동 분기**(동기 시도→1만 초과 `SEARCH_EXPORT_LIMIT_EXCEEDED` 감지→비동기 제안) + ExportDialog **4단계 상태머신**(form→confirmAsync→tracking→done). 잡 생성 `POST /search/export-jobs`(202+jobId)→1500ms 폴링 `GET /{id}`(progress/status, **백엔드 완료이벤트 없음→프론트 폴링 자체 감지**)→COMPLETED 시 `GET /{id}/download` blob. 폴링=`use-export-job-polling` hook(BulkOperation `use-bulk-operation` 1:1 미러, react-query v5 `(query)=>query.state.data?.status` 시그니처·종단/error 정지·unmount cleanup). Zod `.nullish()`(백엔드 `@JsonInclude(NON_NULL)` 정합)·`isLimitExceeded`/`resultCount` 타입가드(as any 0). MSW stateful jobId-키 Map E2E. **plan 리뷰 BLOCKER4**(v5 refetchInterval/Zod nullish/기존테스트·E2E 교체) + **게이트2 CONCERNS3**(다운로드실패 데드패스·폴링에러 tracking정지 spec EC5·cleanup vacuous green) 적발·해소. 백엔드 변경 0. ADR 불요(view-layer 소비, plan/spec에 UX 결정 기록).

- [x] D1. 도메인 — ExportJob (책임. backend-engineer)
- [x] D2. 명세 — 큐 + 진행률 + 결과 URL TTL (책임. backend-engineer)
- [x] D3. 데이터 모델 — `export_jobs(status, progress, result_minio_key, expires_at)` (책임. db-engineer)
- [x] D4. 백엔드 — pgmq job + 백그라운드 worker (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 1만건 시나리오 (책임. backend-engineer)
- [x] D6. 프론트 UI — 진행률 + 알림 + 다운로드 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

## §4 Import (FR-IM, 2개)

### §4.1 FR-IM-01 — CSV/JSON Import (Jira 마이그레이션)

**우선순위**. 필수 | **선행**. issue-tracking §2~§3 (이슈/컴포넌트/버전 작성 API) | **Plan slug**. `search/import`

- [ ] D1. 도메인 — ImportJob (책임. backend-engineer)
- [ ] D2. 명세 — CSV/JSON 파싱 + 트랜잭션 정책 + dry-run (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `import_jobs(status, error_log_minio_key)` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/imports` + 백그라운드 worker (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — Jira CSV 샘플 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 파일 업로드 + 진행률 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.2 FR-IM-02 — Import 매핑 UI (필드/사용자 매핑)

**우선순위**. 필수 | **선행**. §4.1 | **Plan slug**. `search/import-mapping`

- [ ] D1. 도메인 — ImportMapping (책임. backend-engineer)
- [ ] D2. 명세 — 필드 매핑 + 사용자 매핑 + 미매핑 처리 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `import_mappings(import_job_id, source_field, target_field)` (책임. db-engineer)
- [ ] D4. 백엔드 — 매핑 검증 API (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 매핑 마법사 (다단계) (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §5 REST API + Webhook + PAT (FR-API, 4개)

### §5.1 FR-API-01 — 이슈 CRUD REST API (표준화)

**우선순위**. 필수 | **선행**. issue-tracking §2.1.1 | **Plan slug**. `search/api-issue-crud`

- [x] D1. 도메인 — API 응답 표준 (페이지네이션, 에러 포맷) (책임. backend-engineer)
- [x] D2. 명세 — OpenAPI 3.1 스펙 작성 (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [x] D4. 백엔드 — issue-tracking API에 cursor pagination + bulk ops 표준 적용 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — OpenAPI 스펙 검증 + contract test (책임. backend-engineer)
- [x] D6. 프론트 UI — (해당 없음 — API 문서는 Swagger UI) (책임. -)
- [x] D7. E2E — OpenApiContractTest 가 실 DB로 cursor 순회·계약 검증 (Postman/Insomnia 수동 시나리오 대체) (책임. qa-engineer)

### §5.2 FR-API-02 — AQL 검색 REST API

**우선순위**. 필수 | **선행**. §2.2, §5.1 | **Plan slug**. `search/api-aql`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 동기/비동기 응답 정책 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/search/aql` + 페이지네이션 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — (해당 없음) (책임. -)
- [ ] D7. E2E (책임. qa-engineer)

### §5.3 FR-API-03 — Webhook (외부 시스템 통지)

**우선순위**. 필수 | **선행**. §5.1, identity-access §2.10 (감사) | **Plan slug**. `search/api-webhook-out`

- [ ] D1. 도메인 — OutboundWebhook (책임. backend-engineer)
- [ ] D2. 명세 — HMAC-SHA256 서명 + 재시도 + circuit breaker (책임. backend-engineer + security-engineer)
- [ ] D3. 데이터 모델 — `outbound_webhooks(url, secret_encrypted, event_filter)` + `webhook_deliveries(status, response_code)` (책임. db-engineer)
- [ ] D4. 백엔드 — pgmq event → HTTP 발송 + 재시도 (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 — 재시도 + circuit breaker (책임. backend-engineer)
- [ ] D6. 프론트 UI — Webhook 관리 페이지 + 발송 이력 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §5.4 FR-API-04 — Personal Access Token

**우선순위**. 필수 | **선행**. identity-access §2.9 (PAT 데이터 모델) | **Plan slug**. `search/api-pat`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — scope + TTL + 회전 + 취소 (책임. security-engineer)
- [ ] D3. 데이터 모델 — (identity-access §2.9 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/users/me/pats` 발급 + 인증 미들웨어에 PAT 인식 추가 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — scope 위반 reject (책임. security-engineer)
- [ ] D6. 프론트 UI — PAT 발급/회전/취소 페이지. **DEVELOPMENT.md §1.17 — 토큰은 화면 표시 1회만, localStorage 금지** (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §A OpenAPI 문서 게시

- [ ] `springdoc-openapi-starter-webmvc-ui` 통합
- [ ] `/v3/api-docs` + `/swagger-ui` 호스팅
- [ ] CI에서 OpenAPI 스펙 변경 시 알림 (계약 변경 감시)

## §NFR search-export-import BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| AQL 100만건 검색 | 1s | ___ | k6 + `pg_stat_statements` |
| AQL 단순 쿼리 | 500ms | ___ | k6 |
| API 단순 GET | 100ms | ___ | k6 |
| Export 1만건 (비동기) | 60s | ___ | 백그라운드 worker |
| Import 1만건 | 120s | ___ | 백그라운드 worker |
| Webhook 발송 응답 | 500ms | ___ | k6 (외부 mock) |
| PAT 인증 검증 | 50ms | ___ | k6 |
| 한글 형태소 정확도 | 95% | ___ | 30 케이스 |
| OpenAPI 스펙 contract test | 0 회귀 | ___ | CI |

### BC 완료 조건

- [ ] §2~§5 (12 FR) 모두 `[x]` 마킹
- [ ] §A OpenAPI 게시 완료
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "search-export-import BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "search-export-import BC 완료"
