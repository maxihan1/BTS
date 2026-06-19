# FR-PL-02 지연/임박 자동 알림

> slug: fr-pl-02-overdue-notify
> type: feature
> agent: backend-engineer
> 생성: 2026-06-19

## Brief

**원문**. "fr-pl-02 진행하자"

**FR-PL-02 (agile-planning product §6.2)** — 지연/임박 자동 알림. 우선순위 높음. 선행 §6.1(FR-PL-01 일정 필드, 완료) + notification BC.

**핵심 흐름**. 이슈 마감일(due_date/target_date)을 매일 1회 스캔 → 지연(overdue)/임박(D-day 접근) 이슈를 찾아 pgmq 이벤트 발행 → 기존 notification 인프라가 토스트로 전달.

**product D단계**.
- D1. 도메인 (backend-engineer)
- D2. 명세 — D-day 트리거 (스케줄러) (backend-engineer)
- D3. 데이터 모델 — (활용, 신규 스키마 없음) (db-engineer)
- D4. 백엔드 — Spring @Scheduled 일 1회 + pgmq 이벤트 (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 알림 토스트 (frontend-engineer)
- D7. E2E (qa-engineer)

**classify 보정 메모**. primary_bc=notification→issue-tracking (스케줄러는 일정필드 보유 BC 소유, BC 격리상 다른 BC 테이블 직접 조회 불가). type=backend→feature. cross-BC(스케줄러 위치 / notification 소비 / PR 분할 여부)는 도메인 단계에서 결정.

## 도메인 정리

- **BC**: issue-tracking (스케줄러 = 발행 측, Maxi 확정). notification BC는 **변경 0** — 이미 완비됨.
- **핵심 발견 (코드 실측)**. notification 소비 측이 FR-NT-01 구축 시 forward-looking으로 미리 만들어져 있음.
  - `NotificationEventType.kt:34,37` — `ISSUE_DUE_SOON("issue.due_soon", false)`, `ISSUE_OVERDUE("issue.overdue", false)` 이미 존재
  - `NotificationWorker.buildTitleBody()` L289-290 — "마감이 임박했습니다" / "마감이 초과되었습니다" 이미 처리
  - `V401__seed_default_policies.sql` L30/33-34 — due_soon→ASSIGNEE, overdue→ASSIGNEE+REPORTER (IN_APP, 전역) 이미 시드됨
  - 프론트 `useNotificationStream.ts` — 이벤트 타입 무관 제네릭 토스트(`toast(title, {description: body})`). **D6 신규 코드 0**
- **신규 구현 = issue-tracking 발행 측만**.
  1. `IssueDomainEvent` sealed interface에 `IssueDueSoon`/`IssueOverdue` 추가 (`@JsonTypeName("issue.due_soon")`/`"issue.overdue"` + `@JsonSubTypes` 등록). 페이로드 최소 = `issueKey`, `projectKey`, `occurredAt` (수신자는 notification의 resolver가 포트로 조회, actorId 부재 OK)
  2. `IssueRepository` — 열림(`resolution_id IS NULL AND deleted_at IS NULL`) + due_date 기준 범위 조회 메서드
  3. 신규 `@Scheduled` 워커 (BulkOperationCleanupWorker 패턴: cron + Clock 주입 + `@Transactional`) — 매일 스캔 → `IssueEventPublisher.publish()` (MANDATORY tx outbox)
  4. 백엔드 테스트 (단위 + Testcontainers 통합)
- **종료 이슈 제외**. `Issue.resolutionId != null` = 해결됨(종료). BC 격리상 워크플로우 상태 직접조회 불가 → `resolution_id IS NULL`이 cross-BC-safe "열림" 신호 (glossary "DONE 전이 시 resolution 필수").
- **재알림 멱등성**. `dedupKey = hash(eventType, issueKey, occurredAt, recipientUserId, channel)` (NotificationWorker L247). 스케줄러가 `occurredAt`을 **날짜 단위 정규화**하면 "하루 1회 재알림" (같은 날 재전달은 dedup), **고정**하면 "1회만". → 스펙 결정 사항.
- **스펙에서 정할 핵심 product 결정**. (1) 임박 기준 LEAD_DAYS (며칠 전부터 due_soon) (2) 재알림 정책 (매일 vs 1회) (3) due_date만 vs target_date 포함 (4) cron 시각.
- **새 용어**. "지연 알림(overdue)", "임박 알림(due_soon)" — glossary 추가 후보 (Maxi 승인 대기).
- **기존 결정 충돌**: 없음. 관련 ADR(notification BC bootstrap/in-app channel/webhook) 모두 파이프라인 구축 건, FR-PL-02는 신규 생산자.
- **관련 ADR**: 없음 (신규 아키텍처 결정 없음 — 기존 파이프라인 재사용). 재알림 정책은 spec에 인라인.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
