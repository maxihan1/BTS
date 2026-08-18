<!-- search-export-import BC — AQL 검색 + Export + Import + REST API + Webhook + PAT 12 FR -->

# search-export-import BC

**소속 FR**. 12개 (SR 4 + EX 2 + IM 2 + API 4).
**책임**. 이슈 검색(AQL/필터/형태소) + Export(CSV/XLSX/비동기) + Import(Jira) + REST API + Webhook + Personal Access Token.
**SDD 참조**. 10장 (검색/Export/Import), 11장 (API).
**다른 BC와의 경계**. issue-tracking BC의 이슈를 검색/Export. 다른 모든 BC의 REST API 엔드포인트는 각 BC에서 정의하되, 본 BC는 그 API의 **표준 규약** (페이지네이션/벌크/에러 응답 포맷)을 책임. **import 금지 — API 호출만**.

## §0 진입 조건

- [x] identity-access §2.9 (FR-AU-09 PAT 토큰) 완료 — 2026-07-27 실측: `identity-access.md` §2.9 FR-AU-09 D1~D7 전부 `[x]` (PR #37)
- [x] issue-tracking §2~§6 (검색/Export 대상 데이터 안정) 완료 — 2026-07-27 실측: `grep -cE '^- \[[ ~!]\] D[0-9]+\.' docs/plan/product/issue-tracking.md` → 0 (§2~§6 포함 미완 D단계 없음)
- [x] §1 기술 검증 통과 (아래) — 2026-07-27 실측: §1.1 5항목 중 4항목 실물 확인, ANTLR 항목만 손수 파서로 대체(아래 주석). FR-SR-02 D1~D7 전량 `[x]` 로 프로덕션 반영

## §1 기술 검증

### §1.1 AQL 파서 + PostgreSQL 변환 PoC (3일)

**SDD**. 10장. **checklist.md 위임**. §1.2. **ADR 후보**. 없음.

- [ ] ANTLR 4 문법 정의 (`backend/modules/search-export-import/aql/Aql.g4`) — ⚠️ 대체됨. 손수 작성 재귀하강 파서(`com/bts/search/aql/AqlLexer.kt`·`AqlParser.kt`, ANTLR 의존성·`*.g4` 파일 저장소 전체 0건) — ADR `docs/decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md`. (2026-07-27 실측)
- [x] AST → jOOQ Condition 변환기 1차 구현 — 2026-07-27 실측: `IssueRepository.buildAstCondition` (`AqlNode.And/Or/Not/Comparison` → `org.jooq.Condition`) + `IssueSearchAdapter`
- [x] JQL 기본 키워드 (AND/OR/`=`/`!=`/`IN`/`~`/`ORDER BY`) 동작 — 2026-07-27 실측: `AqlToken.kt` 에 `KW_AND`·`KW_OR`·`EQ`·`NEQ`·`KW_IN`·`TILDE`·`KW_ORDER` 전부 정의
- [x] 단위 테스트 50개 통과 (JQL 호환) — 2026-07-27 실측: `AqlParserTest.kt` 81 + `AqlLexerTest.kt` 35 = `@Test` 116건 (실행 수는 XML 집계 기준 별도)
- [x] `pg_trgm` 확장 설치 (텍스트 매칭 가속) — 2026-07-27 실측: `V031__issues_summary_trgm_index.sql` 의 `CREATE EXTENSION IF NOT EXISTS pg_trgm` + `init_codegen.sql` 미러

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

> **PR1 기반+코어 완료 (2026-07-02, PR #218)**. Maxi 결정으로 FR-IM-01을 SDD 10.6.3 **풀 마이그레이션 에픽**(순차 PR)으로 확장 — PR1(기반+코어) → PR2(컴포넌트/버전 자동생성+소스 상태 전환) → PR3(댓글/Worklog) → PR4(첨부 zip+이력). **PR1 범위**. FR-EX-02(비동기 Export) 역방향 미러 — `ImportJob` 도메인·`import_jobs`(V604)·pgmq `q_import_jobs`·multipart 업로드→MinIO(`bts-imports`)·`POST/GET /api/v1/imports`·`ImportJobWorker`(CAS/dead-letter/VT300·stale600/TTL 24h)·CSV+JSON(Jackson 스트리밍) 파서·**코어 이슈 필드**(summary/description/type/priority/reporter·assignee 이메일매핑/labels) 생성. **BTS 최초 cross-BC 쓰기 포트** `IssueImportPort`(shared-kernel, issue-tracking `IssueImportAdapter` 구현) — `createIssue(actor=requester)` 권한 위임(우회 불가)·행별 best-effort(create+update 1 tx 원자성, `IssueCreated` outbox 롤백 안전)·dry-run(실경로 권한 정확 미러)·에러로그 CSV(ExportCellSanitizer 정화)·접수 CREATE coarse 게이트 fail-fast. `UserLookupPort.resolveByEmails` default 확장(email nullable+비유니크 다중매칭 fail-safe). 새 키 자동생성(Jira 키 보존 안 함 — 키 재사용 금지 원칙). ADR `docs/decisions/2026-07-02-fr-im-01-csv-json-import.md`. 코드리뷰 CONCERN 3건(권한발산·완료 progress·행상한 스펙) 수정 후 PASS. **D6/D7 프론트·PR2~4는 후속. D박스는 FR 전체 완료 시 마킹.**

> **PR2 컴포넌트/버전 자동생성 + 소스 상태 전환 완료 (2026-07-02, PR #221)**. **PR2 범위**. 파서·`IssueImportCommand`(shared-kernel) 3필드 확장(status/fixVersions/affectsVersions, CSV `status`/`fix version`/`affects version`·JSON `fields.status.name`/`fixVersions[].name`/`versions[].name`) + `IssueImportAdapter` 확장 — **컴포넌트/버전 미매칭 시 자동생성**(actor=requester로 `ComponentApplicationService`/`VersionApplicationService.create` 위임 → MANAGE_COMPONENTS/MANAGE_VERSIONS 게이트) + **affects/fix 버전 링크**(`changeAffectsVersions`/`changeFixVersions`, EDIT_ISSUE) + **소스 상태 direct-set**(신규 `IssueImportStatusService` — FSM edge 우회, `WorkflowStateCatalog.listStates` name 대소문자 매칭, `IssueRepository.applyTransition(resolutionId=null)`, IssueMoveService 선례). **권한 3축**. 생성(admin)=`hasPermission` **사전 체크**로 tx 오염 회피(참여 @Transactional throw는 shared tx rollback-only 오염, memory `transaction-self-invocation-requires-new`) → 없으면 create 미호출+best-effort 경고 / 편집(basic UPDATE)=행 실패(PR1 일관) / 전환=`hasPermission` 사전체크로 경고화. **G1 경고 노출**(Brainstorming) — PR1이 버린 `Success.warnings`를 `ImportJobProcessor`가 결과 로그 CSV에 severity(WARNING/FAILURE) 컬럼으로 기록(경고행만 있어도 업로드), **스키마 변경 0**. dry-run은 자동생성 권한·TRANSITION 권한·**상태 name 매칭**을 실경로와 동일 기준 미리 검사(`statusNameMatches`) — 유효성-예측 경고만 미러, "자동 생성했습니다" informational 경고는 실행 전용(CONCERN-B). DONE 카테고리 resolution=null 허용(게이트1 확정, PR2 범위 밖). **신규 마이그레이션 0**. 적대 코드리뷰 CONCERN-A(dry-run 미매칭 미리보기)/NIT-1(중복 이름 23505) 수정(S22/S23). (구현 중 발견한 FR-RP-01 pre-existing ArchUnit 위반은 hot-fix로 준비했으나, rebase 시점에 main의 FR-RP-02 #222가 `SprintBurndownQueryRepository` 추출로 이미 독립 해소해 hot-fix 커밋을 drop — main 채택.) **D6/D7 프론트·PR3~4는 후속. D박스는 FR 전체 완료 시 마킹.**

> **PR3 댓글/Worklog Import 완료 (2026-07-03, PR #224)**. **PR3 범위**. Jira 소스 이슈의 댓글·Worklog를 생성 이슈에 함께 import. **댓글 도메인 신설**(BTS에 댓글 기능 부재 → 이 PR에서 도입, DATA.md §3에 이미 예정된 `comments` 소프트삭제 테이블) — `V035__comments.sql`(issue_id FK ON DELETE CASCADE·author_id FK 미적용 BC격리·deleted_at·`(issue_id, created_at) WHERE deleted_at IS NULL` 인덱스, init_codegen 미러) + `Comment`/`CommentRepository`(jOOQ) + `CommentApplicationService`(create=`IssuePermission.UPDATE` 게이트·**원본 authorId·createdAt 보존**, list=**VIEW+`IssueScope.Issue`** 기밀이슈 누출차단) + `GET /api/v1/issues/{key}/comments`(`CommentView` created_at ASC·`MarkdownRenderer.renderSafe` bodyHtml) + **전용 `CommentExceptionHandler`**(worklog 핸들러 전체 미러 — `ResponseStatusException` 401 전파로 catch-all 500 변질 차단). 작성/수정/삭제 REST·UI·멘션은 별도 FR. **Worklog** — `WorklogService.createImported`(author 주입·**이력 생략**[Import는 이벤트 재생 아님]·no-bump·remaining auto). **파서/커맨드 확장** — `IssueImportCommand`(shared-kernel)에 `comments`/`worklogs` + `ImportComment`/`ImportWorklog` VO. JSON 정본(`fields.comment.comments[]`/`fields.worklog.worklogs[]` 중첩 스트리밍), **CSV 댓글은 동명 `Comment` 컬럼 다중 수집**(기존 `putIfAbsent` 첫 컬럼만 보존→2..N 유실 회귀 수정, `date;author;body` split limit=3), **worklog는 JSON 전용**(Maxi 확정). **어댑터 위임** — `IssueImportAdapter`가 createIssue 후 댓글→worklog 생성, 이메일→author `resolveByEmails` 단일 배치 합류+requester 폴백, **tx 오염 사전체크**(잔여 throw {UPDATE 권한·timeSpent≤0(23514)} 호출 전 강등, PR2 원칙) + **행당 유형별 집약 경고**(Maxi 확정) + dry-run **별도 경고 경로**(`warnCommentsWorklogsIfNeeded`, FORBIDDEN early-return 이후·`rowTriggersUpdate` 미엮음, PR2 CONCERN-A 재발 방지). **신규 마이그레이션 V035**(issue-tracking, 머지 직전 재확인). 8 task TDD·전 통합테스트 Testcontainers 실 tx(S3 권한없음→이슈 커밋·S6 dry-run→FORBIDDEN 아님·S7 예상외 throw→행 롤백). eng-review C1(예외핸들러 401)/C2(CSV 다중컬럼)/C3(dry-run 배관) 반영. **D6/D7 프론트·PR4는 후속. D박스는 FR 전체 완료 시 마킹.**

> **PR4 첨부/이력 Import 완료 (2026-07-03, PR #226)**. **PR4 범위**. Jira 첨부파일 zip과 변경 이력(changelog)을 생성 이슈에 함께 import — 백엔드 4/4 완결. **첨부** = zip 두-part 멀티파트 업로드(`attachmentsZip` optional part, 서버가 외부 파일을 직접 fetch하지 않아 SSRF 경로 0). shared-kernel `ImportAttachmentSource`(fun interface, search가 zip 보유·issue-tracking이 스트림 소비, BC 격리) → 어댑터가 FR-AC-01 `IssueAttachmentService.upload`(ClamAV/MIME 게이트 재사용, 우회 불가)로 위임, 행별 best-effort. `ZipImportAttachmentSource`가 zip-slip·엔트리 100MB 초과를 거부(외부가 보고한 size가 아닌 실측 바이트로 검증 — 침묵 절단 방지). V605 `import_jobs.attachments_object_key`. **이력** = Jira `changelog.histories[]`를 detector 우회하고 충실 재생(Maxi 확정) — `recordImported`가 `issue_change_group/item`에 원본 `occurredAt`을 그대로 주입(스키마 변경 0), `CHANGELOG_FIELD_MAP` 13필드 매핑(미매핑 필드는 스킵), author 미해석 시 actorId는 null 처리(requester 폴백 안 함), 이력 항목 상한 1000. 코드리뷰 CONCERN 3건(무부팅 모듈 프로파일 yml 실효 없음·외부 보고 size 신뢰 금지→실측·best-effort catch의 권한예외 스킵-경고 강등 금지) 수정 후 PASS. **D6/D7 프론트는 후속. D박스는 FR 전체 완료 시 마킹.**

> **D6/D7 프론트 + FR-IM-01 전체 완료 (2026-07-03)**. FR-EX-02(비동기 Export) 프론트의 역방향 미러 — `api/imports.ts`(Zod `importJobStatusSchema` + `submitImportJob`/`fetchImportJobStatus`/`downloadImportErrorLog` + 에러코드 7종 한국어 매핑) + `use-import-job-polling.ts`(react-query v5 `refetchInterval:(query)=>`, 종단 COMPLETED/FAILED에서 중단) + `ImportForm.tsx`(3-phase 상태머신 form→tracking→done, dry-run 2-step — [검증만 실행]=primary·[Import 시작]=secondary 버튼 위계로 마이그레이션 안전 유도, done에서 실패 수 destructive 강조 + 에러 로그 CSV 다운로드 + "이 파일로 실제 Import" 재제출) + `/projects/$projectKey/settings/import` 전용 설정 페이지(router.ts 등록) + MSW stateful 핸들러(jobId-keyed 진행 시뮬레이션) + E2E(S1 진입/S2 dry-run→실제 Import 완료/S3 FAILED→재시도). 백엔드 변경 0(view-layer 소비). **FR-IM-01 전체 완료 — PR1(#218)·PR2(#221)·PR3(#224)·PR4(#226) + D6/D7 프론트.**

- [x] D1. 도메인 — ImportJob (책임. backend-engineer)
- [x] D2. 명세 — CSV/JSON 파싱 + 트랜잭션 정책 + dry-run (책임. backend-engineer)
- [x] D3. 데이터 모델 — `import_jobs(status, error_log_minio_key)` (책임. db-engineer)
- [x] D4. 백엔드 — `POST /api/v1/imports` + 백그라운드 worker (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — Jira CSV 샘플 (책임. backend-engineer)
- [x] D6. 프론트 UI — 파일 업로드 + 진행률 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §4.2 FR-IM-02 — Import 매핑 UI (필드/사용자 매핑)

**우선순위**. 필수 | **선행**. §4.1 | **Plan slug**. `search/import-mapping`

> **PR-A 완료 (기반 + 필드 매핑, 2026-07-03)**. Maxi 결정으로 FR-IM-02를 **순차 에픽 3-PR(A/B/C) + 프론트**로 확장 — 사용자 매핑을 **전 작성자 필드**로·값 매핑을 **status/type/priority**로 확장하면서 백엔드가 커져 분할. **2단계 흐름(analyze→map→run)** 채택 — 기존 즉시-업로드(`POST /api/v1/imports`, canonical 자동매핑)는 하위호환 유지. **PR-A 범위**. `ImportJobStatus.AWAITING_MAPPING` 신규 상태(V606 `chk_import_jobs_status` 확장) + `import_mappings(import_job_id, source_field, target_field)`(V606, FK ON DELETE CASCADE) + `TargetField` 카탈로그 11종 + 매핑-aware `ImportRowParser`(CSV 임의 헤더 자유매핑 오버로드·canonical 폴백 불변, JSON canonical 유지) + `readHeaderAndSample` bounded 분석 + `ImportJobService.analyze`(persist+AWAITING_MAPPING+ABANDON_TTL 24h) + `ImportMappingService.validate/confirm`(CAS-우선 트랜잭션으로 중복 enqueue 차단) + `MappingValidator`(SUMMARY_NOT_MAPPED/DUPLICATE_TARGET/UNKNOWN_TARGET/UNKNOWN_SOURCE + SOURCE_FIELD_IGNORED 경고) + `ImportMappingController` 3 엔드포인트(`POST /imports/analyze` 200 · `/imports/{id}/mapping/validate` 200 · `/imports/{id}/mapping` 200 PENDING 전환) + `ImportJobProcessor` 매핑 로드 + `ImportJobCleanupWorker` `deleteIfExpired` 가드(cleanup↔confirm 레이스 차단). ADR `docs/decisions/2026-07-03-fr-im-02-import-mapping.md`. **D3 데이터 모델 deviation** — 단일 `import_mappings`(PR-A) → 사용자 매핑용 `import_user_mappings`(PR-B)·값 매핑용 `import_value_mappings`(PR-C) 확장. **PR-B(사용자 매핑 전 작성자)·PR-C(값 매핑)·D6/D7 프론트 마법사는 후속. D박스는 FR 전체 완료 시 마킹.**

> **D6/D7 완료 — 프론트 마법사 PR(#235, 2026-07-04). FR-IM-02 전체 완료.** 백엔드 3-PR(#230 PR-A 필드매핑 / #233 PR-B 사용자매핑 / #234 PR-C 값매핑) + 이 프론트로 에픽 완결. 백엔드 변경 0(확정 REST 계약 소비만). **두 모드 병존**(Maxi 결정) — 설정 Import 페이지에 "바로 가져오기"(기존 FR-IM-01 폼, canonical+JSON 첨부 zip 무변경) / "매핑하며 가져오기"(신규 `ImportMappingWizard`) 토글. analyze 경로가 첨부 zip 미지원이라 폼을 대체하지 않고 병존. **마법사 흐름**. 업로드/분석(`POST /imports/analyze`)→(CSV)필드 매핑(`suggestFieldMappings` 프론트 휴리스틱 프리필+`validate`)→사용자 매핑(`collect/users`, 추천+검색 재지정+미매핑)→값 매핑(`collect/values`, STATUS 관대·TYPE/PRIORITY 엄격)→검토·확정(`POST /imports/{id}/mapping` dryRun)→기존 `useImportJobPolling` 재사용. 사용자/값 단계는 collect 빈 목록 시 자동 스킵(동적 stepper), JSON은 필드 매핑 스킵. **dry-run 재적용**(Maxi 결정) — confirm 단발 소진이라 dry-run 후 [이 매핑으로 실제 가져오기]는 보존 file+매핑으로 재-analyze 후 실제 confirm. Zod 스키마 백엔드 DTO 1:1(`@JsonInclude(NON_NULL)`→`.nullish()`). 게이트2 두 상보 리뷰가 **실 BLOCKER**(재수집 시 override 전량 재-seed로 사용자 명시 매핑 조용히 소실→함수형 병합으로 생존 키 보존) + **422 dead-end**(ReviewStep 복구 경로 부재+`errors[]` 삼킴→[이전] 버튼+per-field alert) 적발·수정. E2E가 폴링 조기 404 고착 버그도 적발. 신규 백엔드/스키마/ADR 0(에픽 ADR #230 재사용).

- [x] D1. 도메인 — ImportMapping (책임. backend-engineer)
- [x] D2. 명세 — 필드 매핑 + 사용자 매핑 + 미매핑 처리 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `import_mappings`(PR-A) + `import_user_mappings`(PR-B) + `import_value_mappings`(PR-C) (책임. db-engineer)
- [x] D4. 백엔드 — 매핑 검증 API (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 매핑 마법사 (다단계) (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

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

> **전체 완료 (2026-06-30, PR #207 + #208)**. 논리 search-export-import / 물리 issue-tracking(새 BC 신설 0). PR #207 — cursor 병행 페이지네이션(offset 유지·프론트 무회귀, opaque Base64URL keyset `(created_at DESC, id DESC)`) + 응답 envelope(`{data, meta.page:{next,limit}}`) + RFC 7807 ProblemDetail 보강(신규 `ISSUE_INVALID_CURSOR`/`ISSUE_PAGINATION_MODE_CONFLICT`, 두 cursor 경로 계약 통일) + **전역 springdoc OpenAPI 3.1 + Swagger UI**(bearerAuth) + `OpenApiContractTest` 실 DB cursor 순회(중복0/누락0/tie-break). V033 부분 인덱스 `(created_at DESC, id DESC) WHERE deleted_at IS NULL` + init_codegen 미러. cursor seek는 `buildActiveSecureWhere` 재사용으로 visibility/소프트삭제 술어 구조적 상속(우회 0). PR #208 — 기존 bulk 엔드포인트(`BulkOperationController` 3개)에 `@Tag`/`@Operation`/`@ApiResponses`/`@SecurityRequirement` 적용(FR-IS-05 기존 주소 문서화, 신규 0). 적대/일반 두 리뷰 상보 — changelog cursor 위변조 errorCode 불일치(VALIDATION_FAILED→ISSUE_INVALID_CURSOR) 적발·통일. ADR `docs/decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md`. **FR-API-01 전체 완료.**

### §5.2 FR-API-02 — AQL 검색 REST API

**우선순위**. 필수 | **선행**. §2.2, §5.1 | **Plan slug**. `search/api-aql`

- [x] D1. 도메인 — envelope 표준 정의 (책임. backend-engineer)
- [x] D2. 명세 — 동기 검색 + 대량은 export job(FR-EX-02) 위임, cursor는 후속 FR (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용, 변경 없음) (책임. db-engineer)
- [x] D4. 백엔드 — `POST /api/v1/search/aql` 응답 envelope `{data, meta.page}` + offset 페이지네이션 + springdoc OpenAPI (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 슬라이스/통합/contract/annotation (책임. backend-engineer)
- [x] D6. 프론트 UI — (새 화면 없음 — 응답 파싱 envelope 어댑테이션만 동반) (책임. frontend-engineer)
- [x] D7. E2E — `SearchOpenApiContractTest` + `OpenApiAnnotationTest`가 실 `/v3/api-docs`로 계약 검증 (책임. qa-engineer)

> **전체 완료 (2026-06-30, PR #210)**. offset 페이지네이션 유지 + 응답을 raw Spring Page → envelope `{data, meta:{page:{number,size,totalElements,totalPages}}}`로 표준화(FR-API-01 외피 일관) + search 모듈 springdoc 2.6.0 + `OpenApiConfig`(bearerAuth) + 엔드포인트 `@Tag`/`@Operation`/`@ApiResponses`/`@SecurityRequirement` + EC5 `@Schema(implementation=AqlSearchPageResponse)` 제네릭 erasure 방어. **cursor는 AQL 동적 ORDER BY와 keyset seek 충돌로 후속 FR 분리**(ADR). `IssueSearchPort` 재사용으로 BROWSE·visibility 보안 술어 구조적 상속(변경 0, 마이그레이션 0). 프론트 `search.ts`/`search.tsx` envelope 파싱 동반(무회귀, 소비처 1곳). 적대/일반 두 리뷰 상보 — 프론트 가짜그린(envelope strip 무력화) + MSW priorityName 한글 drift(backend `IssuePriority` 영어) 적발·정정. ADR `docs/decisions/2026-06-30-fr-api-02-aql-search-offset-envelope.md`. **FR-API-02 전체 완료.**

### §5.3 FR-API-03 — Webhook (외부 시스템 통지)

**우선순위**. 필수 | **선행**. §5.1, identity-access §2.10 (감사) | **Plan slug**. `search/api-webhook-out`

> **FR-API-03 전체 완료 — PR1(#211)·PR2(#212)·PR3(#213)·PR4(#214, 2026-07-01)**. 4-PR 분할. PR1 shared-kernel `com.bts.shared.http` SSRF 검증기+HTTP 클라이언트 추출(FR-NT-05와 단일 구현 공유). PR2 `OutboundWebhook` 구독 도메인+CRUD REST+secret AES-256-GCM 암호화+V603(`outbound_webhooks`/`webhook_deliveries`), 전역 SYSTEM_ADMIN 게이트. PR3 issue-tracking dual-send(`q_webhook_events`)+search fanout/dispatch 워커+HMAC-SHA256 서명+circuit breaker+발송 이력 기록+멱등키(`X-BTS-Delivery`). PR4 관리 UI(구독 CRUD+발송 이력 화면, `apps/web`)+E2E — 전 엔드포인트 SYSTEM_ADMIN 라우트 가드+Header 게이팅 이중화, secret 미노출(hasSecret만), OCC 409, 폼 `key` 재마운트(편집전환 데이터손상 차단). **FR-API-03 전체 종료.**

- [x] D1. 도메인 — OutboundWebhook (책임. backend-engineer)
- [x] D2. 명세 — HMAC-SHA256 서명 + 재시도 + circuit breaker (책임. backend-engineer + security-engineer)
- [x] D3. 데이터 모델 — `outbound_webhooks(url, secret_encrypted, event_filter)` + `webhook_deliveries(status, response_code)` (책임. db-engineer)
- [x] D4. 백엔드 — pgmq event → HTTP 발송 + 재시도 (책임. backend-engineer + security-engineer)
- [x] D5. 백엔드 테스트 — 재시도 + circuit breaker (책임. backend-engineer)
- [x] D6. 프론트 UI — Webhook 관리 페이지 + 발송 이력 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §5.4 FR-API-04 — Personal Access Token

> **전체 완료 (2026-07-02, PR #215)**. 사용자 셀프서비스 PAT 발급/목록/취소. **물리 구현 identity-access `com.atlas.bts.identity.pat`**(논리 소속 search-export-import), FR-AU-09(#37) 자산 재사용으로 **마이그레이션 0**. scope 카탈로그 5종(`read:issues`·`write:issues`·`read:projects`·`write:projects`·`*`) 화이트리스트 검증(저장·표시만, 전면 강제는 후속 트랙 — ADR 결정 1). 회전=취소+재발급(별도 rotate API 없음). 보안. CSPRNG `SecureRandom` base62 48자 `pat_` 접두·SHA-256 저장(raw 1회 노출·로깅/localStorage 금지)·개수 상한 20 TOCTOU `pg_advisory_xact_lock(hashtextextended(userId))` 후 재count·취소 IDOR `findByIdAndUserId` 소유확인(타인/미존재 동일 404·멱등 204)·PAT 자격증명 관리 API 호출 403(JWT 전용)·감사 `PAT_ISSUED`/`PAT_REVOKED` 2종 트랜잭션 내 fail-closed(enum 22→24). TTL 사용자 선택 최대 1년(무기한 금지). 프론트 `/settings/pats`(scope 미강제 disclosure 경고 배너). ADR `docs/decisions/2026-07-02-fr-api-04-pat-management.md`. 두 독립 리뷰(code-reviewer PASS·/review 이슈 0) BLOCKER 0.

**우선순위**. 필수 | **선행**. identity-access §2.9 (PAT 데이터 모델) | **Plan slug**. `search/api-pat`

- [x] D1. 도메인 (책임. security-engineer)
- [x] D2. 명세 — scope + TTL + 회전 + 취소 (책임. security-engineer)
- [x] D3. 데이터 모델 — (identity-access §2.9 활용) (책임. db-engineer)
- [x] D4. 백엔드 — `POST /api/v1/users/me/pats` 발급 + 인증 미들웨어에 PAT 인식 추가 (책임. security-engineer)
- [x] D5. 백엔드 테스트 — scope 위반 reject (책임. security-engineer)
- [x] D6. 프론트 UI — PAT 발급/회전/취소 페이지. **DEVELOPMENT.md §1.17 — 토큰은 화면 표시 1회만, localStorage 금지** (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

## §A OpenAPI 문서 게시

- [x] `springdoc-openapi-starter-webmvc-ui` 통합 — 2026-07-27 실측: `search-export-import/build.gradle.kts:107` · `issue-tracking/build.gradle.kts:179` 에 `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0`
- [x] `/v3/api-docs` + `/swagger-ui` 호스팅 — 2026-07-27 실측: `app/src/main/resources/application.yml` `springdoc.api-docs.path=/v3/api-docs`·`swagger-ui.path=/swagger-ui.html` + `OpenApiConfig.kt` permitAll + `OpenApiDocsIntegrationTest` 200 검증
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

- [x] §2~§5 (12 FR) 모두 `[x]` 마킹 — 2026-07-27 실측: 이 파일 FR 헤더 12개 · `- [x] D«n».` 84건 · 미완 D단계 0건
- [ ] §A OpenAPI 게시 완료
- [ ] §NFR 측정표 모든 항목 임계 통과 — 미측정. 위 측정표 9행 실측란이 전부 `___`. k6 부하 + `pg_stat_statements` 로 AQL/API/Export/Import/Webhook/PAT p95 와 한글 형태소 30케이스 정확도를 측정해야 한다
- [x] CHANGELOG.md 정리 — 2026-07-27 실측: 저장소 루트 `CHANGELOG.md` §BC 요약에 search-export-import 행 존재 (12 FR · 2026-06-23~07-04 · PR 15건)
- [ ] README.md §7 변경 이력에 "search-export-import BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "search-export-import BC 완료" — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가)
