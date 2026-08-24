# 프로덕션 UI 결함 12건 + `/ws` 핸드셰이크 permitAll

> 티어: T2
> slug: prod-ui-defects-12-ws-permitall
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-24

## Brief

`bts.maxihan.com` 에 로그인해 48개 라우트를 데스크톱(1440x900)·모바일(390x844) 두 뷰포트로 전수
순회하고, DOM 계측 프로브 + 스크린샷 47장 병렬 시각 리뷰로 결함을 뽑아 12건을 수정했다.
제보 25건 중 반증 단계를 통과한 22건이 남았고 그중 12건을 이번 PR 범위로 닫는다.

관련 FR — **FR-NT-02**(인앱 WebSocket 알림) · **FR-PM-07**(필드 권한) ·
jira-parity 로드맵 **F24**(모바일 드로어) · **F21**(PageLayout 전수 적용, 부분만).

### 핵심 3건

1. **필드 권한 화면 상시 에러 (FR-PM-07)** — 서버는 맨 배열 `[]` 를 주는데 프론트 Zod 스키마와
   MSW mock 은 `{data:[…]}` 래퍼를 기대했다. mock 이 스키마와 같은 거짓 계약을 공유해 단위
   테스트 9,668개가 전부 초록인데 실화면만 죽어 있었다(`two-lists-never-check-each-other` 양식).
   실계약 red 테스트 2건(`T-FP-3b2`·`T-FP-3b3`)을 먼저 세우고 스키마·mock 을 동시에 고쳤다.
   정본은 백엔드 `FieldPermissionController.listRules`.

2. **WebSocket 401 (FR-NT-02)** — 중앙 `SecurityConfig` 에 `/ws` HTTP permitAll 이 없어
   `anyRequest().authenticated()` 가 업그레이드 요청을 먼저 잘랐다. 프로덕션 전 화면에서 실시간
   인앱 알림이 죽어 있었다. `antMatcher(WS_HANDSHAKE_PATH)` 로 **핸드셰이크만** 열고 인증 지점은
   STOMP `CONNECT` frame 의 `StompAuthChannelInterceptor` 에 그대로 둔다.

3. **모바일 드로어 (F24)** — `sheet.tsx` 프리미티브를 신설하지 않고 `max-md:` 오프캔버스 +
   백드롭으로 풀었다. 드로어 상태를 `collapsed` 와 분리(`use-sidebar-drawer.ts`), 토글 대상 선택은
   `useSidebarToggle` 이 단일 소유, JS 미디어쿼리와 Tailwind `max-md:` 정합 판별식을 신설했다.
   데스크톱 클래스는 변경하지 않았다.

### 그 외 9건

`FilterBar` `items-end`→`items-start`(이슈·보드·백로그 3화면 공통) · `TopBar` `max-md` 축약
(검색 입력창은 계약상 불변이라 무변경) · `settings/details` 컨테이너 정렬(F21 부분) ·
`board-labels` · `AuditLogFilters` · `NotificationPolicyForm` · `FavoritesMenu` ·
`InboxFilters` · `CreateIssueDialog` · `IssueAssigneeSelect` · `AccountMenu` ·
`admin.workflow-schemes.new`.

### classify 결과와 이탈

- classify 원출력 — `type=ui` · `agent=frontend-engineer` · `tier=T1` · slug `ui-12-ws-permitall`
- **이탈 1 — 티어.** 선언 T2 를 쓴다. `SecurityConfig.kt` 가 `SEC_*`·`BE_MAIN` 표면이고
  혼합은 최고 티어가 지배한다(`/bts` 판정 5문 ①). classify 는 제목만 보므로 경로를 못 본다.
- **이탈 2 — slug.** 자동 생성 `ui-12-ws-permitall` 이 뜻을 잃어 수동 지정했다.
- **이탈 3 — worktree 미사용.** 구현이 `main` 워킹트리에서 이미 끝나 있어
  `git switch -c` 로 브랜치만 팠다. 이송 0 · husky 훅 네이티브 동작. Maxi 승인(D2).
- **이탈 4 — 단계 순서.** 구현이 [2]~[5] 보다 먼저 끝났다. 이 plan 은 사후 작성이다.
- ★classify 가 제목의 「스키마」에 반응해 `type=migration`·`agent=db-engineer` 로 오분류했다
  (같은 제목에서 그 단어만 빼면 `ui`/`frontend-engineer`). 부채 등재 후보.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
