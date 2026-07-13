# FR-SL-06 D6 UI (프로젝트 설정 → Slack 채널 관리 화면) + D7 E2E

> slug: fr-sl-06-d6-d7-slack-channel-mapping-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-13

## Brief

FR-SL-06(채널↔프로젝트 매핑)의 마지막 남은 D6 UI + D7 E2E를 구현한다.
백엔드는 완료됨:
- PR-A(#264): 매핑 CRUD API + 설정. V704, projectKey, 하드삭제, PROJECT_ADMIN 가드,
  403→404/malformed→400 정규화.
- PR-B(#266): 채널 라우팅. notification SlackChannelBroadcaster → q_slack_channel_broadcasts
  → 채널 워커, 보안게이트 IssueSecurityClassificationPort(fail-closed).

D6 = 프로젝트 설정 화면에 "Slack 채널 관리" 섹션 신설.
  채널↔프로젝트 매핑 CRUD(생성/목록/삭제) + event_filter 선택 UI.
D7 = Playwright E2E 시나리오.

완료 시 slack-integration BC = 6/6 · BC 완료 마킹 + 문서 전수 동기화(verify-master-plan).

## 도메인 정리

- BC: slack-integration (프론트엔드 소비). 새 도메인 용어/엔티티 없음 — 백엔드 완비.
- 새 용어: 없음. glossary 갱신 불필요.
- 기존 결정 충돌: 없음.
- 관련 ADR: [docs/decisions/2026-07-13-fr-sl-06-channel-mapping.md](../decisions/2026-07-13-fr-sl-06-channel-mapping.md) (PR-A). 신규 ADR 불필요 — UI는 순수 소비.

### 백엔드 API 계약 (코드 확인 완료, 이 UI가 붙는 대상)

REST 4 endpoint (`SlackChannelMappingController`, `/api/v1/slack/channel-mappings`, JWT 전용·PAT 401):

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/channel-mappings` | `{projectKey, channelId, channelName?, eventTypes: string[]}` | 201 + `ChannelMappingResponse` |
| GET | `/channel-mappings?projectKey=` | — | 200 + `ChannelMappingResponse[]` |
| PATCH | `/channel-mappings/{id}` | `{channelId?, channelName?, eventTypes?}` (null=미변경) | 200 + `ChannelMappingResponse` |
| DELETE | `/channel-mappings/{id}` | — | 204 |

`ChannelMappingResponse` = `{id: UUID, projectKey, channelId, channelName: string|null, eventTypes: string[](정렬됨), createdAt, updatedAt}`. **team_id 비노출**(단일 워크스페이스 설치, 내부 해석).

**event_filter 허용값 10종** (`SlackChannelEventType`, notification wireValue 미러):
`issue.created` · `issue.assigned` · `issue.transitioned` · `issue.commented` · `issue.due_soon` · `issue.overdue` · `sprint.started` · `sprint.ended` · `automation.failed` · `issue.mentioned`. 빈 집합/미지값 → 400.

**권한/에러 계약**: PROJECT_ADMIN(service 내부 fail-closed). 권한 거부/미존재 → **404**(존재 비노출). malformed 입력 → **400**. PAT 인증 → 401.

### 프론트엔드 관례 (같은 앱 grep 확인)

- 라우트: `src/routes/projects.$projectKey.settings.slack-channels.tsx` (선례 `.automation.tsx` 동형 — projectKey 스코프 + admin 게이트 + 룰 CRUD).
- API: `src/api/slack.ts`에 채널 매핑 함수 추가(기존 slack API 관례 유지).
- MSW: `src/mocks/slack-channel-mapping-handlers.ts` 신규 + **전역 등록 필수**(`msw-global-handler-registration-gap` 회귀 방지).
- 컴포넌트: `src/components/settings/` 하위(기존 `SlackConnectionCard` 등과 동일 위치).

### 스코프 보정

- plan 스텁 Brief의 "CRUD(생성/목록/삭제)"는 **update(PATCH) 누락**. 백엔드가 PATCH 완비 → UI도 수정 포함(완제품 기준). 스펙에서 최종 확정.

## 스펙

전체 스펙. [docs/specs/2026-07-13-fr-sl-06-d6-d7-slack-channel-mapping-ui.md](../specs/2026-07-13-fr-sl-06-d6-d7-slack-channel-mapping-ui.md)

핵심 시나리오 요약.
- 프로젝트 설정 → "Slack 채널" 탭에서 채널↔프로젝트 매핑을 CRUD(생성/목록/수정/삭제).
- 각 매핑 = 채널 ID(+표시명) + event_filter(10종 이벤트 유형 다중선택).
- 선례 `settings.automation.tsx` 미러(RouteAdapter+props-Page, List+FormDialog, `p-8 space-y-6 max-w-2xl`).
- office-hours 스킵(메모리 `bts-spec-office-hours-mismatch` — 명확·완결 후속 FR). design-consultation 스킵(DESIGN.md 존재). design-shotgun 스킵(기존 설정 페이지 패턴 미러, 새 시각 발명 불필요).

**게이트1 확정 필요 결정**.
- **C1. 채널 ID 직접입력(추천) vs 채널 picker(백엔드 확장)** — 백엔드에 채널 목록 API 없음.
- EC4 워크스페이스 미설치 사전 안내 배너 포함 여부(409 처리는 기본, 배너는 선택).

## Brainstorming Check

✅ 통과 (1회 iteration). 409 두 종류(중복·워크스페이스미설치) 폼 처리 과소명세 발견 후 보강. C1 결정만 게이트1로.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
