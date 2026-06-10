# FR-MN-01 — 본문/댓글 @멘션 + 즉시 알림

> slug: fr-mn-01-mention-notify
> type: backend
> agent: backend-engineer
> BC: issue-tracking (fr-index 정본 §4.1.1) — classify의 primary_bc=notification은 미존재 모듈, 무시
> 생성: 2026-06-11

## Brief

사용자 원문: "fr-mn-01 진행해줘"

FR-MN-01 (fr-index §4.1.1, 필수): 이슈 본문/댓글에서 `@사용자`로 멘션하면 해당 사용자에게 즉시 알림.

classify 결과: type=backend, agent=backend-engineer, primary_bc=notification(미존재 → issue-tracking로 정정).
미해결 핵심 쟁점: "즉시 알림"의 전달 메커니즘 + BC 경계 (notification 모듈 부재 → issue-tracking 내 처리 vs 신규 모듈/이벤트). domain 단계에서 결정.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: issue-tracking (정본 fr-index §4.1.1). classify의 `primary_bc=notification`은 미존재 모듈 → 무시.
- **범위 결정 (Maxi 확정, 옵션 A)**: 본문 멘션 추출 + pgmq 이벤트 발행까지. 댓글 멘션·Inbox 도착(D7)·렌더링(D6)은 후속 FR로 deferred.
  - 근거: ① 댓글(comment) FR·엔티티 부재(본문만 가능) ② 알림 전달/Inbox(FR-UX-03)·notification 모듈 부재 ③ plan 정본 D3/D4가 이미 "notification 이벤트 발행만"으로 좁혀 명시 ④ learnings "병렬 FR 인프라 충돌" — notification 인프라 선점 시 FR-NT와 충돌.
- **선행 상태**: §2.1.4 FR-IS-04(본문 Markdown) = ✅완료(PR #43~47). §4.3.1 FR-WT-01(Watcher) = ❌미완료지만, 옵션 A 범위(멘션→이벤트 발행)는 멘션 대상을 직접 해석하므로 Watcher 불요.
- **영향 엔티티**: Issue(기존, `description` 필드 활용), `IssueMentioned`(신규 도메인 이벤트, `issue.mentioned`).
- **신규 용어**: "멘션 (Mention)" — 글(본문/댓글)에서 `@username`으로 다른 사용자를 호출하는 행위. glossary에 정식 등재 후보(현재 "그룹 멘션" 만 간접 언급). Maxi 승인 대기.
- **기존 이벤트 인프라 활용**: `IssueDomainEvent` sealed interface(`issue.created/updated/transitioned/soft_deleted`) + `IssueEventPublisher`(pgmq `q_issue_events`, `@Transactional(MANDATORY)` outbox) + 발행 호출처 `IssueApplicationService`(create L213 / update L285 등). → `IssueMentioned` 추가 후 본문 저장 시점에서 발행.
- **미해결(→ spec에서 확정)**: ① `@username` 파싱 규칙(정규식, 코드블록/이메일 회피) ② username→userId cross-BC 해석 메커니즘(identity-access resolver 존재 여부 확인 필요) ③ `IssueMentioned` payload 형태 ④ 추출 시점(생성 + description PATCH) ⑤ 자기 멘션/중복/미존재 username 처리.
- **관련 ADR**: 없음 (plan 정본 D3/D4 의도 준수, 신규 아키텍처 결정 없음).

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-mn-01-mention-notify.md](../specs/2026-06-11-fr-mn-01-mention-notify.md)

핵심 3줄 요약.
- 이슈 본문(description) PATCH 시 `@username` 추출 → `UserLookupPort.findIdsByUsernames`로 해석 → `IssueMentioned`(issue.mentioned) pgmq 발행 (같은 트랜잭션 outbox).
- diff 기반(신규 추가 멘션만) + 자기멘션 제외 + 미존재/이메일/코드스팬 무시. 신규 멘션 0건이면 미발행.
- 신규 REST 엔드포인트·Flyway 마이그레이션 없음. cross-BC는 UserLookupPort 확장만. 댓글·Inbox·D6/D7 deferred.

## Brainstorming Check

✅ 통과 (1회 iteration). sealed subtype 추가 안전성·q_issue_events 무소비자 패턴 코드 검증 완료. Maxi 결정 gap 없음.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
