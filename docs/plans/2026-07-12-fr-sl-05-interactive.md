# FR-SL-05 인터랙티브 메시지 (버튼/메뉴)

> slug: fr-sl-05-interactive
> type: feature
> agent: backend-engineer (+ security-engineer D4)
> primary_bc: slack-integration
> 생성: 2026-07-12

## Brief

**사용자 원문**. "fr-sl-05 진행하자" — FR-SL-05 인터랙티브 (Slack 메시지 버튼/셀렉트 등 인터랙티브 컴포넌트 처리).

**classify 교정** (진실 출처 = 이 plan, cache 아님 — 멀티세션 충돌로 cache 신뢰 불가).
- classify 원 판정. `type=ui / agent=frontend-engineer / primary_bc=issue-tracking` — "컴포넌트" 단어를 React UI로 오판.
- 교정. `type=feature / agent=backend-engineer (+security-engineer D4) / primary_bc=slack-integration`.
- 근거. `docs/plan/product/slack-integration.md §3.3` D1~D7 전부 backend/db/security/qa 책임, **D6 프론트 UI = 해당 없음**. Slack Block Kit 버튼은 Slack이 렌더 → React UI 없음. FR-SL-03 D6에 동일 오분류 교정 선례 기록됨.

**스코프 요지** (SDD 9.3.5 + product §3.3).
- 알림 메시지(Slack DM)에 액션 버튼 포함. 예. `[상세보기] [완료로 표시] [코멘트 추가]`.
- 사용자가 Slack에서 버튼 클릭 → Slack이 Interactivity Request URL로 `block_actions` payload POST.
- 백엔드. 서명검증 → payload 파싱 → Slack 사용자 → BTS 사용자 해석 → 권한 가드 → 액션 수행(상태 전이/담당자 변경/댓글) → Slack 메시지 갱신(response_url / chat.update).
- 선행. FR-SL-04(§3.2). 인바운드 서명검증기·ack200+@Async 패턴 재사용.

**D 단계 (product §3.3)**.
- D1 도메인 — InteractiveAction (backend-engineer)
- D2 명세 — 상태 전이, 담당자 변경 등 (backend-engineer)
- D3 데이터 모델 — (활용) (db-engineer)
- D4 백엔드 — block_actions handler + 권한 가드 + 응답 갱신 (backend-engineer + security-engineer)
- D5 백엔드 테스트 (backend-engineer)
- D6 프론트 UI — (해당 없음)
- D7 E2E (qa-engineer)

**멀티세션 주의**. 동시 세션 = FR-AT-02 D6 (automation 프론트, `.worktrees/fr-at-02-d6-d7-ui`). 모듈·레이어 분리로 충돌 위험 낮으나, 머지 직전 Flyway V번호 + git 브랜치 재확인 필수.

## 도메인 정리

- **BC**. slack-integration (`com.bts.slack`). 이슈 변경은 shared-kernel cross-BC 쓰기 포트 위임(BC 격리 유지).
- **영향 엔티티/자산**.
  - 신규. `SlackInteractionPayload`(block_actions / view_submission 파싱 VO), `InteractiveAction`(도메인 개념 — 완료전이/담당자변경/댓글), `SlackInteractionsController`(`POST /slack/interactions`).
  - 재사용(전수 실재 검증 — phantom 0). `SlackSignatureVerifier`(서명검증), `SlackUserMappingRepository.findUserIdBySlackUserId`(역매핑 V702), `SlackResponseUrlClient`(response_url 아웃바운드), `SlackBlockKitRenderer`(버튼 렌더 확장), `IssueMutationPort.assign/addComment`(FR-AT-02), `IssueTransitionPort.transition`(FR-BD-01 보드).
  - 결정 대기. `SlackInteractionLog`(버튼 액션 감사 — domain 노트는 신규 엔티티 명시 / product D3는 "(활용)". 스펙에서 확정).
- **액션↔포트 매핑** (신규 cross-BC 쓰기 포트 불필요).
  - 완료로 표시 → `IssueTransitionPort.transition(BoardTransitionCommand)`. `expectedVersion`(OCC)·`resolutionId`(DONE 카테고리) 필요 → 현재 이슈 버전/상태 선조회 필요(스펙 결정).
  - 담당자 변경 → `IssueMutationPort.assign(AssignCommand)`. FR-SL-05 = IssueMutationPort **2번째 소비자**.
  - 코멘트 추가 → `IssueMutationPort.addComment(AddCommentCommand)`. 텍스트 입력은 Slack 모달(`view_submission`)로 받을지 스펙 결정.
  - 상세보기 → URL 버튼(BTS 웹 링크). 백엔드 콜백 없음.
- **새 용어**. 인터랙티브 액션(Interactive Action — block_actions), 모달 제출(view_submission) — 스코프 확정 후 glossary 반영 검토.
- **기존 결정 충돌**. 없음. FR-SL-03(Events)·FR-SL-04(Slash)가 인바운드 서명검증·ack200+@Async→response_url 기반 확립 → FR-SL-05 자연 확장. FR-AT-02 IssueMutationPort 재사용(2번째 소비자).
- **관련 ADR**. 선행 [2026-07-11-fr-sl-04-slash-command.md](../decisions/2026-07-11-fr-sl-04-slash-command.md)(인바운드 패턴) · [2026-07-11-fr-sl-03-slack-unfurl.md](../decisions/2026-07-11-fr-sl-03-slack-unfurl.md)(서명검증·역매핑) · FR-AT-02(IssueMutationPort). 신규 ADR `2026-07-12-fr-sl-05-interactive.md`는 bts-spec에서 생성.
- **grill-with-docs 축약 사유**. 도메인 언어/BC 경계가 SL-03/04로 이미 확립, 재사용 포트 전수 실재 검증 완료, 남은 것은 스코프/설계 결정(모달·감사·완료전이 버전) → bts-spec office-hours의 구체 선택지로 위임.

## 스펙

전체 스펙. [docs/specs/2026-07-12-fr-sl-05-interactive.md](../specs/2026-07-12-fr-sl-05-interactive.md)

**Maxi 스코프 결정(2026-07-12)**. 풀세트(완료+담당자+상세보기+**코멘트 모달**) · 완료=**resolution 선택 모달** · **V703 감사 테이블 신설**.

핵심 시나리오 요약.
- `POST /slack/interactions` — block_actions(버튼/셀렉트) + view_submission(모달 제출) 2종. 서명검증 선행 → 역매핑 → cross-BC 쓰기 포트(권한 fail-closed) → 원본 메시지 갱신.
- 완료로 표시 → views.open(resolution 모달, 동기 3초) → 제출 → `IssueTransitionPort.transition`.
- 담당자 변경 → Slack `users_select`(후보 포트 우회) → 역매핑 → `IssueMutationPort.assign`.
- 코멘트 추가 → views.open(모달) → 제출 → `IssueMutationPort.addComment`.

신규. `POST /slack/interactions` 컨트롤러·payload 파서(2종)·모달 빌더(2)·`SlackMessageClient` views.open/chat.update 확장·`SlackBlockKitRenderer` actions 블록·**V703 slack_interaction_log**·**신규 읽기 포트 `IssueCompletionOptionsPort`**(version+done전이+resolution) + issue-tracking 어댑터.

## Brainstorming Check

✅ 통과 (포트 조사 중 sanity check 수행 — 발견 5건 스펙 반영).
- trigger_id 3초 만료 → 모달 오픈은 동기 views.open, 그 외는 ack200+@Async (SL-04와 다른 핵심 제약).
- 모달 제출 지연 시 OCC 충돌 → private_metadata에 expectedVersion 박제 + 충돌 시 ephemeral 재시도(form-occ-409 선례).
- 담당자 후보: `ProjectMembershipPort`는 방향 반대 → Slack 네이티브 `users_select`로 후보 포트 회피.
- resolution 목록 포트 부재 → 신규 `IssueCompletionOptionsPort`(version+done전이+resolution 결합 읽기, fail-closed).
- 상세보기 url 버튼 payload는 no-op 200(dispatch 경고 방지).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
