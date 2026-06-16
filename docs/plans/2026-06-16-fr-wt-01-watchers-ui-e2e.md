# FR-WT-01 Watcher 프론트 D6/D7 — Watch 버튼 + 카운트 UI + E2E

> slug: fr-wt-01-watchers-ui-e2e
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-16

## Brief

사용자 원문. "fr-wt-01 프론트 watch 버튼+카운트 UI 및 E2E (백엔드 D1~D5는 PR #151로 머지 완료, 후속 PR slug fr-wt-01-watchers-ui-e2e)"

FR-WT-01(이슈 Watcher 추가/제거 + 자동 Watcher)의 백엔드 D1~D5는 PR #151(squash 6e954719)로 머지 완료. 이번 작업은 남은 프론트 D6/D7.

- **D6** — Watch 버튼(토글) + watcher 카운트 UI를 이슈 상세 페이지에 통합. `GET/POST/DELETE /api/v1/issues/{key}/watchers` 호출, `isWatching`으로 버튼 상태.
- **D7** — Playwright E2E(watch/unwatch/카운트/권한 게이팅).

classify 결과. type=qa로 오판정(E2E 키워드) → ui로 교정(본체는 프론트 UI, E2E 따라붙음). 메모리 `classify E2E→qa 오판정 ui 교정` 적용.

## 도메인 정리

- **BC**: issue-tracking (프론트는 BC 경계 무관하나 소비 대상 API의 BC)
- **신규 용어**: 없음. glossary에 "워처(Watcher) — 이슈 변경 알림 수신자" 이미 정의됨.
- **영향 엔티티(읽기 전용 소비)**: Issue(상세), IssueWatcher(목록/카운트). 프론트는 신규 도메인 모델 도입 없음 — 기존 백엔드 API 소비만.
- **기존 결정 충돌**: 없음. 관련 ADR(`2026-06-02-issue-clone-semantics` cloneIssue 자동watch 제외, `2026-06-01-issue-assignee-user-lookup-port`)은 백엔드 영역, 프론트 UI에 영향 없음.

### 소비할 백엔드 API 계약 (정본: backend `com/bts/issue/watcher/web/*`, PR #151 머지됨)

| 메서드 | 경로 | 요청 | 응답 | 권한 / 에러 |
|---|---|---|---|---|
| GET | `/api/v1/issues/{key}/watchers` | — | 200 `DataResponse<WatcherListResponse>` | VIEW / 404·403 |
| POST | `/api/v1/issues/{key}/watchers` | `{ userId?: UUID }` (없으면 self) | 201 (멱등, no body) | self=VIEW·타인=UPDATE / 404·403·422 |
| DELETE | `/api/v1/issues/{key}/watchers/{userId}` | — | 204 (멱등, no body) | self=VIEW·타인=UPDATE / 404·403 |

`WatcherListResponse` = `{ watchers: [{ userId: UUID, displayName: string }], count: number, isWatching: boolean }` (`@JsonInclude(NON_NULL)`).
- GET만 `DataResponse` 래퍼(`{ data: {...} }`). POST/DELETE는 본문 없음.
- POST 멱등(이미 watch여도 201), DELETE 멱등(미존재여도 204).
- D6 범위: **self watch/unwatch + 카운트 + isWatching 버튼 상태**. 타인 추가(UPDATE 권한)는 백엔드 지원하나 UI 1차 범위는 self 토글로 한정(스펙에서 확정).

## 스펙

전체 스펙. [docs/specs/2026-06-16-fr-wt-01-watchers-ui-e2e.md](../specs/2026-06-16-fr-wt-01-watchers-ui-e2e.md)

**D6 범위(Maxi 확정 — Option A)**: self watch/unwatch 토글 + 감시자 카운트 + 감시자 명단(읽기 전용). 타인 수동 추가 UI 제외.

핵심 시나리오 3줄.
- "보기"/"보기 취소" 토글 → POST(self)/DELETE(내 userId) → 카운트·명단 갱신(invalidate-only).
- 메타패널 감시자 섹션에 카운트("N명")+명단(displayName). 자동 추가된 보고자/담당자도 표시.
- 담당자/컴포넌트 변경 시 자동 watcher 라이브 갱신(FR-7, watcher 쿼리 invalidate).

## Brainstorming Check

✅ 통과 (1회). gap 1건(자동 watcher 라이브 갱신 FR-7) 보강 완료.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
