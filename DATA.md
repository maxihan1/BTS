# DATA.md

> **BTS 개발 헌법 §3 — 데이터 / DB / 마이그레이션 규칙.**
> 위반 시 데이터 무결성 손상 가능. `/bts-codereview`가 `migration`/`auth`/이슈키 영향 PR에 인라인 가이드로 첨부.
> 자세한 스키마. `docs/sdd/05-data-model.md`. 정책 결정. `Maxi_wiki/BTS/decisions/`.

**목차.** [§1 절대 원칙 5개](#1-절대-원칙-bts-데이터-무결성-5원칙) · [§2 이슈 키 영속성](#2-이슈-키-영속성-11-상세) ·
[§3 소프트 삭제](#3-소프트-삭제-12-상세) · [§4 Flyway 마이그레이션](#4-flyway-마이그레이션-13-상세) ·
[§5 jOOQ 쿼리 규칙](#5-jooq-쿼리-규칙-13--1-보안-3) · [§6 트랜잭션 경계](#6-트랜잭션-경계-14-상세) ·
[§7 PostgreSQL 특화](#7-postgresql-특화-fts--pgmq--인덱스) · [§8 토큰/비밀값 저장](#8-토큰--비밀값-저장-1-보안-1) ·
[§9 데이터 마이그레이션](#9-데이터-마이그레이션-jira-import--fr-im-01-인앱-구현으로-갱신-2026-07-02) · [§10 변경 이력](#10-변경-이력)

## §1. 절대 원칙 (BTS 데이터 무결성 5원칙)

AIG의 "자금 손실 5원칙"과 동일 패턴. 위반 시 즉시 PR BLOCKER.

1. **이슈 키(`PROJ-123`)는 영구 보존** — 재발급 금지. 이동/삭제 시 `IssueKeyRedirect`로 옛 키 보존.
2. **`DELETE`는 항상 `WHERE` + 소프트 삭제 우선** — 하드 삭제는 ADR + Maxi 확인 필수.
3. **마이그레이션은 Flyway만** — 수동 ALTER TABLE / 임시 SQL 금지.
4. **트랜잭션 경계 명시** — `@Transactional` 누락 = BLOCKER. 코드리뷰가 잡는다 (Detekt 커스텀 룰은 **미구현** — §6 은 계획이다).
5. **인증/CSRF 우회 불가** — Spring Security 필터 체인 변경은 plan-eng-review + plan-ceo-review 필수.

## §2. 이슈 키 영속성 (§1.1 상세)

### 이슈 키 발급 흐름

1. 이슈 생성. `project.key_sequence` 증가 → 새 키 `<PROJ_KEY>-<NUMBER>` 발급
2. 키는 **삭제/재사용 불가**. 시퀀스는 갭 허용 (롤백된 트랜잭션도 시퀀스 소비)
3. 이슈 자체 삭제. 소프트 삭제 (`deleted_at`), 키는 `issues` 테이블에 영구 잔존

### 이슈 이동 (프로젝트 변경)

이슈를 다른 프로젝트로 이동 시.

1. 이동 대상 프로젝트에서 새 키 발급. 예. `OLD-42` → `NEW-15`
2. `IssueKeyRedirect (old_key, new_key, redirected_at)` row insert
3. 옛 이슈의 `key` 필드는 새 키로 업데이트, **`issues.id`는 불변**
4. API 응답 시 `GET /issues/OLD-42` → 308 Permanent Redirect → `GET /issues/NEW-15`
5. Slack Unfurl, 외부 인용 모두 redirect로 살아 있음

### 키 재발급 시도 차단

- `IssueKeyRedirect` UNIQUE INDEX on `old_key` — 같은 옛 키로 두 번 redirect 불가
- 새 키 발급 시 `IssueKeyRedirect.new_key`도 체크 (옛 키와 충돌 방지)

## §3. 소프트 삭제 (§1.2 상세)

### 적용 대상

- `issues`, `comments`, `projects`, `users` — 모두 `deleted_at TIMESTAMPTZ NULL`
- `workflows`, `statuses` — 워크플로우 정의와 전역 상태 카탈로그 (V205 · V203 · FR-WF-04)
  - **키 재사용을 열어 두려면 유니크가 부분 인덱스여야 한다.** `V206` 이 두 테이블의 `key` 유니크를
    `WHERE deleted_at IS NULL` 부분 인덱스로 바꾼 이유다. 컬럼 UNIQUE 로 두면 **지운 key 를 영원히
    다시 쓸 수 없다** — 소프트 삭제를 도입하는 테이블은 이 짝을 반드시 함께 본다.
  - **정리 정책 없음(의도).** 편집 이력 보존이 목적이고 규모가 작다(워크플로우·상태 각 수십 단위).
    규모가 커지면 재검토한다. `TODOS.md` 56 과 함께 본다.
- **자동 필터 없음** — `deleted_at IS NULL` 조건은 repository마다 쿼리에 수동으로 붙인다(공통 `SoftDeleteFilter` 래퍼는 존재하지 않음, `.kt` grep 히트 0). 새 쿼리/술어를 작성할 때 빠뜨리면 삭제된 행이 그대로 노출된다 — 아카이브·소프트삭제 술어를 설계할 때 이 문장을 "자동 처리됨"으로 오독하지 말 것

### 하드 삭제 허용 영역 (예외)

- **세션/임시 토큰** (`sessions`, `refresh_tokens`) — TTL 만료 후 GC
- **알림** (`notifications`) — 사용자가 읽고 30일 경과 시 삭제 가능
- **감사 로그 (`audit_logs`)** — 절대 삭제 금지 (소프트도 안 함, append-only)
- **첨부** (`issue_attachments`) — 삭제 시 DB row + MinIO 객체 즉시 제거. 대용량 바이너리(스토리지 누적 회피) + 이슈 키와 달리 외부 영구 인용이 약해 보존 가치 낮음. `deleted_at` 컬럼 없음. ADR `2026-06-15-fr-ac-01-attachment-storage` (FR-AC-01, Maxi 확정)
- **즐겨찾기** (`favorites`) — 즐겨찾기 해제(unstar)는 행 즉시 제거. 개인 북마크 토글로 복구 가치 낮음(Watcher와 동일 성격). `deleted_at` 컬럼 없음. ADR `2026-06-24-fr-ux-02-favorites` (FR-UX-02, Maxi 확정)
- **저장된 필터** (`saved_filters`) — 필터 삭제 시 행 즉시 제거. 외부 영구 인용 약함(이슈 키와 다름). `deleted_at` 컬럼 없음. ADR `2026-06-26-fr-sr-03-saved-filters` (FR-SR-03, Maxi 확정)
- **대시보드 공유 토큰** (`dashboard_share_tokens`) — 공유 링크 취소(revoke) 시 행 즉시 제거. 세션/refresh_token/PAT와 동일한 임시 자격증명(ephemeral credential)이며 복구는 재발급으로 대체. `deleted_at` 컬럼 없음. ADR `2026-07-02-fr-db-03-dashboard-share` (FR-DB-03, Maxi 확정)
- **단축키 커스터마이즈** (`user_keymap`) — 기본값 복원 시 override 행 즉시 제거(replace-all PATCH의 `DELETE ... WHERE user_id`). 개인 설정 토글로 복구 가치 낮음(favorites와 동일 성격), 프론트 기본 키맵으로 언제든 재구성. `deleted_at` 컬럼 없음. ADR `2026-07-08-fr-pf-03-keymap-customize` (FR-PF-03, Maxi 확정)
- **전역 권한 부여** (`global_permission_grants`) — 회수(revoke) 시 행 즉시 제거. 외부 영구 인용 없음(이슈 키와 다름 — Slack·이메일·외부 문서가 grant `id`를 참조하지 않는다), 재부여는 새 행으로 충분. 소프트 삭제로 남기면 `UNIQUE (permission, grantee_type, grantee_id)`에 부분 인덱스 조건이 붙어 멱등성 근거가 복잡해진다. 삭제 행 수 0이면 404로 거부한다(조용한 성공 금지). `deleted_at` 컬럼 없음. ADR `2026-07-17-global-permission-grants` (FR-PM-10, Maxi 확정)
- **전환 규칙 (검증기·후처리)** (`workflow_validators`, `workflow_post_actions`) — 관리자가 규칙을 뗄 때 행 즉시 제거. 켜고 끄는 설정 행이라 외부 영구 인용 없음(이슈 키와 다름 — Slack·이메일·외부 문서가 validator/post-action `id`를 참조하지 않는다), 재생성은 새 행으로 충분. 전환 삭제 시 `transition_id ... ON DELETE CASCADE`가 이미 같은 행을 물리 삭제하므로 행 단위 경로만 다른 정책을 쓸 이유가 없다. 소프트 삭제로 남기면 조회마다 `deleted_at IS NULL`이 붙고(공통 필터 없음) `display_order` 순서 계산에 죽은 행이 섞인다. 삭제 전 존재·소속 검사로 미존재는 404(조용한 성공 금지). `deleted_at` 컬럼 없음(V200부터). ADR `docs/adr/2026-08-25-workflow-transition-rule-hard-delete` (FR-WF-06, Maxi 확정 대기)

### 영구 삭제 (GDPR 등 법적 요청)

- 사용자 본인 요청 시. ADR 작성 → `users.deleted_at` 설정 + PII 필드 무명화 (`email`, `display_name`을 hash로 대체)
- 이슈 자체는 삭제 안 함. 작성자만 `[deleted]`로 표시

## §4. Flyway 마이그레이션 (§1.3 상세)

### 파일 명명 규칙

```
backend/modules/<bc>/src/main/resources/db/migration/
├── V<번호>__<설명>.sql
└── ...
```

- 접두사. `V<번호>__<설명>.sql`
- 번호. 단조 증가 정수 (gap 허용), **BC 별 100단위 범위 할당** (§4.1)
- 설명. snake_case, 명령형 어조 (`add_`, `drop_`, `rename_`)

### §4.1. BC 별 번호 범위 (cross-BC 충돌 방지)

cross-BC 의존이 도입된 모듈 (예: project-workflow → issue-tracking) 에서
classpath 합쳐질 때 동일 버전 번호 (`V001`) 가 두 모듈에 존재하면
Flyway 가 `Found more than one migration with version` 으로 실패한다.

이를 막기 위해 BC 별 100단위 범위를 할당한다.

| BC                 | 범위        | 사용 중                           |
|--------------------|------------|------------------------------------|
| identity-access    | V001~V099  | V001~V006                         |
| issue-tracking     | V001~V099  | V001~V003 (grand-fathered)        |
| project-workflow   | V200~V299  | V200~V205                         |
| automation         | V300~V399  | V300~V310                         |
| notification       | V400~V499  | V400, V401                        |
| agile-planning     | V500~V599  | V500                              |
| search-export-import | V600~V699 | V600~V603                       |
| slack-integration  | V700~V799  | V700~V703                         |

**grand-fathered 예외**. identity-access (V001~V006) 와 issue-tracking (V001~V003)
는 cross-BC 의존이 도입되기 전부터 V001 부터 시작했으므로 그대로 유지.
project-workflow 가 issue-tracking 을 cross-BC import 하면서 처음 충돌이
노출되어 V200~ 으로 옮겨졌다 (ADR `2026-05-26-bc-migration-prefix-policy`).

**새 BC 가 추가될 때**. 위 표에 다음 사용 가능 범위 (V300, V400, ...)
를 부여하고, 첫 마이그레이션은 해당 범위의 시작 번호로 작성한다.

### 마이그레이션 작성 시 규칙

1. **롤백 가능성을 항상 고려.** 큰 변경은 `V100__add_column.sql` + `V101__backfill.sql` + `V102__drop_old.sql`로 분할
2. **`NOT NULL` 컬럼 추가**. 반드시 default 값 또는 backfill 마이그레이션 동반
3. **인덱스는 `CONCURRENTLY`** (PostgreSQL) — 대형 테이블 락 회피
4. **타임존**. `TIMESTAMPTZ` 강제. `TIMESTAMP` (without time zone) 금지
5. **테스트**. Testcontainers Postgres 기동 → 전체 마이그레이션 체인 적용 → 검증

### `db-engineer` 에이전트 책임

- 새 마이그레이션 파일 작성
- 기존 데이터 보존 검증 (테스트로)
- `prisma migrate diff` 같은 자동 drift 체크 도구 도입 (Phase 1)

## §5. jOOQ 쿼리 규칙 (§1.3 + §1 보안 §3)

### 금지

- `Connection.createStatement` 직접 사용
- `String.format("SELECT ... %s ...", userInput)` 같은 문자열 결합
- `dsl.execute(rawSql)` — 정적 검증 우회

> **예외 (2026-05-26 추가)**. 다음은 위 금지의 정식 예외 — (i) parameter binding (`?` placeholder) 사용 + (ii) jOOQ 미지원 PostgreSQL 함수 (pg_advisory_lock, pgmq.send 등) 호출. 자세한 결정 근거 + 잠재 잘못된 사용 가드. [docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md](docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md).

### 권장

```kotlin
// 좋음. jOOQ DSL
val issues = dsl.selectFrom(ISSUES)
    .where(ISSUES.PROJECT_ID.eq(projectId))
    .and(ISSUES.DELETED_AT.isNull)
    .fetch()

// 좋음. 동적 조건은 jOOQ Condition 빌더
var cond = ISSUES.PROJECT_ID.eq(projectId).and(ISSUES.DELETED_AT.isNull)
if (assigneeId != null) cond = cond.and(ISSUES.ASSIGNEE_ID.eq(assigneeId))
val issues = dsl.selectFrom(ISSUES).where(cond).fetch()
```

### 코드 생성

- jOOQ 코드젠은 Flyway 마이그레이션 후 `./gradlew generateJooq` (CI 자동 실행)
- 생성물은 git에 커밋 (개발 환경 일관성)

## §6. 트랜잭션 경계 (§1.4 상세)

### 기본 규칙

- 모든 비즈니스 로직 메서드에 `@Transactional` 명시
- 읽기 전용은 `@Transactional(readOnly = true)`
- 격리 수준은 PostgreSQL 기본 `READ COMMITTED` (특수 요구 시만 변경)

### 다중 BC 트랜잭션 금지

- 이슈 + 알림 + 워크플로우 전환이 같이 일어나는 경우. **이벤트 발행만 트랜잭션 내**, 실제 알림 발사는 pgmq 비동기.
- 이유. BC 간 결합 최소화. 한 BC 트랜잭션이 다른 BC를 직접 호출하면 격리 깨짐.

```kotlin
@Transactional
fun transitionIssue(key: IssueKey, action: TransitionAction) {
    // 1. 워크플로우 검증 (자기 BC 내)
    workflowEngine.validate(...)
    // 2. 이슈 상태 변경 (자기 BC 내)
    issueRepository.updateStatus(...)
    // 3. 이벤트만 발행 (pgmq에 enqueue, 알림 발사는 비동기)
    eventPublisher.publish(IssueTransitioned(...))
}
```

### Detekt 커스텀 룰 (Phase 1)

- public service 메서드인데 `@Transactional` 없으면 빌드 실패
- 예외. `@TransactionalAware` 어노테이션으로 명시 (호출자가 책임지는 경우)

## §7. PostgreSQL 특화 (FTS / pgmq / 인덱스)

### FTS (전체 텍스트 검색)

- `tsvector` 컬럼은 generated column (`STORED`)
- 한국어 형태소. `pg_search_korean` 또는 `mecab-ko` 확장 (Phase 0)
- GIN 인덱스 필수

```sql
ALTER TABLE issues ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', summary || ' ' || description)) STORED;
CREATE INDEX idx_issues_search ON issues USING GIN(search_vector);
```

### pgmq (메시지 큐)

- Kafka 대체 (1K 사용자 규모면 충분)
- 큐 이름은 BC별. `q_notifications`, `q_automation`, `q_email`
- 트랜잭션 발행. `pgmq.send_with_delay(queue, payload, 0)` — 같은 트랜잭션 내 enqueue 가능
- 컨슈머 워커는 별도 Spring Boot 프로세스 (또는 같은 프로세스의 스케줄러)

### 인덱스 정책

- 외래 키는 항상 인덱스 (PostgreSQL은 자동 안 함)
- 복합 인덱스는 카디널리티 높은 컬럼 먼저
- `pg_stat_user_indexes`로 미사용 인덱스 주기 점검 (월 1회)

## §8. 토큰 / 비밀값 저장 (§1 보안 §1)

### 패스워드

- Argon2id (Spring Security `Argon2PasswordEncoder`)
- 파라미터. memory 65536, iterations 3, parallelism 1 (NIST 권장)

### Personal Access Token

```kotlin
// 발급 시
val rawToken = SecureRandom().nextToken(32) // 사용자에게 1회만 표시
val tokenHash = sha256(rawToken)
patRepository.save(PersonalAccessToken(userId, tokenHash, ...))
// rawToken은 DB에 절대 저장 안 함
```

### 외부 API 키 (Slack, OAuth client secret)

- KMS 암호화 후 DB 저장
- `application.yml`의 키는 환경 변수에서 주입 (`${BTS_KMS_KEY:?required}`)

## §9. 데이터 마이그레이션 (Jira Import) — FR-IM-01 인앱 구현으로 갱신 (2026-07-02)

**초기 SDD 원안(별도 도구 `tools/jira-import/`·Phase 4·Jira 키 보존)은 FR-IM-01(PR #218)에서 인앱 기능으로 대체됐다.** 실제 구현 방식은 아래를 정본으로 한다(SDD 15장은 원안 참조용). ADR `docs/decisions/2026-07-02-fr-im-01-csv-json-import.md`.

### 실제 구현 원칙 (FR-IM-01)

- **인앱 기능** — 별도 도구 아님. `POST /api/v1/imports`(multipart) → MinIO(`bts-imports`) 저장 → pgmq `q_import_jobs` 백그라운드 worker. search-export-import BC(`com.bts.search.imports`), FR-EX-02(비동기 Export) 역방향 미러.
- **새 키 자동생성** — Jira 원본 키는 **보존하지 않는다**(`incrementKeySequence`로 새 키 발급). "이슈 키 재사용 금지" 원칙(§1.1)·충돌 위험 회피. (SDD 원안의 "Jira 키 보존"은 superseded — Maxi 확정.)
- **행별 best-effort** — 한 행 실패가 다른 행 롤백 안 함(행 원자성: create+update 1 tx). 실패행은 에러 로그(MinIO CSV, formula injection 정화)에 기록. 별도 `import_checkpoint` 테이블 없이 `import_jobs`(V604)가 total/succeeded/failed 카운트 집계. 재실행 시 중복 생성 가능(MVP 허용, dry-run 사전검증 권장).
- **에픽 PR 구조** — PR1 코어(이슈 코어 필드) → PR2 컴포넌트/버전 자동생성+상태 전환 → PR3 댓글/Worklog → PR4 첨부(zip 업로드)/이력. 첨부 바이너리는 **zip 아카이브 업로드**(서버 fetch 없음=SSRF 없음), MinIO 저장은 PR4.
- **cross-BC 쓰기** — search BC는 issue-tracking을 직접 호출하지 않고 shared-kernel `IssueImportPort`(BTS 최초 쓰기 포트)로 위임. 권한(CREATE/UPDATE)은 issue-tracking `createIssue`/`updateIssue`가 판정(우회 불가).

## §10. 변경 이력

- 2026-05-19. 초안. BTS 데이터 무결성 5원칙 정의 + Flyway/jOOQ/트랜잭션/FTS/pgmq 규칙 통합.
- 2026-05-26. §5 예외 단서 추가. parameter binding + jOOQ 미지원 PG 함수 한정 예외 등록. 근거 — [docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md](docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md).
