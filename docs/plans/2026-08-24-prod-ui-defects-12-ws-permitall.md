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

## 도메인 정리

| 항목 | 내용 |
|---|---|
| BC | **identity-access**(중앙 `SecurityConfig`) + **apps/web** 셸. `notification` BC 는 `/ws` 엔드포인트 소유자라 **읽기만** 했다 — 프로덕션 코드 0줄 |
| 영향 엔티티 | 없음. 마이그레이션 0 · 스키마 0 · 신규 API 0 |
| 새 용어 | 없음. `glossary.md` 갱신 불필요 |
| 관련 ADR | `docs/decisions/2026-07-17-git-webhook-inbound-permitall.md` — permitAll 을 중앙 `SecurityConfig` 에 등록하는 **선례이자 실측 근거**(401 판별자가 왜 공허해지는지를 그 KDoc 이 기록). ADR **D-2**(전역 키 리스너 소유자 허용목록 봉인) — Esc 닫기를 포기한 근거 |
| 기존 결정 충돌 | 없음. `/ws` permitAll 은 「인증 지점을 STOMP CONNECT 로 미룬다」는 FR-NT-02 원설계를 **복원**하는 것이지 뒤집는 것이 아니다 |

## 스펙

> `/bts-spec` §5 경량 경로 — `type=ui` 가 보안 표면 때문에 T2 로 승격된 경우. 9섹션 대신
> `Jira 대조` · `엣지 케이스` · `측정 가능한 완료 기준` 3섹션 + 시각 검증 기준 + 보안 절.

### Jira 대조

| 화면 | Jira Cloud 대응 | BTS 이전 | 이번 변경 |
|---|---|---|---|
| 앱 셸 사이드바 (모바일) | 좁은 폭에서 사이드바가 오프캔버스 드로어로 전환, 백드롭이 본문을 덮고 헤더 토글로 여닫는다 | 데스크톱 레일만 있어 390px 에서 264px 사이드바가 본문을 밀어냈다 | `max-md:` 오프캔버스 + 백드롭. 데스크톱 클래스 무변경 |
| 상단바 (모바일) | 좁은 폭에서 보조 컨트롤을 접고 검색·생성만 남긴다 | 컨트롤 7종이 390px 에 전부 남아 겹쳤다 | 워드마크 텍스트·프로젝트 전환기·설정 톱니를 `max-md` 에서 감추고 「만들기」를 아이콘 전용으로. **검색 입력창은 무변경** |
| 필터바 | 섹션 라벨이 상단 정렬 | `items-end` 라 섹션 높이 차(212/112/52/62)만큼 라벨 top 이 145/245/305/295 로 벌어졌다 | `items-start` |
| 필드 권한 | Jira 대응 없음 — ADS 준용 | 항상 에러 화면 | 스키마 계약 교정 |

### 즉사 계약(§2) 대조 — 실측

| 계약 | 실측 | 판정 |
|---|---|---|
| `aria-label` 4종 (`메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환`·`검색`) | 4종 모두 문자열 무변경. `ProjectSwitcher` 는 `<nav>` 가 아니라 popover 라 감싸는 `div.max-md:hidden` 이 nav 이름을 건드리지 않는다 | ✅ |
| `<h1>` 단 하나 + 이름 verbatim | `settings/details` 는 컨테이너만 바꿨고 h1 문구는 한 글자도 안 바꿨다. `BoardPage` h1 은 **신규 추가**(래칫 +5줄의 정체) | ❓ 신규 h1 이 그 라우트에 2개를 만들지 않는지 [3] 에서 확인 |
| `role="dialog"` 고유 label | 신규 다이얼로그 0 | ✅ |
| **관리 메뉴 기본 펼침 · 모바일 드로어는 모바일 폭에서만** | `isAdmin && (...)` 렌더 조건 무변경. 드로어는 `max-md:`(767.98px 이하)에서만 적용. **좁은 폭 E2E 3스펙 23/23 통과**(2026-08-24 실행) | ✅ 실측 |
| `검색` 이름 분리 + `exact: true` | `TopBar` 검색 입력창 무변경. `getByRole('searchbox', {name:'전역 검색', exact:true})` 그대로 유효 — 375px 스펙의 준비 단계가 이 셀렉터를 쓰는데 통과했다 | ✅ 실측 |
| 프로젝트 스위처 nav 금지 | popover 유지, 래퍼 `div` 만 추가 | ✅ |
| 팔레트 QUICK_LINKS 순서 | 무변경 | ✅ |
| 단축키 레지스트리 동결 | `SHORTCUTS` 무변경. **Esc 닫기를 포기한 이유가 이것**(ADR D-2 허용목록) | ✅ |

### 보안 — `/ws` permitAll (게이트 1 안건)

learnings 2026-07-15 「permitAll 을 여는 PR 은 대상 경로의 본문 수신 방식을 **먼저 grep** 한다」
(PR #274/#275, nginx 110m × 힙 1152MB → 요청당 ~330MB OOM 사고) 규칙을 적용해 실측했다.

| 점검 | 실측 결과 |
|---|---|
| 본문 적재 경로 | `/ws` 는 컨트롤러가 **아니다** — `notification` BC `WebSocketConfig.registerStompEndpoints` 의 `registry.addEndpoint("/ws")`. `@RequestBody` 0건. 핸드셰이크는 본문 없는 GET 업그레이드다 |
| 하위 경로 노출 | `withSockJS()` 호출 **0** → `/ws/info`·`/ws/xhr_send` 폴백 경로 부재. 단일 경로 permitAll 로 완결되며 와일드카드가 필요 없다 |
| 백프레셔 | 기설정 — `MESSAGE_SIZE_LIMIT_BYTES` 64KB · `SEND_TIME_LIMIT_MS` 10초 · `SEND_BUFFER_SIZE_LIMIT_BYTES`. 슬로우 클라이언트 힙 고갈 방어선이 이미 있다 |
| CSWSH | 인증이 앰비언트 쿠키가 아니라 CONNECT frame 의 `Authorization: Bearer` 라 교차 출처 소켓이 사용자 자격을 도용할 수 없다 |
| 매처 종류 | `antMatcher(WS_HANDSHAKE_PATH)`. 문자열 매처는 MVC 가 있으면 `MvcRequestMatcher` 로 해석되는데 `/ws` 는 MVC 핸들러가 아니라 매칭이 보장되지 않는다(`SamlSecurityConfig` 선례) |
| 인증 소멸 여부 | **아니다.** 열린 것은 HTTP 핸드셰이크뿐. CONNECT 검증은 `StompAuthChannelInterceptor` 단독 — 헤더 부재·형식 오류·decode 실패·subject 부재·PAT 를 모두 거부하고 클라이언트발 SEND 도 막는다(push-only) |
| ❓ 미확인 | `setAllowedOrigins` 미설정 — Spring 기본값(동일 출처)에 의존한다. 명시 설정이 필요한지 게이트 1 에서 판정 |

### 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| E1 | 데스크톱에서 접은(`collapsed=true`) 뒤 모바일 폭으로 좁힘 | `useSidebarRailCollapsed` 가 모바일에서 항상 false → 드로어는 264px 전체 폭. **단 `RecentIssuesMenu` 미이주 — 결함 D6** |
| E2 | 모바일에서 드로어를 연 채 데스크톱으로 넓힘 | `ShellLayout` 의 `useEffect(!isMobile → setDrawerOpen(false))` |
| E3 | 드로어에서 링크 클릭 | pathname 구독 `useEffect` 가 닫는다 |
| E4 | 드로어 닫힘 상태의 포커스 | `-translate-x-full` 만으로는 탭 순서에 남는다 → `max-md:invisible` 병기 |
| E5 | 드로어가 헤더를 덮음 | `top-12`(TopBar `h-12` 짝). `inset-y-0` 금지 |
| E6 | 드로어가 열린 채 본문으로 탭 이동 | ❌ **미처리 — 결함 D7.** 포커스 트랩·`aria-modal` 없음, Esc 없음 |
| E7 | 필드 권한 응답이 빈 배열 | 맨 배열 `[]` 파싱 성공 (기존엔 ZodError) |
| E8 | `/ws` 아래 새 매핑이 생김 | 단일 경로라 자동 노출 없음. **인접 경로 401 판별식 부재 — 결함 D2** |
| E9 | `prefers-reduced-motion` | `motion-reduce:max-md:transition-none` |

### 측정 가능한 완료 기준

1. `pnpm verify` 통과 — tsc 0 · eslint 0 error · vitest 전량 green · vite build 성공
2. `./gradlew :modules:identity-access:test :modules:app:test :modules:notification:test ktlintCheck detekt` 통과
3. **`pnpm --filter web test:e2e` 전량 통과** — 계약 §5 「기존 E2E 동반 실행 없이 UI PR 을 닫지 않는다」
4. `/ws` permitAll 뮤테이션 — 그 줄을 끊으면 `:modules:app` `WebSocketHandshakePermitAllTest` 가 red
5. 발견 결함 D1~D7 이 전부 닫히거나 `TODOS.md` 에 등재됨
6. 판별식 전량 통과(`pnpm test:workflow`) · doc-index drift 0

### 시각 검증 기준 (§6 — 생략 금지)

**관련 E2E** — 좁은 폭을 실제로 쓰는 스펙이 판정 대상이다.

| 스펙 | 폭 | 결과 |
|---|---|---|
| `detail-action-shortcuts.spec.ts` | `NARROW_VIEWPORT = 375×812` — 768px 미만이라 드로어 구간 직격 | ✅ 통과 |
| `issue-move.spec.ts` | 루프가 1280·390·320 폭을 훑는다 | ✅ 통과 |
| `issue-split-view.spec.ts` | `NARROW_VIEWPORT = 800×900` — 768 초과라 데스크톱 경로 | ✅ 통과 |
| 위 3스펙 합계 | — | **23 passed (28.3s)**, 2026-08-24 실행 |
| `settings-admin-hub.spec.ts` | 기본 1280px — `banner` 안 `설정` 링크 단언. `max-md:hidden` 무관 | 전량 실행에서 확인 |
| 관리 메뉴 4스펙 (`notification-policies`·`audit-logs`·`global-permissions`·`webhook`) | 기본 1280px | 전량 실행에서 확인 |

**눈확인 항목** (라이트/다크 양쪽)

1. 390px — 드로어 열기/닫기, 백드롭 클릭 닫기, 링크 클릭 후 자동 닫힘
2. 390px — 드로어 안에서 「최근 이슈」가 보이는가 (D6 재현 경로 — 데스크톱에서 접은 뒤 좁히기)
3. 390px — 상단바에 검색·만들기·계정이 겹치지 않고 들어가는가
4. 1440px — 사이드바 레일/펼침이 이전과 동일한가 (회귀 없음)
5. `/settings/field-permissions` — 에러 화면이 사라지고 목록이 뜨는가
6. 프로덕션 배포 후 — 콘솔에 `WebSocket connection … failed` 가 사라지는가

### 발견 결함 — 사후 spec 이 드러낸 것 ([3] plan 의 task 로 넘긴다)

| # | 결함 | 근거 | 성격 |
|---|---|---|---|
| D1 | `SecurityConfig.kt` 주석이 **없는 판별식**을 짝이라 선언 | 「열림 1건 + 인접 경로 401 1건」이라 적었으나 그 파일의 실제 테스트는 「경로 상수 단일성」·「antMatcher 사용」 2건이다. 열림 증명은 `:modules:app` 쪽이 진다 | `invariant-satisfied-by-helptext-not-logic` |
| D2 | **인접 경로 401 판별식 부재** | 주석이 있다고 주장하는 그 가드가 어느 모듈에도 없다. permitAll 이 조용히 넓어져도 잡히지 않는다 | 가드 공백 |
| D3 | 파일명 오도 | `WebSocketHandshakePermitAllIntegrationTest.kt` 는 통합 테스트가 아니라 소스 텍스트 가드다 | 명명 |
| D4 | **Esc 모순 주석 2곳** | `ShellLayout.tsx` 백드롭 주석과 `Sidebar.tsx` `inset-y-0` 주석이 「Esc 가 닫는 길을 제공한다」고 적었는데, 같은 파일 위쪽 주석은 「ADR D-2 에 걸려 Esc 를 **포기했다**」고 적었다. `aria-hidden` 백드롭의 접근성 근거가 거짓 전제 위에 있다 | 거짓 근거 |
| D5 | `ShellLayout` 이 `useSidebarToggle` 을 **안 쓴다** | `ShellLayout.tsx:92` 가 `isMobile ? toggleDrawer : toggleCollapsed` 를 인라인 복제해 `[` 단축키에 넘긴다. 훅 KDoc 이 「토글 지점 셋을 여기 한 곳에서만 고른다」고 선언한 그 계약을 셋 중 하나가 지키지 않는다 | `two-lists-never-check-each-other` |
| D6 | **드로어에서 「최근 이슈」가 통째로 사라진다** | `RecentIssuesMenu.tsx:67,82` 가 `collapsed` 를 직접 읽고 `if (collapsed) return null` 한다. 형제 3곳(`Sidebar`·`ProjectTree`·`FavoritesMenu`)은 전부 `useSidebarRailCollapsed` 로 이주했는데 이것만 남았다. 재현 — 데스크톱에서 사이드바를 접고(localStorage 영속) 모바일 폭에서 드로어를 열면 그 메뉴만 없다 | 실 UI 버그 |
| D7 | 드로어에 포커스 트랩·`aria-modal`·Esc 가 없다 | 백드롭이 본문을 시각적으로 덮지만 키보드로는 뒤 콘텐츠에 탭이 들어간다. 닫는 길은 포인터 2개 + 상단바 토글뿐이다 | 접근성 |

### F24 / F21 로드맵 판정

- **F24 제약 2개 실측 통과** — `<aside>` 유지로 `complementary` 랜드마크 1개 불변
  (`ShellLayout.test.tsx:182` 단언 생존) · 관리 메뉴 `isAdmin && (...)` 렌더 조건 무변경.
- **F24 를 「완주」로 선언하지 않는다.** 로드맵 표는 F24 를 「모바일 드로어 (**Sheet**)」로
  적었는데 구현은 `sheet.tsx` 프리미티브 없이 `max-md:` 오프캔버스로 풀었다. 수단이 다르고
  D6·D7 이 열려 있다. 로드맵 표 갱신은 D6·D7 을 닫은 뒤 같은 PR 에서 하되, 「Sheet」라는
  수단 기술을 결과 기술로 바꾸는 것이 맞는지는 게이트 1 안건이다.
- **F21 은 의도적으로 부분만 했다.** `settings/details` 하나가 `PageLayout`(중앙정렬)을 써서
  형제 11개 탭과 236px 어긋나 있었고, **1개를 11개에 맞췄다**(반대 방향이 아니다). 전수 적용은
  48 라우트 규모의 별건이고 컨테이너 관례가 둘로 갈려 있어(`PageLayout` 5라우트 vs 좌측 `p-8`
  다수) 어느 쪽으로 모을지가 Maxi 결정 사항이다. h1 문구는 한 글자도 바꾸지 않았다(계약 §2).

## Sanity Check

**gap 4건 발견 — 전부 위 표에 반영했다. 스펙을 재작성하지 않았다.**

| gap | 유형 | 처리 |
|---|---|---|
| 드로어가 열린 채 뒤 콘텐츠로 탭이 들어간다 | 엣지 케이스 미커버 | E6 · D7 로 등재. 닫는 길이 포인터 2개뿐인 것과 한 묶음 |
| 「토글 대상은 한 곳이 소유」가 실제로는 셋 중 둘만 | 가정 누락 | D5 로 등재 |
| 「형제 전부 레일 판정으로 이주」가 실제로는 4곳 중 3곳 | 가정 누락 | D6 로 등재 (실 UI 버그) |
| `setAllowedOrigins` 미설정 | 모호 — 기본값 의존 | 보안 표에 ❓ 로 남기고 게이트 1 판정 대상 |

**Maxi 결정 필요 2건** — ① F24 를 완주로 선언할지(수단이 로드맵 기술과 다름)
② `setAllowedOrigins` 를 명시할지. 둘 다 게이트 1 안건으로 올린다.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
