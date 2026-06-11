# FR-NT-01 — 이벤트별 알림 정책 — 스펙

> BC: notification (신규 모듈)
> 범위: 정책 도메인 + CRUD + 평가 엔진 (D1~D5). UI(D6)/E2E(D7)는 후속 분리 검토.
> 관련: ADR [2026-06-11-notification-policy-bc-bootstrap](../decisions/2026-06-11-notification-policy-bc-bootstrap.md), SDD §9.1.1~9.1.3

## 0. 한 줄 정의

"어떤 이벤트(event_type)가 발생하면, 어떤 수신자 역할(recipient_role)에게, 어떤 채널(channel)로 알릴지"를 정의하는 **규칙 데이터 + 평가 엔진**. 실제 전달은 본 FR 범위 밖(FR-NT-02+).

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 전역 기본 정책 조회 (시스템 관리자)
- **Given** 시스템 관리자가 로그인했고, SDD §9.1.2 매트릭스가 시드됨
- **When** `GET /api/v1/notification-policies` 호출 (projectKey 없음)
- **Then** 전역 기본 정책 목록(event_type × recipient_role × channel + enabled)이 반환된다

### S2. 전역 정책 채널 추가 (시스템 관리자)
- **Given** `issue.created → REPORTER → IN_APP` 전역 정책이 존재
- **When** 시스템 관리자가 `issue.created → REPORTER → EMAIL` 정책을 POST
- **Then** 새 정책이 추가되고, 이후 평가 시 REPORTER는 IN_APP·EMAIL 둘 다 적용된다

### S3. 정책 비활성화 (토글)
- **Given** `issue.transitioned → WATCHER → IN_APP` 전역 정책이 enabled=true
- **When** PATCH로 enabled=false
- **Then** 평가 결과에서 해당 (역할, 채널) 조합이 제외된다 (행은 보존 — 감사/복원용)

### S4. 프로젝트별 override (replace 방식)
- **Given** `issue.created`에 대한 전역 기본 = {REPORTER→IN_APP, WATCHER→IN_APP, COMPONENT_LEAD→IN_APP}
- **When** 프로젝트 ATLAS가 `issue.created → ASSIGNEE → SLACK` 정책 1개를 정의
- **Then** ATLAS에서 `issue.created` 평가 시 전역 기본은 **완전히 무시**되고 ATLAS 정책({ASSIGNEE→SLACK})만 적용된다 (override = event_type 단위 replace, merge 아님)
- **And** 프로젝트가 정책을 정의하지 않은 다른 event_type은 전역 기본을 그대로 사용한다

### S5. 평가 엔진 (내부 — FR-NT-02가 호출)
- **Given** 정책 데이터가 시드/구성됨
- **When** `evaluate(eventType, projectKey?)` 호출
- **Then** 적용 가능한 `{recipientRole, channel}` 활성(enabled=true) 조합 목록이 반환된다 (전역/프로젝트 우선순위 병합 반영)

### S6. 발행원 없는 이벤트 정책 (정보 표시)
- **Given** `sprint.started`는 카탈로그에 있으나 발행원(agile-planning BC)이 아직 없음 (publishable=false)
- **When** 관리자가 `sprint.started` 정책을 정의
- **Then** 정책은 정상 저장된다. 카탈로그 조회 시 publishable=false로 표시되어 "아직 발행되지 않는 이벤트"임을 UI가 알 수 있다

## 2. 기능 요구사항 (FR)

- **FR-1**. NotificationPolicy 도메인 — (project_id?, event_type, recipient_role, channel, enabled) 불변식: 동일 (project_id, event_type, recipient_role, channel) 중복 금지.
- **FR-2**. event_type 카탈로그 = `NotificationEventType` enum (SDD §9.1.2, 9종). 각 항목에 `publishable` 메타.
- **FR-3**. recipient_role 카탈로그 = `RecipientRole` enum (SDD §9.1.2 등장 역할).
- **FR-4**. channel 카탈로그 = `Channel` enum (SDD §9.1.1 / product FR-NT-02, 5종).
- **FR-5**. 정책 CRUD API (생성/목록/토글/삭제) + 카탈로그 조회 API.
- **FR-6**. 평가 엔진 `NotificationPolicyEvaluator.evaluate(eventType, projectKey?)` — 전역/프로젝트 override 병합, enabled 필터.
- **FR-7**. 초기 시드 — SDD §9.1.2 매트릭스를 전역 기본 정책으로 마이그레이션 시드 (기본 채널 IN_APP).
- **FR-8**. 권한 — **모든 정책 CRUD = SYSTEM_ADMIN** (전역·프로젝트별 공통, Maxi 확정). `SystemPermissionResolver.isSystemAdmin()` 소비(shared-kernel 기존 포트, identity-access prod adapter FR-PM-08). 프로젝트 관리자 위임은 후속 FR. → notification 단일 BC 완결.

## 3. enum 카탈로그 (정확한 목록)

### 3.1 NotificationEventType (event_type, 9종 — SDD §9.1.2)

| enum | 문자열 | SDD 기본 수신자 | publishable (현재) |
|---|---|---|---|
| ISSUE_CREATED | `issue.created` | Reporter, Watcher, ComponentLead | ✅ (issue.created 발행) |
| ISSUE_ASSIGNED | `issue.assigned` | Assignee, PreviousAssignee | ❌ (issue.updated로 발행, 이름 미일치) |
| ISSUE_TRANSITIONED | `issue.transitioned` | Reporter, Assignee, Watcher | ✅ (issue.transitioned 발행) |
| ISSUE_COMMENTED | `issue.commented` | Reporter, Assignee, Watcher, Mentioned | ❌ (현재 issue.mentioned만) |
| ISSUE_DUE_SOON | `issue.due_soon` | Assignee | ❌ (due 스케줄러 미구현) |
| ISSUE_OVERDUE | `issue.overdue` | Assignee, Reporter | ❌ |
| SPRINT_STARTED | `sprint.started` | 프로젝트 멤버 | ❌ (agile-planning BC 부재) |
| SPRINT_ENDED | `sprint.ended` | 프로젝트 멤버 | ❌ |
| AUTOMATION_FAILED | `automation.failed` | RuleOwner, ProjectAdmin | ❌ (automation BC 부재) |

> `publishable`은 정보성 메타데이터다. 평가 엔진은 publishable과 무관하게 모든 event_type에 대해 정책을 평가한다. FR-NT-02가 실제 큐 이벤트를 평가 엔진에 전달할 때 event_type 문자열 매핑(예: `issue.updated`→`issue.assigned`/`issue.commented`)을 책임진다. (본 FR 범위 밖)

### 3.2 RecipientRole (recipient_role)

`REPORTER`, `ASSIGNEE`, `PREVIOUS_ASSIGNEE`, `WATCHER`, `COMPONENT_LEAD`, `MENTIONED`, `PROJECT_MEMBER`, `RULE_OWNER`, `PROJECT_ADMIN`

> 역할 → 실제 사용자 목록 해석(RecipientResolver)은 FR-NT-03. 본 FR은 enum 값 저장만.

### 3.3 Channel (channel — SDD §9.1.1 / product FR-NT-02)

`EMAIL`, `IN_APP`, `SLACK`, `TEAMS`, `WEBHOOK`

## 4. 데이터 모델 (notification_policies)

```sql
CREATE TABLE notification_policies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID,                       -- NULL = 전역 기본, 값 = 프로젝트별 override
    event_type      VARCHAR(64)  NOT NULL,      -- NotificationEventType
    recipient_role  VARCHAR(32)  NOT NULL,      -- RecipientRole
    channel         VARCHAR(16)  NOT NULL,      -- Channel
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      UUID,                        -- 감사 (시드는 NULL)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_policy
        UNIQUE NULLS NOT DISTINCT (project_id, event_type, recipient_role, channel)
);

CREATE INDEX idx_notification_policy_lookup
    ON notification_policies (event_type, project_id) WHERE enabled = TRUE;
```

- **NULLS NOT DISTINCT** 필수 — project_id NULL(전역)인 중복 정책을 UNIQUE가 잡도록 (memory: pg-null-distinct-on-conflict-idempotency).
- project_id는 **FK를 걸지 않는다** (BC 격리 — issue-tracking의 projects를 직접 참조하지 않음). 단순 UUID 참조.
- jOOQ codegen 미러 — `db/codegen/init_codegen.sql`에 동일 DDL 미러 (memory: jooq-init-codegen-mirror).

### 4.1 시드 (V001 또는 별도 V002)

SDD §9.1.2 매트릭스를 전역 기본(project_id=NULL) 정책으로 시드. 채널 IN_APP. 예:
`(NULL, 'issue.created', 'REPORTER', 'IN_APP', true)`, `(NULL, 'issue.created', 'WATCHER', 'IN_APP', true)`, … (전 이벤트×SDD 기본 수신자).

## 5. API 인터페이스 (REST)

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | `/api/v1/notification-policies/catalog` | enum 카탈로그(event_type+publishable / recipient_role / channel) | 인증 사용자 |
| GET | `/api/v1/notification-policies?projectKey={key}` | 정책 목록 (projectKey 없으면 전역) | SYSTEM_ADMIN |
| POST | `/api/v1/notification-policies` | 정책 생성 (body: projectKey?, eventType, recipientRole, channel, enabled?) | SYSTEM_ADMIN |
| PATCH | `/api/v1/notification-policies/{id}` | enabled 토글 (body: enabled) | SYSTEM_ADMIN |
| DELETE | `/api/v1/notification-policies/{id}` | 정책 삭제 | SYSTEM_ADMIN |

- 평가 엔진(`evaluate`)은 **REST 비노출** 내부 서비스. FR-NT-02 consumer가 호출.
- CSRF — 변경 API(POST/PATCH/DELETE)는 기존 BTS 패턴(쿠키 JWT + CSRF) 따름 (memory: frontend-api-convention-per-bc).
- 중복 생성(409) — UNIQUE 위반 시 409 Conflict.
- 잘못된 enum 값(400), 권한 없음(403), 미존재 id(404).

## 6. 평가 엔진 명세

```
evaluate(eventType: NotificationEventType, projectKey: String?): List<PolicyMatch>
  PolicyMatch = (recipientRole, channel)
```

알고리즘:
1. projectKey가 주어지고, 그 프로젝트가 `eventType`에 대해 정책을 **1개 이상** 가지면 → 그 프로젝트 정책(enabled=true)만 반환 (전역 무시, replace).
2. 아니면 → 전역 기본(project_id IS NULL, enabled=true) 반환.
3. enabled=false는 항상 제외.
4. 알 수 없는/정책 없는 eventType → 빈 목록.

## 7. 비기능 요구사항 (NFR)

- 정책 평가 조회 p95 < 50ms (event_type+project_id 인덱스). 캐시는 본 FR 범위 밖(FR-NT-02 consumer 성능 시 검토).
- ArchUnit — notification BC 격리(issue-tracking/project-workflow/identity-access 내부 패키지 직접 import 금지), jOOQ repository 화이트리스트, @Transactional+@Service 룰.
- 테스트 — 단위(도메인 불변식 + 평가 엔진 분기) + 통합(Testcontainers: CRUD + UNIQUE 멱등 + override replace + 권한 403).

## 8. 엣지 케이스

- **EC1**. 동일 (project, event, role, channel) 중복 생성 → 409 (UNIQUE NULLS NOT DISTINCT).
- **EC2**. 프로젝트 override는 event_type 단위 replace — 프로젝트가 그 event_type에 정책 0개면 전역 fallback.
- **EC3**. enabled=false 정책 — 행 보존, 평가 제외.
- **EC4**. publishable=false event_type 정책 정의 허용 (S6).
- **EC5**. 평가 시 알 수 없는 event_type / 정책 0개 → 빈 목록 (예외 아님).
- **EC6**. 잘못된 enum 문자열 입력 → 400.
- **EC7**. 비-SYSTEM_ADMIN이 정책 조회/변경(전역·프로젝트별 무관) 시도 → 403.
- **EC8**. 카탈로그 조회는 인증 사용자면 허용(민감 정보 아님), 정책 데이터는 SYSTEM_ADMIN만.

## 9. 제약 조건

- notification 새 모듈 부트스트랩 (project-workflow 템플릿: settings.gradle.kts 등록, build.gradle.kts jOOQ/Flyway, db/migration/notification, com.bts.notification 패키지, ArchUnit).
- 다른 BC 직접 import 금지. 사용자/프로젝트 권한 확인은 shared-kernel 포트 경유 (memory: crossbc-permission-resolver-not-role-lookup).
- V번호 — notification 모듈 자체 디렉토리에서 시작(다른 BC와 격리). 머지 직전 충돌 재확인 (memory: migration-vnumber-concurrent-branch-collision).
- 완제품 품질 (PoC 금지). TDD red→green→refactor.

## 10. 측정 가능한 완료 기준

- [ ] notification 모듈 부트스트랩 — `./gradlew :modules:notification:build` 통과, jOOQ codegen, ArchUnit 그린
- [ ] notification_policies 테이블 + V001 마이그레이션 + init_codegen 미러 + SDD §9.1.2 시드
- [ ] NotificationPolicy 도메인 + 3 enum(EventType/RecipientRole/Channel)
- [ ] 정책 CRUD API 4종 + 카탈로그 API — 통합 테스트(Testcontainers) 그린
- [ ] 평가 엔진 — 전역/프로젝트 override replace + enabled 필터, 단위 테스트 분기 커버
- [ ] 권한 가드 — 전역 SYSTEM_ADMIN / 프로젝트 관리 권한, 403 테스트
- [ ] 전체 백엔드 테스트 0 fail, ktlint/detekt 그린

## 11. 미해결 / 게이트1 확인 포인트

- **권한 배선** ✅ 해소 — 모든 정책 CRUD = SYSTEM_ADMIN. `SystemPermissionResolver.isSystemAdmin()`(shared-kernel 기존 + identity-access prod adapter) 소비. notification 단일 BC 완결, cross-BC 수정 없음.
- **시드 위치**. V001(테이블)과 동일 마이그레이션 vs 별도 V002. (plan에서 결정 — 권장: V001 테이블 + V002 시드 분리해 codegen init 단순화)
- **D6 UI / D7 E2E**. 본 PR 포함 vs 후속 분리 — PR 크기 보고 게이트1에서 Maxi 판단 (권장: 백엔드 D1~D5 먼저, UI/E2E 후속 PR. 멘션 PR #114 선례 동일).

## Brainstorming Check

✅ 통과 (self-review 1회). 발견 gap 1건 — "프로젝트별 정책 CRUD 권한 배선이 cross-BC가 되어 '한 PR=한 BC' 충돌". Maxi 결정으로 해소(모든 CRUD = SYSTEM_ADMIN, 단일 BC 완결). 데이터 모델의 프로젝트별 override는 유지. 나머지 항목(PATCH enabled-only, 프로젝트 삭제 orphan=범위밖, publishable=false 시드 포함)은 의도된 설계로 확인.
