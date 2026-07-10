# FR-SL-03 Slack Unfurl (Atlas URL 자동 카드)

> slug: fr-sl-03-slack-unfurl
> type: feature
> agent: backend-engineer
> 생성: 2026-07-11

## Brief

**사용자 원문**. "fr-sl-03 진행해줘"

FR-SL-03 — Slack Unfurl (Atlas URL 자동 카드). Slack 대화에 Atlas 이슈 URL을 붙이면
Slack이 `link_shared` 이벤트를 백엔드로 전송 → 백엔드가 열람 권한을 확인한 뒤
이슈 키·제목·상태·담당자를 담은 Block Kit 카드를 반환해 Slack이 링크를 카드로 펼침(unfurl).

**분류 결과**. classify-task가 `ui/frontend-engineer`로 오판 → 명세(`slack-integration.md §3.1`)
근거로 `feature/backend-engineer`로 교정. D6 프론트 UI = 해당 없음, D1~D5 backend-engineer, D7 qa-engineer.

**명세 위치**. `docs/plan/product/slack-integration.md §3.1`, `docs/sdd/09-notifications-slack.md §9.3.3`

## 도메인 정리

- **BC**: slack-integration (`com.bts.slack`). issue-tracking/identity-access 직접 import 금지 → shared-kernel 포트 경유.
- **신규 엔티티/VO**: `UnfurlPayload`(link_shared 파싱 결과), `IssueUnfurlView`(카드 이슈 스냅샷). shared-kernel에 `IssueUnfurlPort` 신설.
- **재사용 자산**: `SlackBotTokenResolver.resolve(teamId)`·`SlackMessageClient` SDK 패턴·`IssueVisibilityPort`(가시성 로직 재사용)·`UserLookupPort.findDisplayNamesByIds`·`SlackBlockKitRenderer`·SDK 번들 `SlackSignature.Verifier`.
- **신규 구현**: 서명 검증기(X-Slack-Signature)·`POST /slack/events`·역방향 매핑 조회(+V702 인덱스)·`IssueUnfurlPort`(결합 fail-closed)·`chat.unfurl` 클라이언트·unfurl 카드 렌더.

### Maxi 게이트 결정 (3)
1. **cross-BC 이슈 조회 = 단일 결합 fail-closed 포트** — `getVisibleIssueCard(issueKey, viewerUserId): IssueUnfurlView?`. 볼 수 없으면 null → slack BC가 데이터 물리적 미수신(fail-open 구조 차단).
2. **카드 = 정보 카드만** — 키+제목/상태/우선순위/담당자. 액션 버튼은 FR-SL-05로 미룸.
3. **3초 룰 = ack 200 즉시 + 경량 @Async** — chat.unfurl 비동기. 새 pgmq 큐/마이그레이션 없음.

### 권한 모델
- 공유자(Slack user) → 역매핑 → Atlas user → 열람 권한 판정. 미매핑/무권한 → unfurl 안 함(fail-closed).
- 수용 한계: unfurl 카드는 채널 전원 노출(GitHub/Jira 동일 트레이드오프). ADR 명시.

- **기존 결정 충돌**: 없음. FR-SL-01(수신=자체 컨트롤러)·FR-SL-02(봇토큰/매핑) 자산 연장.
- **관련 ADR**: [docs/decisions/2026-07-11-fr-sl-03-slack-unfurl.md](../decisions/2026-07-11-fr-sl-03-slack-unfurl.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
