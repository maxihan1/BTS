# FR-NT-01 — 이벤트별 알림 정책

> slug: fr-nt-01-notification
> type: backend
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-11

## Brief

FR-NT-01 — 이벤트별 알림 정책 (notification BC).
사용자 원문. "FR-NT-01 진행하자"
classify. type=backend, agent=backend-engineer, primary_bc=notification

product 명세. `docs/plan/product/notification-dashboard.md` §2.1
fr-index. `| FR-NT-01 | 이벤트별 알림 정책 | 필수 | notification-dashboard | §2.1 |`

## 도메인 정리

- **BC**: notification (신규 모듈 — `backend/modules/notification/` 부트스트랩 필요. BTS 첫 notification BC 작업)
- **영향 엔티티 (신규)**: NotificationPolicy (이벤트별 수신자×채널 규칙)
- **새 용어 (glossary 후보, Maxi 승인 대기)**:
  - 알림 정책 (NotificationPolicy) — "어떤 event_type이 발생하면 어떤 수신자 역할에게 어떤 채널로 알린다"는 규칙 데이터
  - 수신자 역할 (RecipientRole) — Reporter / Assignee / Watcher / ComponentLead / ProjectAdmin / 프로젝트 멤버 등. 실제 사용자 해석은 FR-NT-03
  - 알림 채널 (Channel) — 이메일 / 인앱(Inbox) / Slack / Teams / Webhook (SDD §9.1.1)
  - 정책 평가 엔진 (PolicyEvaluation) — 이벤트 → 적용 정책(수신자역할×채널) 목록을 순수 반환. 실제 전달 X
- **범위 경계 (Maxi 확정)**: 정책 도메인 + CRUD API + 평가 엔진까지. pgmq consumer/fanout/채널 전달/Inbox 기록은 FR-NT-02·03·04·UX-03로 분리
- **event_type 카탈로그 (Maxi 확정)**: SDD §9.1.2 전체 (미래 이벤트 포함). 발행원 없는 이벤트도 정책 정의 가능. 정확한 enum 목록은 스펙에서 확정
- **선행 조건 (Maxi 확정)**: STOMP WebSocket PoC(§1)는 차단 조건 아님 (정책은 실시간 전송 무관). identity-access 세션 ✅ / issue-tracking 이벤트 발행 ✅
- **기존 이벤트 인프라 (소비 대상)**:
  - `q_issue_events` ← issue.created/updated/transitioned/soft_deleted/mentioned (`IssueEventPublisher`, Jackson 다형성 `{"type":...}`)
  - `q_workflow_scheme_events` ← WorkflowSchemeAssigned/Updated/Deleted
  - 소비 방식: `SELECT * FROM pgmq.read(queue, vt, qty)` → JSON `type` 필드로 라우팅
- **기존 결정 충돌**: 없음 (notification BC 첫 ADR)
- **관련 ADR**: [docs/decisions/2026-06-11-notification-policy-bc-bootstrap.md](../decisions/2026-06-11-notification-policy-bc-bootstrap.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-nt-01-notification.md](../specs/2026-06-11-fr-nt-01-notification.md)

핵심 3줄 요약.
- notification 새 BC 모듈 부트스트랩 + `notification_policies(project_id?, event_type, recipient_role, channel, enabled)` 테이블 + SDD §9.1.2 매트릭스 시드(IN_APP)
- 3 enum(EventType 9종/RecipientRole/Channel 5종) + 정책 CRUD API 4종 + 카탈로그 API + 평가 엔진(전역/프로젝트 override = event_type 단위 replace)
- 모든 CRUD 권한 = SYSTEM_ADMIN(`SystemPermissionResolver`). 실제 전달/소비는 FR-NT-02+로 분리. 백엔드 D1~D5 (UI/E2E 후속 분리 권장)

## Brainstorming Check

✅ 통과 (self-review 1회 iteration). 발견 gap 1건 — 프로젝트별 정책 권한 배선이 cross-BC(identity-access prod adapter 추가)가 되어 "한 PR=한 BC" 충돌. Maxi 결정으로 해소(모든 CRUD = SYSTEM_ADMIN, 단일 BC). 데이터 모델 프로젝트별 override는 유지.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
