# FR-AT-07 — PR 머지 연동 (Fix Version 자동 설정) · 백엔드 D1~D5 스펙

> plan. [docs/plans/2026-07-15-fr-at-07-pr-merge.md](../plans/2026-07-15-fr-at-07-pr-merge.md) — 도메인 사실 F1~F9 / 결정 D1~D6
> PR. #274 | slug. `fr-at-07-pr-merge` | BC. automation
> **범위**. D1~D5(백엔드). D6(Webhook URL 생성 UI)·D7(E2E)는 후속 PR.

## 확정된 행위 결정 (Maxi)

| # | 결정 | 기각안 |
|---|---|---|
| D1 | **신규 `TriggerType.PR_MERGED` + 전용 엔드포인트** — 기존 pgmq→`ActionExecutor` 파이프라인 재사용 | 기존 WEBHOOK 재사용 / 룰 엔진 우회 |
| D2 | **인바운드 permitAll 3종 일괄 중앙등록** (git 신규 + automation FR-AT-01 + slack) | git만 / 계속 미룸 |
| D3 | **신규 포트 메서드 `IssueMutationPort.setFixVersions`** | `setField` 화이트리스트 확장 |
| D4 | **룰 액션에 versionId 명시 지정** | 브랜치→버전 매핑 테이블 / 최신 UNRELEASED 자동선택 |
| D7 | **이슈 키 추출 = PR 제목 + 본문** (커밋 메시지 제외) | 커밋 메시지 포함(GitHub API 별도 호출 필요 → 범위 폭증) |
| D8 | **Closes/Fixes 계열 키워드 필수** | 본문 어디든 이슈키 매칭(오설정 위험) |
| D9 | **GitHub + GitLab 둘 다** (product doc D2 준수) | GitHub 먼저·GitLab 후속 |
| D10 | **delivery ID dedup 테이블** | 미도입(멱등성 의존) |

## 사용자 시나리오 (Given-When-Then)

### S1. GitHub PR 머지 → Fix Version 자동 설정 (happy path)
```
Given 프로젝트 PROJ에 Git 웹훅이 등록돼 있고(provider=GITHUB, secret 보유)
  And PROJ에 활성 룰 "머지되면 1.2.0으로" 가 있다
      (trigger=PR_MERGED, action=SET_FIX_VERSIONS{versionIds:[<1.2.0의 UUID>]})
 When GitHub이 pull_request(action=closed, merged=true) 웹훅을 보내고
      PR 본문에 "Closes PROJ-42" 가 있다
 Then 서명 검증 통과 → 202 응답 (200ms 이내)
  And PROJ-42의 Fix Version이 [1.2.0]으로 설정된다
  And rule_executions에 SUCCESS 1행이 남는다
```

### S2. 서명 불일치 → 거부
```
Given 등록된 웹훅의 secret이 S이다
 When X-Hub-Signature-256이 S로 계산한 값과 다르다
 Then 401 (빈 본문). 룰 미발화. 큐 미적재
```

### S3. 머지되지 않은 PR 이벤트 → 무시
```
Given 유효한 서명의 pull_request 웹훅
 When action=closed 이지만 merged=false (머지 없이 닫힘)
 Then 202. 룰 미발화 (조용히 무시)
```

### S4. 다중 이슈 키 → 각각 적용
```
Given PR 본문이 "Closes PROJ-1\nFixes PROJ-2" 이다
 When 머지 웹훅 수신
 Then PROJ-1·PROJ-2 각각에 대해 룰이 1회씩 발화 (이슈당 1 메시지 enqueue)
```

### S5. 키워드 없는 단순 언급 → 미발화
```
Given PR 본문이 "PROJ-9 관련 리팩터링" 이다 (Closes/Fixes 없음)
 When 머지 웹훅 수신
 Then 202. 룰 미발화 (D8 — 키워드 필수)
```

### S6. 재전송(replay) → 중복 미처리
```
Given 동일 X-GitHub-Delivery의 웹훅이 이미 처리됐다
 When 같은 payload가 재전송된다
 Then 202. 룰 미발화 (dedup). rule_executions 추가 행 없음
```

### S7. 권한 없는 룰 actor → fail-closed
```
Given 룰의 actor가 PROJ-42의 UPDATE 권한이 없다
 When 머지 웹훅으로 룰이 발화한다
 Then Fix Version 미변경. rule_executions에 PERMISSION_DENIED 기록
      (기존 IssueMutationPermissionDeniedException 경로 승계)
```

### S8. GitLab MR 머지 → 동일 동작
```
Given provider=GITLAB 등록 (secret=T)
 When X-Gitlab-Token: T + Merge Request Hook(object_attributes.action=merge)
  And object_attributes.description에 "Closes PROJ-7"
 Then S1과 동일하게 PROJ-7의 Fix Version 설정
```

## 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR-1 | `POST /api/v1/webhooks/git/{token}` 엔드포인트. permitAll + CSRF ignore. 성공 202 |
| FR-2 | token(SHA-256 해시 조회)으로 등록 행 식별 → provider·projectKey·secret 확보. 미존재/삭제 균일 **404**(존재 숨김, FR-AT-01 선례) |
| FR-3 | **서명 검증 fail-closed**. GITHUB=HMAC-SHA256(`X-Hub-Signature-256: sha256=<hex>`) / GITLAB=평문 토큰(`X-Gitlab-Token`). 둘 다 **상수시간 비교**. 불일치·헤더누락·secret 미설정 → **401**(빈 본문) |
| FR-4 | **머지 이벤트만 처리**. GITHUB=`X-GitHub-Event: pull_request` + `action=="closed"` && `pull_request.merged==true` / GITLAB=`X-Gitlab-Event: Merge Request Hook` + `object_attributes.action=="merge"`. 그 외 → 202 무시 |
| FR-5 | **delivery dedup**. `{provider}:{deliveryId}` PK 삽입 시도 → 중복이면 202 조기 반환(재처리 없음). deliveryId = `X-GitHub-Delivery` / `X-Gitlab-Event-UUID`, **헤더 부재 시 rawBody의 SHA-256으로 대체**(결정적 fallback) |
| FR-6 | **이슈 키 추출**. PR **제목 + 본문**에서 `Closes/Fixes/Resolves` 계열 키워드 + 이슈키. 키워드 대소문자 무시, **이슈키는 대문자 고정**. 중복 제거 |
| FR-7 | **프로젝트 스코프 필터**. 추출된 이슈키 중 prefix가 등록 행의 `project_key`와 **일치하는 것만** 처리. 불일치는 무시(로그) |
| FR-8 | **룰 팬아웃**. `findEnabledByProjectAndTriggerType(projectKey, PR_MERGED)` × 이슈키 수만큼 `q_automation_execution`에 enqueue |
| FR-9 | **triggerEvent 규약**. 최상위 `issueKey` 포함(`ActionExecutor.extractIssueKey`가 무변경 재사용됨) + PR 메타(`provider/action/title/body/number/url/targetBranch/mergedAt`) |
| FR-10 | **신규 `TriggerType.PR_MERGED`**. `trigger_config` = `{}`(설정 없음, ISSUE_CREATED/WEBHOOK 동형) |
| FR-11 | **신규 `ActionType.SET_FIX_VERSIONS`**. config `{versionIds: [UUID...]}`. 빈 배열 허용(= Fix Version 전체 해제) |
| FR-12 | **신규 포트 `IssueMutationPort.setFixVersions(SetFixVersionsCommand)`**. 전체교체·OCC·dryRun. default 구현 없음(fail-closed) |
| FR-13 | **issue-tracking 어댑터 구현**. `changeFixVersions` 유스케이스 위임(권한/검증/OCC/이력 보존). OCC 충돌 1회 재시도(기존 `runWithOccRetry` 동형) |
| FR-14 | **중앙 SecurityConfig 등록**(D2). git 신규 + automation 웹훅(FR-AT-01) + slack 인바운드. permitAll + CSRF ignore |
| FR-15 | **웹훅 등록 API**. `POST /api/v1/projects/{projectKey}/automation/git-webhooks`(생성, 토큰·secret **1회 노출**) / `GET`(목록, 토큰·secret 미노출) / `DELETE /{id}`(소프트 삭제). 권한 = `MANAGE_AUTOMATION`(기존 `AutomationPermissionResolver`) |
| FR-16 | **프론트 계약 최소 동기화**. `triggerTypeSchema`·`actionTypeSchema` Zod enum에 신규 값 추가 + 라벨 맵 3곳. §제약 C4 |

## 비기능 요구사항 (NFR)

| ID | 요구사항 | 임계 |
|---|---|---|
| NFR-1 | 웹훅 응답 지연 (수신→202) | **p95 < 200ms** (product doc §NFR) |
| NFR-2 | payload 크기 상한 — **서명 검증 이전** 검사 | 256KB 초과 → **413** (FR-AT-01 `MAX_PAYLOAD_BYTES` 동일값) |
| NFR-3 | secret 저장 | **평문 저장/로깅 0**. AES-256-GCM(`SecretEncryptor`) |
| NFR-4 | 서명 비교 | **상수시간**(`MessageDigest.isEqual`) — 타이밍 공격 차단 |
| NFR-5 | 트리거→액션 처리 지연 | p95 < 5s (기존 automation NFR 승계) |
| NFR-6 | 권한 위반 액션 차단율 | 100% (fail-closed) |

## API 인터페이스 (REST)

### 인바운드 (외부 Git 서버 → BTS)

```
POST /api/v1/webhooks/git/{token}
  Content-Type: application/json
  Headers (GITHUB): X-Hub-Signature-256: sha256=<hex>, X-GitHub-Event, X-GitHub-Delivery
  Headers (GITLAB): X-Gitlab-Token: <plain>, X-Gitlab-Event, X-Gitlab-Event-UUID
  Body: 원문 JSON (서명 대상)

  202 Accepted   — 수신 완료 (발화/무시/중복 모두 202, 구분 미노출)
  401 Unauthorized — 서명 불일치·헤더 누락·secret 미설정 (빈 본문)
  404 Not Found  — 토큰 미존재/삭제 (존재 숨김)
  413 Payload Too Large — 256KB 초과
```

**응답 코드 설계 근거**. 발화 여부를 202로 통일해 **외부에 룰 존재/이슈키 유효성을 노출하지 않는다**(정보 누출 차단). 404 vs 401 구분은 FR-AT-01 선례 — 토큰은 라우팅 실패(404), 서명은 인증 실패(401).

### 관리 (BTS UI → BTS)

```
POST   /api/v1/projects/{projectKey}/automation/git-webhooks
       Body: { provider: "GITHUB"|"GITLAB" }
       201 → { id, provider, webhookUrl, token, secret, createdAt }
             ★ token·secret은 이 응답에서만 1회 노출 (FR-AT-01 webhookToken 선례)

GET    /api/v1/projects/{projectKey}/automation/git-webhooks
       200 → [{ id, provider, webhookUrl, createdAt }]   ★ token·secret 미포함

DELETE /api/v1/projects/{projectKey}/automation/git-webhooks/{id}
       204 (소프트 삭제)

  권한. MANAGE_AUTOMATION (AutomationPermissionResolver) — 미보유 403
```

## 데이터 모델 변경

### V306 — `automation_git_webhooks` (신규)

> **★ product doc D3 "(활용. webhook secret 저장)" 전제 폐기.** 기존 `automation_rules.webhook_token_hash`는 **SHA-256 해시(비가역)** 라 HMAC 서명 재계산에 쓸 수 없다(plan §F6). 가역 암호화 저장이 필수 → 신규 테이블. product doc 문구 정정 대상.

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `project_key` | VARCHAR(50) NOT NULL | cross-BC, FK 아님 (`automation_rules` 동형) |
| `provider` | VARCHAR(20) NOT NULL | CHECK `IN ('GITHUB','GITLAB')` — 앱 enum과 이중 방어 |
| `token_hash` | VARCHAR(64) NOT NULL | 라우팅 토큰 SHA-256(평문 미저장). 부분 UNIQUE(`WHERE deleted_at IS NULL`) |
| `secret_encrypted` | TEXT NOT NULL | **AES-256-GCM 암호문 hex** (`SecretEncryptor`) |
| `created_by` | UUID NOT NULL | cross-BC, FK 아님 |
| `created_at` / `updated_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | |
| `version` | BIGINT NOT NULL DEFAULT 0 | OCC (DATA.md §3 기본 컬럼) |
| `deleted_at` | TIMESTAMPTZ | 소프트 삭제 (DATA.md §3) |

인덱스. `uq_automation_git_webhooks_token_hash` (부분 UNIQUE, `WHERE deleted_at IS NULL`) / `idx_automation_git_webhooks_project` (`project_key`, `WHERE deleted_at IS NULL`)

### V306 — `automation_git_deliveries` (신규, dedup)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `dedup_key` | VARCHAR(200) **PK** | `"{provider}:{deliveryId}"`. append-only (`slack_delivery_log` 선례) |
| `received_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | |

**보존 정책**. 무한 증가 → 후속 정리 작업 필요(`slack_delivery_log`도 동일 미해소). §후속 과제.

### V306 — `automation_rules.trigger_type` CHECK 갱신

```sql
ALTER TABLE automation_rules DROP CONSTRAINT ck_automation_rules_trigger_type;
ALTER TABLE automation_rules ADD CONSTRAINT ck_automation_rules_trigger_type CHECK (
  trigger_type IN ('ISSUE_CREATED','ISSUE_UPDATED','ISSUE_COMMENTED','SCHEDULED','WEBHOOK','PR_MERGED')
);
```
**★ V300 편집 금지** — 이미 적용된 마이그레이션 수정은 체크섬 드리프트를 일으킨다(`:modules:app:test`가 5433 영속 DB 사용). 반드시 신규 파일.

### V306 — `automation_actions.action_type` CHECK 갱신

동일 방식으로 `'SET_FIX_VERSIONS'` 추가 (V302 편집 금지).

### shared-kernel 계약 (신규)

```kotlin
// IssueMutationCommands.kt
data class SetFixVersionsCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val versionIds: List<UUID>,   // 전체교체 시맨틱. 빈 리스트 = 전체 해제
    val expectedVersion: Long?,   // OCC. null이면 어댑터가 현재값 조회 후 적용
    val dryRun: Boolean,
)

// IssueMutationPort.kt — default 없음(fail-closed)
fun setFixVersions(cmd: SetFixVersionsCommand): MutationResult
```

## 엣지 케이스

| # | 상황 | 동작 |
|---|---|---|
| EC1 | 토큰 미존재 / 소프트삭제 / 타 프로젝트 | 균일 **404**(존재 숨김) |
| EC2 | secret 미설정(암호화 키 부재) | `SecretEncryptor.decrypt`가 `IllegalStateException` → **401**(fail-open 금지). 로그에 평문/키 미포함 |
| EC3 | `X-Hub-Signature-256` 헤더 누락 | **401** |
| EC4 | payload가 JSON 파싱 불가 | **서명 통과 후** 파싱 → 실패 시 202(무시). 서명 전 파싱 금지 |
| EC5 | 이슈키 0건 추출 | 202. 큐 미적재 |
| EC6 | 추출 이슈키 prefix ≠ 등록 project_key | 해당 키만 무시(FR-7). 나머지는 정상 처리 |
| EC7 | 이슈키가 실재하지 않음(PROJ-99999) | 웹훅은 202. 액션 실행 시 `IssueNotFound` → rule_executions FAILED |
| EC8 | PR_MERGED 룰 0건 | 202. 큐 미적재 |
| EC9 | 액션의 versionId가 타 프로젝트/삭제 버전 | `changeFixVersions`의 `validateVersions`가 422 → 어댑터가 예외 → FAILED 기록 |
| EC10 | OCC 충돌(동시 편집) | 어댑터가 version 재조회 후 **1회 재시도**. 재실패 시 FAILED |
| EC11 | 동일 delivery 재전송 | 202. dedup으로 미처리 (S6) |
| EC12 | dedup 삽입과 처리 사이 크래시 | dedup 먼저 커밋 → **at-most-once**. (트레이드오프. 유실 < 중복설정. §제약 C5) |
| EC13 | GitLab `X-Gitlab-Event-UUID` 부재(구버전) | rawBody SHA-256 fallback (FR-5) |
| EC14 | 256KB 초과 | **413**. 서명 검증 이전 (NFR-2) |
| EC15 | 룰 actor 권한 없음 | PERMISSION_DENIED 기록, 이슈 미변경 (S7) |
| EC16 | 빈 `versionIds` 액션 | Fix Version 전체 해제(정상 동작, FR-11) |

## 제약 조건

| # | 제약 |
|---|---|
| C1 | **raw body 함정**. 컨트롤러는 `@RequestBody String`만 사용. `@RequestParam`/`@ModelAttribute` **병용 금지** — Spring이 form을 먼저 파싱해 스트림을 소비하면 `@RequestBody`가 빈 문자열이 되어 **서명 검증이 조용히 무력화**된다 (`SlackCommandsController.kt:24-33` 3중 경고) |
| C2 | **BC 격리 — 정규식 값 복제**. `IssueKey.REGEX`를 import할 수 없음 → 값 복제 + 주석에 출처 명시 (`AtlasIssueUrlParser.kt:56` 선례) |
| C3 | **BC 격리 예외 — identity-access**. D2의 SecurityConfig 등록. security-engineer 공동 검토 필수 |
| C4 | **프론트 계약 파급 (필수)**. `automation-rules.types.ts:13` `triggerTypeSchema` z.enum 5종 / `:23` `actionTypeSchema` z.enum 4종이 **backend enum과 1:1 고정**. 백엔드만 추가하면 해당 룰 조회 시 **Zod parse 실패로 룰 목록 화면 전체가 깨진다**. 라벨 맵 3곳(`AutomationRuleFormDialog.tsx:72`·`AutomationRuleList.tsx:56`·`RuleExecutionTraceRow.tsx:56`) + 테스트 목록 2곳(`automation-rules.types.test.ts:256,273`) 동반 |
| C5 | **dedup 시맨틱 = at-most-once**. dedup 행을 처리 전 커밋 → 크래시 시 유실 가능. "중복 Fix Version 설정" 보다 "누락"이 안전하다는 판단(수동 재발송 가능) |
| C6 | **enum 카운트 가드 전수**. `TriggerConfigTest.kt:17,21`(`entries.size shouldBe 5`) · `ActionTest.kt:18,22`(`shouldBe 4`) · `SchemaMigrationTest.kt`(CHECK 5종) 동반 갱신 |
| C7 | **마이그레이션 신규 파일만**. V300/V302 편집 금지(체크섬 드리프트) |
| C8 | **prod 조립 재검증**. cross-BC 포트 추가 → 머지 전 `origin/main` rebase + `:modules:app:test` |
| C9 | **신규 암호화 빈**. `automationSecretEncryptor`(`bts.automation-encryption.{key,salt}`) — BC별 키 격리 관례. **빈은 항상 등록**(`@ConditionalOnProperty` 금지), 사용 시점 `check(configured)`. salt는 **hex** |
| C10 | **신규 의존성 0**. HMAC은 JDK `javax.crypto.Mac`. YAML/HTTP 클라이언트 추가 없음 |

## 이슈 키 추출 규칙 (상세)

```
키워드. close|closes|closed|fix|fixes|fixed|resolve|resolves|resolved  (대소문자 무시)
이슈키. [A-Z][A-Z0-9]{1,9}-[1-9][0-9]*                                (대문자 고정)
경계.   키워드 앞 \b, 이슈키 뒤 (?![A-Za-z0-9-])
대상.   PR 제목 + 본문 (GITHUB: pull_request.title/body, GITLAB: object_attributes.title/description)
중복.   추출 후 distinct
```

- **대소문자 비대칭 주의**. 키워드만 대소문자 무시. 전체에 `(?i)`를 걸면 `proj-1` 같은 소문자 키까지 매칭돼 `IssueKey.REGEX`(대문자 고정) 계약과 어긋난다 → 스코프 한정 플래그(`(?-i:...)`) 또는 명시적 문자클래스 사용
- **앵커 주의**. `find()`는 미앵커라 텍스트 유실 사고 이력 있음(learnings — flexmark 인라인 확장). 경계 lookahead 필수
- `body`가 `null`인 PR 허용(제목만 스캔)

## 측정 가능한 완료 기준

- [ ] S1~S8 전 시나리오 통합 테스트 통과 (Testcontainers + 실서블릿)
- [ ] EC1~EC16 전 케이스 테스트 존재
- [ ] 서명 검증 — 유효/무효/헤더누락/secret미설정 4분기 + **상수시간 비교 사용** 확인
- [ ] 256KB 초과 → 413이 **서명 검증 이전** 발생 (실서블릿 검증. MockMvc는 서블릿 상한 우회 → 가짜 그린)
- [ ] `TriggerType.entries.size shouldBe 6` / `ActionType.entries.size shouldBe 5` 갱신
- [ ] `SchemaMigrationTest` CHECK 제약 6종/5종 갱신
- [ ] shared-kernel `IssueMutationPortContractTest` — `setFixVersions` default 없음 검증
- [ ] `StubIssueMutationPort` 갱신 (consumer-owns-stub)
- [ ] **`:modules:app:test` 통과** (9 BC prod 조립 — 신규 포트 NoSuchBean 회귀 차단)
- [ ] SecurityConfig 3종 등록 후 **prod 프로파일에서 인바운드 경로가 401이 아님** 검증
- [ ] 프론트 `pnpm typecheck` + 기존 automation 단위 테스트 회귀 0 (C4)
- [ ] ktlint + detekt 0
- [ ] 전수 동기화 — SDD 8.8 정정 · product doc D2/D3 문구 정정 · fr-index · README · dashboard

## 후속 과제 (본 PR 범위 밖)

- **D6/D7** — Webhook URL 생성 UI + E2E (후속 PR)
- `automation_git_deliveries` / `slack_delivery_log` 보존 정책(정리 배치)
- permitAll **확장 포인트 부재**(plan §F2) — BC별 경로 등록 인터페이스 도입 검토. 이 구조가 부채 재발 원인
- `AutomationWebhookController`의 SHA-256 해싱 중복 구현(`:224` vs `AutomationRuleService.kt:758`) 통합
- automation main에 non-prod 스텁 부재 → 조립 앱 prod 전용(plan §F2 부수 발견)

## Brainstorming Check

(← Phase B에서 채움)
