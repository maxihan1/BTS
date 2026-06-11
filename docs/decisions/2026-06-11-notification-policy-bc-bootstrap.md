# ADR: notification BC 부트스트랩 + 이벤트별 알림 정책 경계 (FR-NT-01)

> 날짜: 2026-06-11
> 상태: 채택
> 범위: notification BC (신규 모듈), FR-NT-01 백엔드 (D1~D5)
> 관련 SDD: §9.1.2 (이벤트별 알림 정책), §9.1.3 (발송 흐름)
> 관련 PR: #118

## 맥락

notification BC는 그린필드다. `backend/modules/`에는 identity-access · issue-tracking · project-workflow · shared-kernel 4개 모듈만 있고 notification 모듈 자체가 없다. FR-NT-01이 notification BC의 첫 작업이다.

이벤트 발행 측은 이미 가동 중이다.

- issue-tracking → `q_issue_events` 큐에 5종 발행. `issue.created` / `issue.updated` / `issue.transitioned` / `issue.soft_deleted` / `issue.mentioned` (Jackson 다형성 JSON, `pgmq.send`, `@Transactional(MANDATORY)`).
- project-workflow → `q_workflow_scheme_events` 큐에 3종 발행. `WorkflowSchemeAssigned` / `WorkflowSchemeUpdated` / `WorkflowSchemeDeleted`.
- issue-tracking bulk → `q_bulk_operation_events`.

그러나 이 이벤트들을 **소비할 notification BC가 없다.** FR-MN-01(#114)도 `IssueMentioned` 발행까지만 했고 실제 알림 생성/전달은 미구현이다.

FR-NT-01의 product 명세(§2.1)는 정책 도메인(D1) · 매트릭스 명세(D2) · `notification_policies` 데이터 모델(D3) · 정책 CRUD + 평가 엔진(D4) · 테스트(D5) · 관리자 UI(D6) · E2E(D7)다.

## 결정 1 — notification을 새 BC 모듈로 부트스트랩

`backend/modules/notification/`을 project-workflow 모듈을 템플릿으로 신규 생성한다. settings.gradle.kts 등록, build.gradle.kts(jOOQ codegen + Flyway + detekt/ktlint), `db/migration/notification/` 마이그레이션 디렉토리, `com.bts.notification.*` 패키지 레이아웃, ArchUnit BC 격리 룰을 포함한다.

**근거**. _index.md / product 명세가 notification을 독립 BC로 정의한다. 다른 BC 호출은 pgmq 이벤트 수신 + shared-kernel 포트(UserLookupPort 등) 경유만 허용(BC 격리). project-workflow와 동일하게 라이브러리 모듈로 시작(독립 SpringBootApplication 진입점은 실제 consumer가 붙는 FR-NT-02 시점에 판단).

## 결정 2 — FR-NT-01 범위 = 정책 도메인 + CRUD + 평가 엔진 (전달은 분리)

FR-NT-01은 **정책 데이터 + 정책 평가 엔진**까지다. 평가 엔진은 "어떤 event_type이 들어오면 (수신자 역할 × 채널)의 어떤 조합이 적용되는가"를 **순수하게 반환**한다. 실제 큐 소비 · 수신자 fanout · 채널 발송 · Inbox 기록은 본 FR 범위 밖이다.

| 책임 | FR | 본 작업 포함? |
|---|---|---|
| 정책 데이터(event_type × recipient_role × channel) + CRUD | FR-NT-01 | ✅ |
| 정책 평가 엔진(이벤트 → 적용 정책 목록) | FR-NT-01 | ✅ |
| pgmq consumer + 실제 채널 fanout/전달 | FR-NT-02 | ❌ |
| 수신자 역할 → 실제 사용자 목록 해석(RecipientResolver) | FR-NT-03 | ❌ |
| 사용자별 구독 override | FR-NT-04 | ❌ |
| Inbox 기록/조회 | FR-UX-03 | ❌ |

**근거**. SDD §9.1.3의 NotificationWorker 발송 흐름(읽기→fanout→렌더링→발송→기록→푸시) 전체는 채널/실시간이 전제다. 정책은 그 흐름의 **입력 규칙**일 뿐이며 WebSocket·이메일·Slack 인프라 없이 독립적으로 도메인·테스트가 완결된다. BC의 도메인 골격을 먼저 세우고 전달 인프라를 후속 FR로 쌓는다.

## 결정 3 — event_type 카탈로그 = SDD §9.1.2 전체 (미래 이벤트 포함)

정책이 다룰 수 있는 event_type 카탈로그를 SDD §9.1.2 기준으로 정의한다. 발행원이 아직 없는 이벤트에 대한 정책도 미리 정의할 수 있게 한다(정책은 설정 데이터이므로).

SDD §9.1.2 카탈로그 ↔ 실재 발행 이벤트 매핑.

| SDD §9.1.2 이벤트 | 실재 발행 상태 |
|---|---|
| `issue.created` | ✅ 발행 중 |
| `issue.assigned` | ⚠️ 별도 이벤트 없음 — `issue.updated`(fields에 assignee 포함)로 발행 |
| `issue.transitioned` | ✅ 발행 중 |
| `issue.commented` | ⚠️ 댓글 이벤트 미발행 — 현재 `issue.mentioned`(멘션)만 |
| `issue.due_soon` / `issue.overdue` | ❌ 발행원 없음(due 스케줄러 미구현) |
| `sprint.started` / `sprint.ended` | ❌ 발행원 없음(agile-planning BC 부재) |
| `automation.failed` | ❌ 발행원 없음(automation BC 부재) |
| (실재 추가) `issue.updated` · `issue.soft_deleted` · `issue.mentioned` · workflow scheme 3종 | ✅ 발행 중이나 SDD §9.1.2 미수록 |

**근거**. 정책은 데이터/설정이라 관리자가 미래 이벤트 정책을 선반영해도 무해하다. 평가 엔진의 **검증**은 실재 발행 이벤트로 수행한다. 정확한 enum 최종 목록(SDD 8종 + 실재 발행분 병합 여부 포함)은 스펙 단계에서 확정한다.

**리스크**. 발행원 없는 event_type 정책은 "동작하지 않는 설정"으로 존재 → 관리자 혼란 가능. 스펙에서 "발행 가능 여부" 메타데이터를 카탈로그에 부여할지 검토한다.

## 결정 4 — STOMP WebSocket PoC(§1)는 FR-NT-01 차단 조건 아님

product 명세 §0 진입 조건은 §1 STOMP WebSocket 재연결 PoC 통과를 요구하나, 이는 실시간 채널 전달(FR-NT-02)을 위한 것이다. FR-NT-01(정책 데이터 + 평가 엔진)은 WebSocket과 무관하므로 PoC 없이 진행한다. STOMP PoC는 FR-NT-02 착수 시 수행한다.

## 결과 / 영향

- 후속 FR-NT-02가 평가 엔진 출력을 입력으로 받아 실제 fanout/채널 전달을 구현한다.
- recipient_role → 사용자 목록 해석(RecipientResolver)은 FR-NT-03.
- notification 모듈은 issue-tracking/project-workflow 내부 패키지를 직접 import하지 않는다(ArchUnit 격리). 이벤트는 pgmq JSON, 사용자 조회는 shared-kernel UserLookupPort 경유.
- 정확한 테이블 스키마(`notification_policies` 컬럼·제약·기본 정책 시드)와 event_type enum 최종 목록은 스펙/계획에서 확정한다.
