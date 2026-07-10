<!-- FR-AT-01 자동화 트리거 명세 — automation BC 착수(D1~D5 백엔드 코어): 5종 트리거 감지 + 룰 CRUD + 실행 큐 이음선 -->

# FR-AT-01 — 자동화 트리거 (스펙)

> BC: automation (신규 9번째 모듈) · 범위: **백엔드 코어 D1~D5** (UI D6 / E2E D7은 후속 PR)
> 관련 ADR: [docs/decisions/2026-07-10-fr-at-01-automation-triggers.md](../decisions/2026-07-10-fr-at-01-automation-triggers.md)
> SDD: 08장(자동화 엔진) · 12장(권한)

## 요약

TCA(Trigger-Condition-Action) 자동화 엔진의 **트리거 절반**을 구현한다. 자동화 룰(`AutomationRule`)을
CRUD로 정의하고, 5종 트리거(ISSUE_CREATED / ISSUE_UPDATED / ISSUE_COMMENTED / SCHEDULED / WEBHOOK)를
감지해 매칭된 룰을 `q_automation_execution` 큐로 넘긴다(액션 실행은 FR-AT-02). 조건(FR-AT-03)·액션
(FR-AT-02)은 이 FR 범위 밖.

## 사용자 시나리오 (Given-When-Then)

### S1. 룰 생성 (권한 있음)
- **Given** PROJECT_ADMIN(=MANAGE_AUTOMATION 보유) 사용자가 프로젝트 PROJ에서
- **When** `POST /api/v1/projects/PROJ/automation/rules`로 `{name, triggerType: ISSUE_CREATED, triggerConfig: {}}` 생성
- **Then** 201 + 생성된 룰(id, enabled=true, version=0) 반환

### S2. 권한 없음 → 거부
- **Given** MANAGE_AUTOMATION 미보유 사용자가
- **When** 룰 생성/수정/삭제/조회 시도
- **Then** 403 (fail-closed) — 룰은 만들어지지 않음

### S3. ISSUE_CREATED 트리거 발화
- **Given** PROJ에 enabled=true, ISSUE_CREATED 룰 R1이 존재
- **When** PROJ에 이슈가 생성되어 `issue.created` 이벤트가 `q_automation_events`에 도달
- **Then** AutomationEventWorker가 R1 매칭 → `q_automation_execution`에 `{ruleId: R1, triggerType: ISSUE_CREATED, triggerEvent: {...}}` 적재

### S4. ISSUE_UPDATED 필드 필터
- **Given** enabled 룰 R2 (ISSUE_UPDATED, triggerConfig `{fields: ["priority"]}`)
- **When** 이슈의 `summary`만 변경되어 `issue.updated {fields:["summary"]}` 도달
- **Then** 필드 교집합 없음 → R2 미발화(enqueue 안 함)
- **When** 이슈의 `priority`가 변경되어 `issue.updated {fields:["priority"]}` 도달
- **Then** 교집합 있음 → R2 발화(enqueue)

### S5. ISSUE_COMMENTED 트리거 (신규 이벤트)
- **Given** enabled 룰 R3 (ISSUE_COMMENTED)
- **When** 이슈에 댓글이 생성되어 신규 `issue.commented` 이벤트가 `q_automation_events`에 도달
- **Then** R3 매칭 → enqueue

### S6. SCHEDULED 트리거 (cron)
- **Given** enabled 룰 R4 (SCHEDULED, triggerConfig `{cron: "0 9 * * *"}`, nextFireAt=오늘 09:00)
- **When** AutomationScheduleWorker의 @Scheduled 폴링 시각이 nextFireAt을 지남
- **Then** R4 발화(enqueue) + nextFireAt을 다음 cron 시각으로 갱신(중복 발화 방지)

### S7. WEBHOOK 트리거 (인바운드)
- **Given** enabled 룰 R5 (WEBHOOK) — 생성 시 발급된 불투명 토큰 T (원문 1회 노출, DB엔 해시만)
- **When** 외부 시스템이 `POST /api/v1/automation/webhooks/{T}`로 임의 JSON payload 전송
- **Then** 토큰 해시 조회 → R5 발화(enqueue, triggerEvent=payload) + 즉시 202 응답(< 200ms)
- **When** 존재하지 않는/취소된 토큰
- **Then** 404 (룰 존재 숨김)

### S8. disabled 룰은 발화 안 함
- **Given** enabled=false 룰
- **When** 매칭 이벤트 도달
- **Then** enqueue 안 함

## 기능 요구사항 (FR)

- **FR1** 자동화 룰 CRUD — 생성/목록/단건/수정(name·enabled·triggerConfig, OCC)/삭제(soft delete). 모두 MANAGE_AUTOMATION 가드.
- **FR2** 트리거 타입 5종 enum — ISSUE_CREATED / ISSUE_UPDATED / ISSUE_COMMENTED / SCHEDULED / WEBHOOK. triggerConfig는 타입별 **형식만** 검증(대상 존재/권한 미검증 — favorites·가젯 선례).
- **FR3** 이슈 이벤트 트리거 감지 — `AutomationEventWorker`가 `q_automation_events`(신규 전용 큐) 폴링 → 이벤트 타입 → 트리거 타입 매핑 → 해당 프로젝트의 enabled 룰 매칭 → `q_automation_execution` enqueue.
- **FR4** ISSUE_UPDATED 필드 필터 — triggerConfig `{fields:[...]}`이 있으면 변경 필드와 교집합이 있을 때만 발화. 비어있으면 모든 update 발화.
- **FR5** SCHEDULED 트리거 — `AutomationScheduleWorker`(@Scheduled) cron 평가(Spring `CronExpression`), nextFireAt 추적으로 중복/누락 없이 발화.
- **FR6** WEBHOOK 트리거 — 불투명 토큰 기반 인바운드 엔드포인트(permitAll, 토큰 인증). 토큰 해시만 저장, 원문 생성 시 1회 노출.
- **FR7** issue-tracking 확장 — ① `IssueEventPublisher`가 issue.created/updated/commented를 `q_automation_events`로 fan-out ② `IssueCommented` 도메인 이벤트 신설 + 댓글 생성 시 발행.
- **FR8** MANAGE_AUTOMATION 권한 결선 — 프로젝트 행정 권한(PROJECT_ADMIN grant), identity-access 시드 + shared-kernel `AutomationPermissionResolver` 포트 + identity-access 구현(fail-closed).

## 비기능 요구사항 (NFR)

- **NFR1** 트리거 감지 → enqueue 지연 p95 < 5s (product §NFR).
- **NFR2** Webhook 응답 < 200ms (동기 경로는 토큰 조회 + enqueue만, 액션 실행은 비동기).
- **NFR3** 권한 위반 액션 차단율 100% (fail-closed — resolver 미주입 시 부팅 실패, non-null 주입).
- **NFR4** pgmq consumer 메시지 생명주기 준수 — read(vt) → 처리 → archive/delete, 실패 시 재시도(vt 만료 재노출). 무한 재시도 방지(최대 시도 후 dead-letter/archive).
- **NFR5** at-least-once 소비 — 중복 발화 허용(FR-AT-02 액션이 멱등 책임). SCHEDULED만 nextFireAt로 중복 억제.

## API 인터페이스 (REST)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/api/v1/projects/{projectKey}/automation/rules` | MANAGE_AUTOMATION | 룰 생성. WEBHOOK이면 응답에 webhookToken 1회 포함 |
| GET | `/api/v1/projects/{projectKey}/automation/rules` | MANAGE_AUTOMATION | 룰 목록(프로젝트 스코프) |
| GET | `/api/v1/projects/{projectKey}/automation/rules/{id}` | MANAGE_AUTOMATION | 단건. 토큰 원문 미노출(해시만 존재) |
| PATCH | `/api/v1/projects/{projectKey}/automation/rules/{id}` | MANAGE_AUTOMATION | name·enabled·triggerConfig 수정(version OCC, 불일치 409) |
| DELETE | `/api/v1/projects/{projectKey}/automation/rules/{id}` | MANAGE_AUTOMATION | soft delete |
| POST | `/api/v1/automation/webhooks/{token}` | permitAll(토큰 인증) | 인바운드 웹훅 트리거. 202. 미존재 토큰 404 |

## 데이터 모델 변경

### automation 모듈 (신규)
- `automation_rules` — id(UUID PK), project_key, name, enabled(bool), trigger_type(enum 5), trigger_config(JSONB), webhook_token_hash(nullable, WEBHOOK만·UNIQUE), next_fire_at(nullable, SCHEDULED만), created_by(UUID), created_at/updated_at(timestamptz), version(bigint OCC), deleted_at(nullable soft delete).
- pgmq 큐 2종 — `q_automation_events`(fan-out 수신), `q_automation_execution`(FR-AT-02 이음선).
- Flyway prefix — automation 모듈 마이그레이션 번호 정책 확인(ADR `bc-migration-prefix-policy`), init_codegen 미러 여부는 db-engineer 판단(JdbcTemplate이면 codegen 불필요).

### issue-tracking (확장)
- `IssueDomainEvent`에 `IssueCommented(issueKey, projectKey, commentId, actorId, occurredAt)` 서브타입 추가.
- `IssueEventPublisher` — automation fan-out 로직(created/updated/commented → `q_automation_events`) + exhaustive `when` 전수 갱신.
- 댓글 생성 서비스 — 같은 트랜잭션에서 `publish(IssueCommented(...))` (Propagation.MANDATORY).

### identity-access (확장)
- 신규 마이그레이션 — `INSERT INTO role_permissions (scheme_id, role, permission_code) VALUES ('00000000-0000-0000-0000-000000000001','PROJECT_ADMIN','MANAGE_AUTOMATION')` (V013 MANAGE_WORKFLOW 동형). `PermissionSchemaMigrationTest` 카운트 +1 동반 갱신.
- `IdentityAccessAutomationPermissionResolver` — 멤버십 + role_permissions MANAGE_AUTOMATION 매트릭스 판정(WorkflowScheme/Issue resolver 동형).

### shared-kernel (확장)
- `AutomationPermissionResolver` 포트(prod 구현=identity-access, non-prod stub) — automation BC가 cross-BC 권한 판정 창구로 사용(role 직접조회 금지).

## 엣지 케이스

- **EC1** 존재하지 않는 프로젝트로 룰 생성 → 404/422 (프로젝트 검증 방식은 기존 컨트롤러 관례 따름).
- **EC2** triggerConfig 형식 오류(예: SCHEDULED인데 cron 없음/파싱 불가) → 400.
- **EC3** WEBHOOK 토큰 충돌(생성 시 해시 UNIQUE 위반) → 재생성 또는 409(극히 드묾, 랜덤 토큰).
- **EC4** `q_automation_events` 역직렬화 실패(미지원 이벤트 타입) → 조용히 skip + archive(under-processing 방지 로그). automation 미관심 이벤트(soft_deleted 등)는 애초에 fan-out 안 함.
- **EC5** SCHEDULED 룰의 cron이 과거만 가리킴/한 폴링에 여러 주기 경과 → nextFireAt 재계산으로 1회만 발화(누적 발화 금지).
- **EC6** 워커 재시작 중 메시지 in-flight → vt 만료 재노출 → 재처리(at-least-once). 액션 멱등은 FR-AT-02.
- **EC7** disabled 룰/soft-deleted 룰은 매칭 대상 제외(모든 감지 경로 공통).
- **EC8** IssueCommented 추가로 다른 소비자(notification 등)의 exhaustive when 컴파일 실패 → 전 모듈 grep 후 `IssueCommented -> false/무시` 갱신(over-processing 무해).

## 제약 조건

- **BC 격리 예외** 명시 — issue-tracking(fan-out+IssueCommented)·identity-access(seed+resolver)·shared-kernel(port) touch. producer→consumer fan-out은 q_webhook_events 선례, 권한은 resolver 창구 표준.
- 신규 외부 의존성 없음 — Spring `CronExpression`(spring-context 내장), pgmq(기존), JSONB(기존).
- 무한루프 방지(체인 깊이 10)는 액션이 이벤트를 유발하는 FR-AT-02 시점 도입(이 FR은 트리거만이라 루프 없음).
- 완제품 품질 — 절대 규칙 19개 준수, TDD, 에러 처리, 권한 fail-closed.

## 측정 가능한 완료 기준

- [ ] automation 모듈 `settings.gradle` 등록 + test-boot 조립(Testcontainers) 부팅 성공
- [ ] 룰 CRUD 5종 + MANAGE_AUTOMATION 가드(403 negative) Testcontainers HTTP end-to-end 통과
- [ ] 5종 트리거 각각 매칭 → `q_automation_execution` 메시지 도달 단언(Testcontainers)
- [ ] ISSUE_UPDATED 필드 필터 교집합 로직 단위 테스트
- [ ] SCHEDULED nextFireAt 중복 억제 단위 테스트
- [ ] WEBHOOK 토큰 해시 조회 + 미존재 404 + 응답 지연 검증
- [ ] issue-tracking IssueCommented 발행 + fan-out 통합 테스트, 전 모듈 exhaustive when 그린
- [ ] identity-access MANAGE_AUTOMATION 시드 + PermissionSchemaMigrationTest 카운트 갱신 그린
- [ ] 전 모듈 test + ktlintCheck + detekt 그린, 회귀 0
- [ ] 문서 전수 동기화(fr-index D단계·product automation.md·SDD 카운트) + verify-master-plan.sh 통과

## Brainstorming Check ✅ (자기검토 — gap 6건 발견·자체 해소, Maxi 결정 불필요)

- **G1. IssueUpdated에 projectKey 부재** — `IssueUpdated(issueKey, fields, occurredAt)`엔 projectKey가 없다.
  워커는 `IssueKey`(`PROJECT-123`)에서 프로젝트 키를 파싱해 룰을 매칭한다(IssueCreated/Commented는 projectKey 보유).
- **G2. 웹훅 payload 크기 상한** — 인바운드 JSON body에 크기 상한(예: 256KB) 적용, 초과 시 413.
  서블릿 기본 상한이 앱 정책을 무력화하지 않도록 명시([[multipart-default-limit-app-policy-false-green]]).
- **G3. SCHEDULED nextFireAt 초기화 + cron TZ** — 룰 생성/활성화 시 cron으로 nextFireAt 즉시 계산.
  cron 평가 타임존 = **UTC(v1)**, 프로젝트-로컬 TZ는 후속. (BTS 전역 Instant/UTC 관례 준수.)
- **G4. q_automation_execution 소비자 부재** — 이 FR엔 소비자가 없다(FR-AT-02가 추가). 의도된 이음선 dead-end.
  test-assembled only라 prod 무한 적재 위험 없음([[no-cross-bc-deployment-assembly]]). 테스트는 도달 단언 후 정리.
- **G5. disabled/soft-deleted WEBHOOK 룰** — 유효 토큰이라도 룰이 비활성/삭제면 **404**(존재 숨김, 발화 안 함). 감지 경로 공통.
- **G6. enqueue 메시지의 실행 액터 컨텍스트** — SCHEDULED/WEBHOOK은 사용자 액터 없음. 메시지는 `ruleId` 보유 →
  FR-AT-02가 `created_by`(또는 시스템)로 실행 주체 해석. 이 FR은 컨텍스트 전달만.
