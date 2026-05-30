# FR-IS-04 — 이슈 본문(Markdown) + 우선순위/라벨/환경/영향도

> slug: fr-is-04-markdown
> type: feature
> agent: backend-engineer (+ security-engineer 공동, frontend-engineer, db-engineer, qa-engineer)
> 생성: 2026-05-30

## Brief

FR-IS-04 — 이슈에 본문(Markdown)과 메타 필드(우선순위/라벨/환경/영향도)를 추가한다. BC=issue-tracking. 선행 FR-IS-01(이슈 CRUD) 완료.

분류 결과 — type=feature, agent=backend-engineer, primary_bc=issue-tracking.

문서상 단계(docs/plan/product/issue-tracking.md §2.1.4).
- D1. 도메인 (backend-engineer)
- D2. 명세 — Markdown XSS sanitization (backend-engineer + security-engineer)
- D3. 데이터 모델 — `issues.body` (TEXT) + priority/environment/impact (db-engineer)
- D4. 백엔드 — flexmark 렌더링 + sanitize (backend-engineer + security-engineer)
- D5. 백엔드 테스트 — XSS 페이로드 10종 차단 (backend-engineer + security-engineer)
- D6. 프론트 UI — TipTap Atlas Editor (`issue-body` variant) (designer → frontend-engineer)
- D7. E2E (qa-engineer)

보안 핵심 — 사용자가 입력한 Markdown을 렌더할 때 XSS(악성 스크립트 주입)를 막아야 함. flexmark(Markdown→HTML 렌더 라이브러리) + sanitize 단계 필수. security-engineer 공동 책임.

## 도메인 정리

- **BC**: issue-tracking (단일). 새 엔티티 없음 — 기존 **Issue Aggregate(FR-IS-01)에 필드 추가**.
- **영향 엔티티**: Issue (description/priority/labels/environment/impact 필드 추가). IssueResponse DTO + IssueController PATCH 확장.
- **새 용어 후보** (glossary 미등재 → Maxi 승인 시 추가):
  - 본문(description) — 이슈 상세 설명. Markdown 원본 저장.
  - 우선순위(priority) — 1~5 (1=Highest..5=Lowest).
  - 라벨(label) — 자유 입력 태그 (Jira 방식, 카탈로그는 파생).
  - 환경(environment) — 재현 환경 자유 텍스트.
  - 영향도(impact) — 영향 범위 등급 (SMALLINT enum).

### 결정 사항 (Maxi, 2026-05-30)

1. **본문 컬럼명 = `description`** (SDD 05-data-model 정본. FR 계획서의 `body`가 아닌 SDD 따름).
2. **라벨 = `issues.labels TEXT[]` + GIN 인덱스** (SDD 05 정본, 옵션 A). FR-IS-04는 라벨 부착/표시만. 자동완성(FR-IS-09)은 `SELECT DISTINCT unnest(labels)` prefix 매칭으로 파생 — Jira의 "카탈로그=파생" 모델과 동일. FR-IS-09의 관리 `labels` 카탈로그 테이블은 Jira에도 없는 과설계라 FR-IS-09 진입 시 재검토.
3. **우선순위 = SMALLINT 1~5 + 이름 매핑** (SDD 05 정본). 1=Highest..5=Lowest. 자동화/검색(SDD 08/09/10)이 쓰는 문자열 이름은 공유 매핑 레이어로 변환.
4. **환경 + 영향도 둘 다 FR-IS-04 포함**. environment=TEXT(자유 텍스트), impact=SMALLINT enum(예 1~3 High/Med/Low — 값 범위는 스펙에서 확정). SDD 05에 두 컬럼 신규 추가.
5. **Markdown 처리**: 원본 Markdown을 `description`에 저장. 읽을 때 flexmark로 HTML 렌더 + sanitize(허용 태그 화이트리스트)로 XSS 차단. CSRF ADR(`2026-05-20-csrf-cookie-mode`)의 "서버 측 입력 sanitization" 규정 준수.

- **기존 결정 충돌**: 없음. CSRF ADR과 정합(준수). SDD 05 내부 불일치(priority SMALLINT vs 문자열, labels TEXT[] vs 정규화)는 위 결정으로 SDD 05 정본 채택해 해소.
- **관련 ADR**: [docs/decisions/2026-05-20-csrf-cookie-mode.md] (sanitization 정책 준수). 신규 ADR 후보 — Markdown 저장/렌더/sanitize 전략 (스펙 단계에서 정식화 판단).
- **SDD 갱신 필요**: 05-data-model `issues`에 environment/impact 컬럼 추가 반영 (구현 시).
- **후속 메모**: FR-IS-09 라벨 자동완성 진입 시 — 관리 카탈로그 테이블(`labels`) 대신 TEXT[] 배열 unnest 파생 방식 재검토.

## 스펙

전체 스펙. [docs/specs/2026-05-30-fr-is-04-markdown.md](../specs/2026-05-30-fr-is-04-markdown.md)

핵심 — 5필드 전부 신규(grep 실증, 부분 구현 없음).
- description(Markdown 원본) + descriptionHtml(서버 렌더+sanitize) — XSS 차단은 서버 측(CSRF ADR 준수).
- priority SMALLINT 1~5 + 이름매핑, labels TEXT[]+GIN(자유입력), environment TEXT, impact SMALLINT 1~3.
- PATCH merge-patch 확장(null=무변경, ""/[]=클리어). IssueResponse↔Zod 1:1.
- V006 마이그레이션 + flexmark/sanitizer 의존성 신규. 프론트 TipTap(issue-body) + 셀렉터/칩.

미해결(plan-eng-review 확정) — descriptionHtml render-on-read 포함 / impact 1~3 스케일 / label 검증 정책 / sanitizer 라이브러리.

## Brainstorming Check

✅ 통과 — 범위 경계 6건 명확화(멘션→FR-MN-01 / 이력→FR-HS-01 / FTS검색→search BC / 템플릿→FR-TM-01 / 라벨자동완성→FR-IS-09 / 목록필드 subset) + TipTap Markdown 직렬화 리스크 식별. Maxi 결정 gap 0. office-hours/design-shotgun 스킵(메모리 패턴).

## Plan

> **이 PR 범위 = 백엔드 D1~D5만** (Maxi 결정 2026-05-30). 프론트(D6 TipTap+셀렉터)·E2E(D7)는 후속 PR. FR-IS-01/02 선례(백엔드→프론트→E2E 분할) 동일.

### 확정 결정 (spec 미해결 4건)
- descriptionHtml render-on-read → IssueResponse 포함 (캐싱 Deferred).
- impact 스케일 1~3 (1=High,2=Medium,3=Low).
- label 검증 — 라벨당 ≤50자, 공백 불가, 이슈당 ≤20개, 중복 dedup, 빈 문자열 제거.
- sanitizer = OWASP Java HTML Sanitizer (allowlist 정책).

> **모듈 직렬화 주의** (memory `bts-plan-wave-gradle-module-compile`). 모든 백엔드 task가 `backend/modules/issue-tracking` 단일 test source set 공유 → RED 병렬 dispatch 시 서로 컴파일 차단. bts-impl은 이 모듈 task들을 같은 wave에 묶지 말 것(파일 안 겹쳐도 직렬).

### Task 1. V006 마이그레이션 — issues 5컬럼 추가 + GIN 인덱스

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V006__issue_body_priority_labels.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/.../migration/V006MigrationTest.kt`]
- depends-on: []

**RED**: Testcontainers 통합 테스트 — V006 적용 후 `issues`에 description/priority/labels/environment/impact 컬럼 존재 + 기존 행 priority=3·labels='{}' backfill + GIN 인덱스 존재 단언. (컬럼 부재로 실패.)

**GREEN**:
1. V006 — `ALTER TABLE issues ADD COLUMN description TEXT, ADD COLUMN priority SMALLINT NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5), ADD COLUMN labels TEXT[] NOT NULL DEFAULT '{}', ADD COLUMN environment TEXT, ADD COLUMN impact SMALLINT CHECK (impact BETWEEN 1 AND 3); CREATE INDEX ix_issues_labels_gin ON issues USING GIN (labels);`
2. **`init_codegen.sql` 미러 (B3 — 필수)** — 동일 5컬럼 + GIN 인덱스를 jOOQ codegen용 init SQL에도 추가. V005 `type_id` 선례 동일 패턴. 이게 빠지면 jOOQ가 `ISSUES.DESCRIPTION/PRIORITY/LABELS/ENVIRONMENT/IMPACT` 상수 미생성 → Task 5 컴파일 불가.

**REFACTOR**: 컬럼 주석(COMMENT) + 마이그레이션 헤더 KDoc.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*V006MigrationTest" --rerun-tasks` + **`./gradlew :modules:issue-tracking:generateJooq`** (jOOQ 재생성 — ISSUES 신규 컬럼 상수 생성 확인. Docker 컨테이너 기동 필요).

### Task 2. Issue 도메인 — 5필드 + create/update 불변식

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueTest.kt`]
- depends-on: []

**RED**: IssueTest — (a) create()가 priority 기본 3·labels 빈 리스트·description/environment/impact null 로 생성 (b) 라벨 불변식: 빈 문자열 제거·중복 dedup(**대소문자 구분 exact match** — "regression"≠"Regression", spec E6 Jira 라벨 대소문자 보존)·공백 포함/50자 초과/20개 초과 시 예외 (c) priority∈1..5, impact∈1..3 범위 밖 예외.

**GREEN**: Issue에 `description: String?`, `priority: Int = 3`, `labels: List<String> = emptyList()`, `environment: String?`, `impact: Int?` 추가 + 라벨/범위 검증 헬퍼(`normalizeLabels`, `require` guard).

**REFACTOR**: 라벨 정규화/범위 상수 추출 + KDoc. 도메인 예외는 기존 IssueException 패턴 재사용.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueTest" --rerun-tasks`

### Task 3. 우선순위/영향도 이름 매핑 (공유 레이어)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssuePriority.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueImpact.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssuePriorityTest.kt`]
- depends-on: []

**RED**: 1↔Highest..5↔Lowest, 1↔High..3↔Low 양방향 매핑 + 범위 밖 입력 처리 단언.

**GREEN**: SMALLINT↔이름 매핑 enum/object (자동화·검색 SDD 08/09/10 재사용 대비 공개 API).

**REFACTOR**: 매핑 테이블 단일 출처화 + KDoc(매핑 근거 SDD 인용).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssuePriorityTest" --rerun-tasks`

### Task 4. Markdown 렌더 + sanitize (XSS 10종 차단) — 보안 핵심

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/issue-tracking/build.gradle.kts`, `gradle/libs.versions.toml`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/markdown/MarkdownRenderer.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/markdown/MarkdownRendererTest.kt`]
- depends-on: []

> **B2 — Maxi 승인 완료(§1.4.17, 게이트1 2026-05-30)**. flexmark는 SDD 03-tech-stack 명시. **OWASP Java HTML Sanitizer 의존성 = Maxi 승인됨**(게이트1 AskUserQuestion). libs.versions.toml에 추가 진행. SDD 03-tech-stack에도 sanitizer 라이브러리 반영 권장(후속).

**RED**: MarkdownRendererTest — XSS 페이로드 10종(`<script>`, `<img onerror>`, `[x](javascript:...)`, `<iframe>`, `data:` 이미지, on* 핸들러, `<style>`, HTML 엔티티 우회, 중첩 태그, svg/onload) 전부 결과 HTML에 실행 코드 0건 + 정상 Markdown(헤더/리스트/코드블록/링크)은 보존 단언.

**GREEN**: flexmark(MD→HTML) → OWASP Java HTML Sanitizer(allowlist) `MarkdownRenderer.renderSafe(md): String`. 정책 디테일(C2):
- 링크 스킴 AttributePolicy — `a[href]`는 http/https/mailto만 허용(`javascript:`/`data:` 거부).
- 코드블록 보존 — `pre`/`code` 허용 + `code[class]`는 `language-*` 값만 화이트리스트(하이라이팅 유지).
- flexmark raw-HTML escape 활성 — 사용자 입력 raw HTML을 flexmark 단계에서 escape(`<svg onload>`·중첩 태그가 sanitizer 전에 무력화), sanitizer는 2차 방어.

**REFACTOR**: allowlist 정책 상수화 + KDoc(CSRF ADR `2026-05-20-csrf-cookie-mode` 링크). 의존성은 libs.versions.toml 버전 카탈로그 경유.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*MarkdownRendererTest" --rerun-tasks`

### Task 5. 리포지토리 영속 — 신규 컬럼 읽기/쓰기 매핑

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/.../IssueRepositoryTest.kt`]
- depends-on: [1, 2]  # Task 1의 **init_codegen.sql + generateJooq 재생성 완료**에 의존(ISSUES 신규 컬럼 jOOQ 상수). 단순 마이그레이션 파일 존재 아님.

**RED**: IssueRepositoryTest(Testcontainers) — 5필드 저장 후 조회 시 round-trip 일치(labels 배열·null 필드 포함) + updateFields가 신규 컬럼 version+1 OCC UPDATE.

**GREEN**: INSERT/SELECT/UPDATE에 description/priority/labels/environment/impact 매핑. **labels 배열 매핑(C1)** — jOOQ가 생성한 `ISSUES.LABELS`(`Array<String?>?`) ↔ 도메인 `List<String>` 변환, NULL 요소 방어, 빈 배열≠null 구분. 프로젝트 최초 배열 컬럼이라 selplate 없음 — round-trip 테스트로 검증.

**REFACTOR**: 컬럼 매핑 중복 제거.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueRepositoryTest" --rerun-tasks`

### Task 6. 애플리케이션 서비스 + PATCH 컨트롤러 + IssueResponse DTO (merge-patch)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `.../application/IssueApplicationRequests.kt` (C4 — UpdateIssueRequest data class가 이 파일 안에 있음, 새 파일 만들지 말 것), `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `.../rest/IssueResponse.kt`, `.../rest/UpdateIssueRequest.kt`(web DTO), `backend/modules/issue-tracking/src/test/kotlin/.../application/IssueApplicationServiceTest.kt`]
- depends-on: [2, 3, 4, 5]

**B1 — merge-patch 3-state 매핑표 (필수)**. JSON 필드부재·`null`·값 3상태를 명확히. summary와 비대칭 주의 — summary는 `@Pattern`이 `""`를 400 거부하지만 description/environment는 `""`=클리어 허용.

| 필드 | 부재 / JSON null | `""` 또는 `[]` | 값 |
|---|---|---|---|
| description | 무변경 | `""` → DB NULL(클리어) | Markdown 설정 |
| environment | 무변경 | `""` → DB NULL(클리어) | 설정 |
| labels | 무변경 | `[]` → 빈 배열(전체제거) | 교체 |
| priority | 무변경 | — | 1..5 설정 |
| impact | 무변경 | — | 1..3 설정(`null` 명시로 클리어는 미지원, 부재=무변경만) |

> 표현 — nullable 필드로 부재·null을 모두 "무변경"으로 처리(기존 summary/typeId RFC 7396 규약 일관). 클리어는 description/environment의 빈 문자열 `""`, labels의 빈 배열 `[]`를 sentinel로 사용. `JsonNullable`/Optional 래퍼 도입 안 함(기존 규약·§1.4.17 회피).

**RED**: 서비스 단위 테스트 — 위 매핑표 각 행(무변경/클리어/설정) + priority·impact 범위 검증(400) + IssueResponse에 descriptionHtml(단건 렌더)·priorityName·impactName·labels 노출. **descriptionHtml 목록 제외(C3)** — 단건 GET만 렌더, 목록(listWithType) 응답엔 descriptionHtml 미포함(목록 N건 렌더 회귀 방지) 단언.

**GREEN**: UpdateIssueRequest(web+app)에 5필드 추가(@Min/@Max/@Size 검증, summary의 @Pattern은 복사 금지 — description/environment는 빈 문자열 허용) + 서비스 merge-patch 로직 + IssueResponse 매핑. **descriptionHtml은 단건 경로에서만 MarkdownRenderer 호출, 목록은 null/생략**. **트랜잭션(C5)** — 기존 `IssueApplicationService` 클래스 레벨 `@Transactional` 상속, 신규 merge-patch는 기존 `updateIssue` 확장(새 메서드 분리해 @Transactional 누락 금지).

**REFACTOR**: merge-patch 헬퍼 추출(부재/null=skip, `""`/`[]`=clear) + DTO KDoc.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueApplicationServiceTest" --rerun-tasks`

### Task 7. 통합 테스트 — 엔드투엔드 PATCH/GET + backfill + OCC

**메타**.
- agent: `backend-engineer` (+ security-engineer 검토)
- files: [`backend/modules/issue-tracking/src/test/kotlin/.../rest/IssueControllerIntegrationTest.kt`]
- depends-on: [6]

**RED**: Testcontainers 통합 — (a) 기존 이슈 GET 시 priority=3·labels=[] backfill 확인 (b) PATCH로 본문 작성 후 GET descriptionHtml 렌더 + XSS 차단 (c) 각 필드 merge-patch/클리어 (d) priority 6 → 400 (e) expectedVersion 불일치 → 409.

**GREEN**: 배선 보정(있으면).

**REFACTOR**: 테스트 헬퍼/픽스처 정리.

**검증**: `./gradlew :modules:issue-tracking:test --rerun-tasks` + `ktlintCheck` + `detekt`

## Plan 메타

- task 수: 7 (전부 백엔드, TDD 사이클 단위)
- 범위: 백엔드 D1~D5만. 프론트(D6)·E2E(D7)는 후속 PR.
- 모듈 직렬화: 7 task 모두 `backend/modules/issue-tracking` test source set 공유 → bts-impl은 같은 wave 묶음 금지(memory `bts-plan-wave-gradle-module-compile`). depends-on 그래프 — T1/T2/T3/T4 독립(단 모듈 직렬), T5←[1,2], T6←[2,3,4,5], T7←[6].
- **codegen 의존(B3)**: T5/T6/T7은 Task 1의 `init_codegen.sql` 갱신 + `generateJooq` 재생성 완료에 의존(ISSUES 신규 컬럼 jOOQ 상수). 단순 마이그레이션 파일 존재가 아니라 codegen 산출물이 선행. bts-impl은 Task 1 GREEN 직후 generateJooq를 1회 실행해야 T5 컴파일 가능.
- **게이트1 Maxi 승인 항목(B2)**: OWASP Java HTML Sanitizer 의존성(§1.4.17). 승인 전 Task 4 보류.
- TDD 강제: yes (test: 커밋이 feat: 보다 먼저)
- 추가 검증: ktlint, detekt(--rerun-tasks 필수, 캐시 false-green 회피), Testcontainers
- 보안: Task 4 XSS 10종 차단 security-engineer 주도, CSRF ADR 준수
- SDD 갱신: 05-data-model에 environment/impact 컬럼 반영(구현 시)

## 리뷰 결과

### eng + security 독립 리뷰 (code-reviewer dispatch, 2026-05-30)

autoplan 대신 eng 집중 독립 리뷰(메모리 `bts-review-plan-autoplan-overkill`). 실제 코드 grep/read 대조.

**BLOCKER 3건 (전부 plan 반영 완료, OWASP는 게이트1 Maxi 승인 대기)**
- B1. merge-patch 3-state 시맨틱 — 기존 코드는 nullable로 null=무변경만 표현(클리어 개념 없음), `summary`는 `@Pattern`이 `""`를 400 거부(비대칭). → Task 6에 3-state 매핑표 명시 반영.
- B2. OWASP Java HTML Sanitizer = SDD 미명시 신규 의존성(§1.4.17 Maxi 승인 필요). flexmark는 SDD 03 명시라 OK. → Task 4에 Maxi 승인 플래그 + 게이트1 결정 항목으로 격상.
- B3. `db/codegen/init_codegen.sql` V006 미러 누락 → jOOQ가 ISSUES 신규 컬럼 상수 미생성 → Task 5 컴파일 불가. V005 type_id 선례(init_codegen.sql). → Task 1에 init_codegen.sql 갱신 + generateJooq 재생성 반영, depends-on에 codegen 의존 명시.

**CONCERN 5건 (plan 반영 완료)**
- C1. labels TEXT[] = 프로젝트 최초 배열 컬럼. jOOQ `Array<String?>?` ↔ 도메인 `List<String>` 변환·NULL 요소 방어 → Task 5 명시. dedup은 대소문자 구분(exact, spec E6) → Task 2 명시.
- C2. sanitize 정책 디테일 — 링크 스킴 AttributePolicy(http/https/mailto), `pre/code`+`class` 언어 화이트리스트, flexmark raw-HTML escape 설정 → Task 4 명시.
- C3. descriptionHtml은 `IssueResponse.from()`이 단건+목록 공유 → 목록 N건 렌더 회귀 위험. 단건만 렌더, 목록 제외 → Task 6 명시.
- C4. 파일명 — `application/UpdateIssueRequest.kt`(X) → 실제 `IssueApplicationRequests.kt` → Task 6 files 교정.
- C5. updateIssue 트랜잭션 확장(기존 클래스 `@Transactional` 상속, 새 메서드 분리 금지) → Task 6 명시.

**PASS**: 모듈 직렬화 wave(§5), 소프트삭제 404·OCC(기존 가드 상속), V006 네임스페이스/CHECK/GIN(SDD 05 정합), TDD 형식.

### 잘 된 점 (리뷰어 확인)
- grep 실증으로 5필드 전부 신규 확인(부분구현 함정 회피). 모듈 직렬화 memory 인용 정확. 범위경계 6건 분리 명확.
