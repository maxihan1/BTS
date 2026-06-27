# FR-MN-01 D6/D7 — 본문 @멘션 시각 강조 렌더링 + 멘션→Inbox 도착 E2E

> slug: fr-mn-01-d6-d7-mention-ui
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7 E2E)
> 생성: 2026-06-27

## Brief

**원문**. "fr-mn-01 d6 d7 진행하자"

FR-MN-01(본문/댓글 @멘션 + 즉시 알림)의 D1~D5(백엔드 발행)는 PR #114로 완료. D6(멘션 렌더링)·D7(Inbox 도착 E2E)은 당시 "알림 전달(FR-NT)·Inbox(FR-UX-03) 인프라 부재"로 deferred됐다. 그 선행 FR이 모두 완료되어 이제 진행 가능.

**핵심 발견 (코드 확인, 2026-06-27)**. 백엔드 멘션→알림→Inbox 파이프라인은 **이미 완성**.
- `NotificationWorker.kt:357` — `ISSUE_MENTIONED` → "…에서 멘션되었습니다" 알림 생성
- `EventRecipientResolver.kt:147` — `RecipientRole.MENTIONED` → `mentionedUserIds`를 수신자로 해석
- `V403__seed_mention_policy.sql` — `issue.mentioned` MENTIONED×IN_APP 정책 시드 (FR-NT-02에서 추가)
- Inbox는 notifications 테이블 확장 (FR-UX-03, #186/#187)

→ **순수 프론트엔드 작업**. FR-MN-02 자동완성(#158)과 동일하게 백엔드 신규 0.

**범위**.
- D6. 본문(description) 렌더링 시 `@username` 시각 강조 (frontend-engineer)
- D7. "본문에 나를 @멘션 → Inbox에 알림 도착" E2E (qa-engineer)

**classify**. 원래 type=qa·agent=qa-engineer로 오판(E2E/Inbox 키워드) → FR-MN-02 선례대로 type=ui·agent=frontend-engineer 수동 조정.

## 도메인 정리

- **BC**: issue-tracking (프론트 표현 계층)
- **영향 엔티티**: 신규 0. `Issue.description`(기존) 읽기 표현만.
- **새 용어**: 0. "멘션"은 기존 통용 용어 (glossary "그룹 멘션" 항목에 이미 등장). 새 용어 도입 없음.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: 멘션 직접 ADR 없음. notification 관련 ADR 2개(`2026-06-11-notification-policy-bc-bootstrap`, `2026-06-12-notification-inapp-channel-delivery`)는 백엔드 전달 결정이라 D6/D7 프론트와 무충돌.
- **작업 대상 파일**:
  - D6 — `apps/web/src/components/issue/IssueDescription.tsx` (본문 렌더링 컴포넌트, FR-IS-04 Write/Preview 마크다운)
  - D7 — `apps/web/e2e/` 신규 spec (멘션→Inbox 도착)
- **참고 선례**: FR-MN-02 자동완성(#158, `useMentionAutocomplete`/`MentionDropdown`) — 동일 textarea 멘션 영역, 입력측. D6은 출력(렌더링)측.
- grill-with-docs 스킵 사유: 새 도메인 개념 0, 기존 멘션 개념의 프론트 표현 계층 추가에 한정.

## 스펙

전체 스펙. [docs/specs/2026-06-27-fr-mn-01-d6-d7-mention-ui.md](../specs/2026-06-27-fr-mn-01-d6-d7-mention-ui.md)

핵심 결정 요약.
- **강조 위치 = 백엔드 MarkdownRenderer (Maxi 확정 옵션 A)**. `@username` → `<span class="mention">`, 정화 allowlist에 `span[class=mention]` 추가. same-BC view layer라 이 PR 처리.
- 마크업 규칙 = MentionParser 추출 규칙 일치(코드/링크/이메일/`@@` 제외). flexmark 인라인 확장 우선 검토(코드/링크 자동 제외).
- **⚠️ 결정3 (게이트1 재검토)**. 실존 검증 안 함 — 형식 기반 강조(renderSafe 무상태 유지). @typo도 강조됨(알림은 발행측 실존검증). 대안=renderSafe에 사용자 조회 결합(복잡, 미채택).
- 프론트 = `.mention` CSS만(IssueDescription dangerouslySetInnerHTML 불변). PDF 자동 반영.
- D7 = 멘션→Inbox 도착 E2E. ground-truth는 백엔드 통합테스트, E2E는 프론트 도착 UI.

## Brainstorming Check

✅ 통과 (직접 sanity check 1회). gap 4건 보강(NFR2 부분집합·D7 분담·클릭없음·self멘션). plan 이관 실증 — flexmark 확장 구현 가능성 / 멘션 정규식 단일 출처.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
