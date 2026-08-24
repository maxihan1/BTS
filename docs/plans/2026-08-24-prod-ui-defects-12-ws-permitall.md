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

## Plan

> **선행 완료 — task 로 만들지 않는다.** UI 결함 12건 수정 전량이 이미 구현·검증됐다
> (프론트 tsc 0 · eslint 0 error · vitest 583 files / 9,680 tests · vite build 성공 ·
> 백엔드 `:modules:identity-access:test`·`:modules:notification:test`·ktlintCheck·detekt 성공).
> 아래 task 는 **[2] spec 이 사후에 드러낸 결함 D1~D7 + 문서 동기화 + 전량 재검증**이다.

### Task 1. D6 — 드로어에서 「최근 이슈」가 사라지는 버그를 닫는다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/RecentIssuesMenu.tsx`, `apps/web/src/components/issue/__tests__/RecentIssuesMenu.test.tsx`]
- depends-on: []

**RED** (동반 테스트).
- 파일: `apps/web/src/components/issue/__tests__/RecentIssuesMenu.test.tsx`
- 테스트: `useSidebarCollapsed.setState({ collapsed: true })` + `matchMedia('(max-width: 767.98px)')` 를 모바일로 물린 상태에서 최근 이슈 목록이 **렌더된다**
- 실패 메시지 (예상): `if (collapsed) return null` 때문에 컴포넌트가 null → 목록 조회 실패

**GREEN**.
- `useSidebarCollapsed` 직접 소비를 `useSidebarRailCollapsed` 로 교체 (형제 3곳과 동일)
- 근거 — 형제 `Sidebar`·`ProjectTree`·`FavoritesMenu` 는 전부 이주했고 이것만 남았다.
  `use-sidebar-drawer.ts:62` KDoc 이 「라벨을 감출지 정할 때 `collapsed` 를 직접 읽지 마라」를 이미 명시한다

**REFACTOR**.
- import 정리 · 이주 근거 한 줄 주석

**검증**.
- `pnpm --filter web test -- RecentIssuesMenu`
- 기존 E2E: 좁은 폭을 쓰는 스펙 없음 (이 컴포넌트는 사이드바 전용) — 눈확인이 주 증인
- 눈확인: 390px, 데스크톱에서 사이드바를 접은 뒤 좁혀 드로어를 열면 「최근 이슈」가 보인다 — 라이트/다크

### Task 2. D5 — `ShellLayout` 이 토글 판정을 인라인 복제하지 않게 한다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/ShellLayout.tsx`, `apps/web/src/components/layout/__tests__/ShellLayout.test.tsx`]
- depends-on: []

**RED** (동반 테스트).
- 파일: `apps/web/src/components/layout/__tests__/ShellLayout.test.tsx`
- 테스트: 모바일 폭에서 `onToggleSidebar` 를 호출하면 `useSidebarDrawer.getState().open` 이 뒤집힌다 (데스크톱에서는 `collapsed` 가 뒤집힌다)
- 실패 메시지 (예상): 지금은 동작이 같아 green 이다 → **판정 소유권 자체를 재는 Task 3 이 진짜 RED 를 진다.** 이 테스트는 회귀 고정용

**GREEN**.
- `ShellLayout.tsx:63,92` 의 `toggleCollapsed` 셀렉터 + `isMobile ? toggleDrawer : toggleCollapsed` 삭제
- `useSidebarToggle()` 한 줄로 교체
- 근거 — `use-sidebar-drawer.ts:81` KDoc 이 「토글 지점이 셋(상단바·`[` 단축키·사이드바 하단)이라 판정을 각자 두면 하나만 고쳐지고 나머지가 조용히 썩는다 — 여기 한 곳에서만 고른다」고 선언한다. 셋 중 `[` 단축키만 그 계약 밖에 있었다

**REFACTOR**.
- `useSidebarCollapsed`·`useMediaQuery` import 가 이 파일에서 더 필요 없으면 제거

**검증**.
- `pnpm --filter web test -- ShellLayout`
- 기존 E2E: `apps/web/e2e/keyboard-shortcuts.spec.ts` · `apps/web/e2e/context-shortcuts.spec.ts` (`[` 단축키 경로)
- 눈확인: 1440px 에서 `[` 로 레일 접기/펼치기 · 390px 에서 `[` 로 드로어 여닫기 — 라이트/다크

### Task 3. D5·D6 재발 방지 — `useSidebarCollapsed` 직접 소비 허용목록 판별식

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/__tests__/sidebar-collapsed-consumer-allowlist.test.ts`]
- depends-on: [1, 2]

**RED**.
- 파일: `apps/web/src/hooks/__tests__/sidebar-collapsed-consumer-allowlist.test.ts`
- 테스트: `apps/web/src/**` 를 훑어 `use-sidebar-collapsed` 를 import 하는 **프로덕션 파일** 집합을 뽑고, 허용목록과 **차집합이 0** 인지 단언한다
- 허용목록 — `hooks/use-sidebar-collapsed.ts`(자신) · `hooks/use-sidebar-drawer.ts`(유일한 래퍼). 테스트 파일(`__tests__`·`*.test.*`)은 대상에서 제외한다
- 실패 메시지 (예상): Task 1·2 를 아직 안 했다면 `RecentIssuesMenu.tsx`·`ShellLayout.tsx` 가 차집합에 남는다

**GREEN**.
- Task 1·2 가 이미 닫는다. 이 task 는 판별식만 세운다

**REFACTOR**.
- 실패 메시지에 「`useSidebarRailCollapsed`/`useSidebarToggle` 을 대신 쓰라」는 처방을 적는다

**★ 비-공허 확인 (필수)**.
- 허용목록에서 `use-sidebar-drawer.ts` 를 **일부러 빼서** red 1회를 눈으로 본 뒤 되돌린다
- 근거 — 「가드 수정 시 표면을 없애면 판별자도 사라진다. 일부러 끊어 red 1회 확인」(`docs/rules/traps.md`)
- ★뮤테이션 확인은 **GREEN 선커밋 뒤에** 한다 — 미커밋 원복은 소실이다

**검증**: `pnpm --filter web test -- sidebar-collapsed-consumer-allowlist`

### Task 4. D4 — Esc 모순 주석 2곳을 사실로 교정한다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/ShellLayout.tsx`, `apps/web/src/components/layout/Sidebar.tsx`]
- depends-on: [2]

**RED** (동반 테스트 없음 — 주석 교정).
- 이 task 는 **문서 정합**이다. 판정은 Task 8 이 Esc 를 실제로 만들면 자동으로 참이 된다

**GREEN**.
- `ShellLayout.tsx` 백드롭 주석 — 「같은 닫기 동작을 접기 버튼과 **Esc** 가 제공한다」에서 Esc 를 뺀다 (Task 8 이 Esc 를 만들면 되살린다)
- `Sidebar.tsx` `inset-y-0` 주석 — 「백드롭/**Esc** 말고는 닫을 길이 없어진다」를 실제 경로로 고친다
- 근거 — 같은 파일 위쪽 주석이 「ADR D-2 판별식에 걸려 Esc 를 **포기했다**」고 적었다. `aria-hidden` 백드롭의 접근성 근거가 존재하지 않는 경로를 든 상태다

**REFACTOR**.
- 두 주석이 같은 사실을 가리키게 문구 통일

**🛑 함정**: ADR D-2 판별식은 **소스 텍스트**를 훑는다 — 주석에 전역 keydown 호출 문법을 예시로 적기만 해도 걸린다

**검증**.
- `pnpm --filter web test -- useKeyboardShortcuts` (ADR D-2 판별식 통과 확인)
- 눈확인: 없음 (주석)

### Task 5. D3 — 소스 가드 테스트 파일명을 실체에 맞춘다

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/config/WebSocketHandshakePathGuardTest.kt`]
- depends-on: []

**RED**: 없음 — rename. 기존 테스트 2건이 그대로 통과해야 한다

**GREEN**.
- `WebSocketHandshakePermitAllIntegrationTest.kt` → `WebSocketHandshakePathGuardTest.kt`, 클래스명 동시 변경
- 근거 — 내용이 `Files.readString` 으로 소스 텍스트를 읽는 가드다. `IntegrationTest` 접미사는 Testcontainers 통합 테스트를 뜻하는 저장소 관례와 어긋나 오독을 부른다

**REFACTOR**: KDoc 첫 줄을 파일명과 일치시킨다

**검증**: `./gradlew :modules:identity-access:test --tests '*WebSocketHandshakePathGuardTest'`

### Task 6. D2 — 인접 경로 401 판별식을 실제로 세운다

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/WebSocketHandshakePermitAllTest.kt`]
- depends-on: []

**RED**.
- 파일: `backend/modules/app/src/test/kotlin/com/bts/app/WebSocketHandshakePermitAllTest.kt`
- 테스트: `GET /wsx` 와 `GET /ws/anything` 이 **401** 이다 — permitAll 이 정확히 `/ws` 한 경로만 연다는 증명
- 실패 메시지 (예상): 지금은 그 케이스 자체가 없다(부재가 곧 결함)

**GREEN**.
- 두 케이스 추가. `WS_HANDSHAKE_PATH` 가 와일드카드로 넓어지면 이 단언이 red 가 된다

**REFACTOR**.
- KDoc 에 **왜 여기서는 401 이 공허하지 않은지**를 명시한다 — 인접 경로는 permitAll 대상이 아니라 필터가 자르는 401 만 가능하고, `/error` 경유 401 과 헷갈릴 여지가 없다. (`/ws` 자신에 대해서는 그 구분이 불가능해 400 을 양성 판별자로 쓴다는 기존 기록과 짝을 이룬다)

**🛑 함정**: Kotlin 블록 주석은 중첩된다 — KDoc 안에 `/ws` + 별 두 개를 문자 그대로 쓰면 파일 끝까지 삼켜 컴파일이 깨진다

**검증**: `./gradlew :modules:app:test --tests '*WebSocketHandshakePermitAllTest'`

### Task 7. D1 — `SecurityConfig` 주석이 실재하는 짝을 가리키게 한다

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`]
- depends-on: [5, 6]

**RED**: 없음 — 주석 교정. Task 6 이 만든 가드가 실재해야 이 주석이 참이 된다

**GREEN**.
- 「짝 판별식 = `WebSocketHandshakePermitAllIntegrationTest` (열림 1건 + 인접 경로 401 1건)」을
  실제 두 짝으로 교체 — ① `identity-access` `WebSocketHandshakePathGuardTest`(폭·매처 종류, 소스 텍스트) ② `:modules:app` `WebSocketHandshakePermitAllTest`(열림 400 양성 증명 + 인접 경로 401)
- 근거 — 현재 주석은 존재하지 않는 판별식을 있다고 선언한다(`invariant-satisfied-by-helptext-not-logic`)

**REFACTOR**: `WS_HANDSHAKE_PATH` KDoc 의 같은 서술도 함께 맞춘다

**🛑 함정**: Kotlin 블록 주석 중첩 (Task 6 과 동일)

**검증**: `./gradlew :modules:identity-access:test :modules:app:test ktlintCheck detekt`

### Task 8. D7 — 드로어를 키보드로도 닫을 수 있게 하고 포커스를 가둔다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/ShellLayout.tsx`, `apps/web/src/components/layout/Sidebar.tsx`, `apps/web/src/components/keyboard-shortcuts/context-shortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/context-shortcuts.test.ts`]
- depends-on: [4]

**RED** (동반 테스트).
- 드로어가 열린 상태에서 Esc 를 누르면 닫힌다
- 드로어가 열린 상태에서 `<aside>` 가 `aria-modal="true"` 이고 뒤 콘텐츠가 `inert`(또는 동등 처리)다

**GREEN**.
- Esc 는 **새 전역 리스너를 만들지 않는다.** `ShortcutContext` 에 이미 `'app-shell'` 이 있으므로 `CONTEXT_SHORTCUTS` 에 항목으로 **등록**한다 — 이것이 ADR D-2 가 지정한 유일한 정식 경로이고, `ShellLayout.tsx` 주석이 스스로 지목한 길이다
- 포커스 가둠 — 드로어가 열린 동안 `<aside aria-modal="true">` + 본문 비활성

**REFACTOR**.
- Task 4 에서 뺐던 「Esc」를 주석에 되살린다 — 이제 참이다

**🛑 함정**: 즉사 계약 「단축키 레지스트리 동결」은 **`SHORTCUTS` 5종**에 대한 것이다. `CONTEXT_SHORTCUTS` 는 별도 레지스트리라 추가가 허용된다. `shortcuts.test.ts` 의 `toHaveLength(5)` 를 건드리지 말 것

**검증**.
- `pnpm --filter web test -- context-shortcuts ShellLayout Sidebar`
- 기존 E2E: `apps/web/e2e/context-shortcuts.spec.ts` · `apps/web/e2e/keyboard-shortcuts.spec.ts`
- 눈확인: 390px 에서 드로어를 열고 Esc · 탭 순회가 뒤 콘텐츠로 새지 않는지 — 라이트/다크

### Task 9. 문서 전수 동기화

**메타**.
- agent: `frontend-engineer`
- files: [`.claude/STATE.md`, `docs/design/jira-parity-roadmap.md`, `TODOS.md`, `docs/INDEX.md`, `docs/INDEX-fr.md`, `docs/INDEX-recent.md`]
- depends-on: [8]

**RED**: 없음 — 문서. 판정은 `node scripts/build-doc-index.mjs --check` 와 `bash scripts/verify-master-plan.sh`

**GREEN**.
- `.claude/STATE.md` — 「main 워킹트리에 다른 세션 미커밋 14+1 파일, 건드리지 말 것」 문단이 **STALE**. 이 PR 이 그 파일들의 주인임을 반영
- `docs/design/jira-parity-roadmap.md:82` F24 행 — **게이트 1 결정 반영**. 로드맵은 수단을 「Sheet」로 적었는데 구현은 `max-md:` 오프캔버스다
- `TODOS.md` — 이번 PR 에서 안 닫는 것 등재.
  ① F21 전수 적용(컨테이너 관례 이원화 — `PageLayout` 5라우트 vs 좌측 `p-8` 다수)
  ② `ProjectTree` 2단 들여쓰기 어긋남(depth1 리프 x=41 · depth1 그룹 x=61 · depth2 x=66 — 반쪽 수정 시 위계 악화)
  ③ polish 5건 — `설정/환경설정` select 4개 폭 제각각(192/256/160/192) · `가져오기`·`프로필` 네이티브 file input 무스타일 · `컴포넌트/커스텀필드/이슈템플릿` H1 과 카드 제목 문구 중복 · `프로젝트 리드` 부제와 카드 설명 동일 문장
  ④ **classify-task 오분류** — 제목에 「스키마」가 있으면 `type=migration`·`agent=db-engineer` 가 된다(그 단어만 빼면 `ui`/`frontend-engineer`). #387 이 닫았다는 부채 44·33·45 와 같은 양식
- `node scripts/build-doc-index.mjs` 재실행

**REFACTOR**: 없음

**검증**: `node scripts/build-doc-index.mjs --check` · `bash scripts/verify-master-plan.sh`

### Task 10. 전량 재검증 + 뮤테이션 + 즉사 계약 잔여 확인

**메타**.
- agent: `qa-engineer`
- files: []
- depends-on: [1, 2, 3, 4, 5, 6, 7, 8, 9]

**RED**: 없음 — 검증 전용

**GREEN**.
1. `pnpm verify` (lint + typecheck + test + build)
2. `pnpm --filter web test:e2e` **전량** — 좁은 폭 3스펙만 돌았다. 관리 메뉴 4스펙(`notification-policies`·`audit-logs`·`global-permissions`·`webhook`)과 `settings-admin-hub.spec.ts` 가 미실행이다
3. `./gradlew :modules:identity-access:test :modules:app:test :modules:notification:test ktlintCheck detekt`
4. `pnpm test:workflow`
5. **`/ws` permitAll 뮤테이션 재확인** — 그 줄을 끊으면 `:modules:app` 테스트가 red. **GREEN 선커밋 뒤에** 할 것
6. **즉사 계약 「h1 단 하나」** — `BoardPage` 신규 h1 이 그 라우트에 h1 을 2개 만들지 않는지 실측

**REFACTOR**: 없음

**검증**: 위 6항목 전부 통과 + 결과를 게이트 2 요약에 그대로 싣는다

### 🛑 게이트 1 결과 (2026-08-24 · Maxi 승인) — task 개정

| 결정 | 답 | task 반영 |
|---|---|---|
| **D3** BLOCKER-D1 처방 | **A** — `AccountMenu` 에 「모든 설정」 1줄 추가 | **Task 11 신설** |
| **D4** D7 범위 | **A** — Esc + 포커스 시작·복귀. 완전 트랩은 별건 | **Task 8 개정** |
| **D5** `setAllowedOrigins` | **A** — 명시한다 | **Task 12 신설** |
| **F24** 완주 선언 | 두 렌즈 일치 — **선언하지 않는다** | Task 9 에 로드맵 수단 기술 교정 추가 |

#### 개정 1 — Task 1 (D6) 은 **양방향**을 단언한다

`TODOS.md:69` 는 접힘 레일의 「최근 항목」 미노출을 **의도된 제품 결정**으로 등재하고
「⚠️ 결함으로 오인해 조용히 되돌리지 말 것」을 명시한다. 수정은 그 결정을 되돌리는 것이 아니라
**근거가 성립하는 구간(64px 레일)으로 적용 범위를 좁히는 것**이다.
→ 동반 테스트는 반드시 둘 다 단언한다.
① 데스크톱 접힘 → **여전히 미렌더**(결정 보존) ② 모바일 드로어 → **렌더**(회귀 수정)

#### 개정 2 — Task 3 판별식은 파일 목록을 **도출**한다

기존 설계는 `FILES_USING_MOBILE_VARIANT` 를 3개 하드코딩했는데, `apps/web/src` 의 실제
`max-*:` 사용 프로덕션 파일은 **4개**다(`AccountMenu.tsx:102`, 이 PR 이 추가). 목록과 실제가
서로를 검사하지 않는다 — 판별식이 스스로 방어한다고 선언한 양식에 자기가 걸렸다.
→ `apps/web/src/**` 를 훑어 `max-*:` 사용 파일을 **도출**한 뒤 그 집합에 `max-md` 외 변형이
없는지 단언한다. 신규 파일이 자동 편입된다.
→ 같은 task 의 `useSidebarCollapsed` 허용목록 판별식도 같은 원칙으로 도출식을 유지한다.
→ **Task 3 을 드롭하면 Task 2 도 함께 드롭한다** — Task 2 의 판정을 지는 것은 Task 3 뿐이다.

#### 개정 3 — Task 8 (D7) 범위 확정

| 이번 PR | 별건 (TODOS) |
|---|---|
| Esc 로 닫기 — `CONTEXT_SHORTCUTS` 에 `app-shell` 항목 **등록**(ADR D-2 정식 경로) | `aria-modal="true"` |
| 드로어가 열릴 때 첫 포커스를 드로어로 이동 | 뒤 콘텐츠 `inert` (완전한 포커스 트랩) |
| 드로어가 닫힐 때 포커스를 토글 버튼으로 복귀 | |

🛑 즉사 계약 「단축키 레지스트리 동결」은 **`SHORTCUTS` 5종**에 대한 것이다.
`CONTEXT_SHORTCUTS` 는 별도 레지스트리라 추가가 허용된다. `shortcuts.test.ts` 의
`toHaveLength(5)` 를 건드리지 말 것.

#### 개정 4 — Task 9 문서 동기화 정정

- **`TODOS.md` ④ classify 오분류는 신규 항목으로 만들지 않는다.** `TODOS.md:1080`
  「`classify-task` 가 타입 이름을 언급한 제목을 그 타입의 작업으로 읽는다」에 **실측을 붙인다** —
  「스키마」→`type=migration`·`agent=db-engineer`(그 단어만 빼면 `ui`/`frontend-engineer`).
  타입명이 아닌 **도메인 단어**라 트리거 축은 다르지만 그 항목의 처방 후보 ㉯(경로 신호가 type
  신호를 이긴다)가 둘 다 덮는다.
- **로드맵 `jira-parity-roadmap.md:82`** — F24 행의 수단 기술 「모바일 드로어 (Sheet)」를
  **「모바일 드로어 (오프캔버스 + 백드롭)」**로 고친다. 완주 마킹은 하지 않는다.
- **`TODOS.md` 신규 등재 2건 추가** — ① 드로어 완전 포커스 트랩(`aria-modal` + `inert`)
  ② `FieldPermissionController.listRules` 의 `ResponseEntity<*>` 타입 소거 및 응답 규약 이원화
  (`DataResponse<T>` envelope vs 맨 리스트) — 프로덕션 장애의 근본 원인이었다.

### Task 11. BLOCKER-D1 — 모바일에서 「설정」 허브에 갈 길을 되살린다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/AccountMenu.tsx`, `apps/web/src/components/layout/__tests__/AccountMenu.test.tsx`]
- depends-on: []

**RED** (동반 테스트).
- 계정 메뉴를 열면 `/settings` 로 가는 항목이 있다
- ★**도달성 판별식을 함께 세운다** — `settings.index.tsx` 가 링크하는 하위 경로 집합과,
  「계정 메뉴 항목 ∪ `/settings` 허브 링크」 집합의 **차집합이 0** 인지 단언한다.
  이번 결함의 정체가 「두 목록이 서로를 검사하지 않는다」였으므로 수정만으로는 재발한다

**GREEN**.
- `AccountMenu` 의 설정 계열 그룹 맨 앞(또는 맨 뒤)에 `<Link to="/settings">모든 설정</Link>`
  `DropdownMenuItem` 1줄 추가

**REFACTOR**.
- `TopBar.tsx:193` 의 `max-md:hidden` 주석을 사실로 교정 — 현재 「같은 진입점이 계정 메뉴와
  사이드바에 있다」는 **거짓이었다**. 이제 계정 메뉴에 실재하므로 그 문장이 참이 되지만,
  「사이드바에 있다」는 여전히 거짓이다(`ADMIN_NAV_LINKS` 는 `isAdmin` 전용) — 지운다

**검증**.
- `pnpm --filter web test -- AccountMenu`
- 기존 E2E: `apps/web/e2e/settings-admin-hub.spec.ts` (기본 1280px — `banner` 안 톱니 단언 생존 확인)
- 눈확인: 390px 에서 계정 메뉴 → 「모든 설정」 → `/settings/password` 도달 — 라이트/다크

### Task 12. `/ws` 핸드셰이크에 허용 출처를 명시한다

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/config/WebSocketConfig.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/config/WebSocketConfigTest.kt`]
- depends-on: []

**RED**.
- `registerStompEndpoints` 가 등록한 엔드포인트에 허용 출처가 **설정돼 있다**는 단언
  (기본값 의존이 아니라 소스에 쓰인 값인지)
- 실패 메시지 (예상): 현재는 `setAllowedOrigins` 호출 자체가 없다

**GREEN**.
- `registry.addEndpoint(WS_ENDPOINT).setAllowedOrigins(...)` — 오리진 목록은 **설정으로 뺀다**
  (로컬 `http://localhost:5173` · prod 도메인). 하드코딩하지 말 것

**REFACTOR**.
- KDoc 에 근거 — permitAll 로 HTTP 계층 방어선이 하나 줄었으므로 남은 방어선을 프레임워크
  기본값에 맡기지 않는다. CSWSH 자체는 CONNECT frame Bearer 라 지금도 비악용이지만,
  Spring 버전 업그레이드가 기본값을 바꿔도 동작이 안 바뀌게 못박는다

**🛑 이탈 — 「한 PR = 한 BC」.**
이 task 는 `notification` BC 의 **프로덕션 코드**를 건드린다. 지금까지 이 PR 은 그 BC 를
읽기만 했다(0줄). 같은 FR-NT-02 결함의 짝이라 분리하면 반쪽 수정이 되므로 여기서 닫되,
**게이트 2 요약에 이탈로 싣는다**(PR #395 선례와 같은 처리).

**🛑 함정**: 로컬 개발 오리진(5173)을 빼먹으면 로컬 WebSocket 이 조용히 죽는다. 설정 반영을
로컬에서 실제로 확인할 것.

**검증**.
- `./gradlew :modules:notification:test --tests '*WebSocketConfigTest'`
- `./gradlew :modules:notification:test :modules:app:test ktlintCheck detekt`
- 로컬 `pnpm dev` 로 WebSocket 핸드셰이크가 여전히 붙는지 눈확인

## Plan 메타

| 항목 | 값 |
|---|---|
| task 수 | **12** (게이트 1 에서 11·12 신설) |
| 예상 wave | 5 — ①`1·2·5·6·11·12` ②`3·4·7` ③`8` ④`9` ⑤`10` |
| 구현 규율 | ui 시각 검증 트랙 (T2) — 프론트 task 는 동반 테스트 + 기존 E2E 목록 + 눈확인 필수. 백엔드 보안 task(6·7)는 정식 TDD red-first |
| 추가 검증 | tsc · eslint · vitest · playwright(전량) · ktlintCheck · detekt · `pnpm test:workflow` · doc-index `--check` · verify-master-plan |
| 게이트 1 결정 대기 | ① F24 를 완주로 선언할지(수단이 로드맵 기술과 다름) ② `setAllowedOrigins` 를 명시할지 ③ D7 을 이번 PR 에서 닫을지 TODOS 로 미룰지 |

## 리뷰 결과

### 렌즈 1 — `/plan-eng-review` (2026-08-24)

> 절차 메모. gstack 리뷰 섹션은 「이슈 1건당 AskUserQuestion 1회 + STOP」을 요구하나,
> 호출자 `/bts-review-plan` 이 「각 리뷰는 `## 리뷰 결과` 에 append · BLOCKER 는 합산해 한 번에
> 판정」을 계약으로 건다. 호출자 계약을 따랐고 판정은 게이트 1 에서 합산한다.

#### Step 0 — 범위 도전

| 점검 | 결과 |
|---|---|
| 기존 코드가 이미 푸는가 | D5·D6 은 `use-sidebar-drawer.ts` 의 `useSidebarToggle`·`useSidebarRailCollapsed` 가 **이미 답을 갖고 있다.** 소비처 2곳이 안 따라왔을 뿐이라 신규 추상화 0 |
| 최소 변경 집합 | Task 8(D7 포커스 트랩)이 유일한 범위 확장 후보. 아래 권고 ③ 참조 |
| 복잡도 (8파일/2클래스 초과 시 스멜) | **트리거됨** — 10 task · 15파일. 다만 신규 클래스·서비스 0, 전부 기존 표면의 결함 수정이다. 확장은 Task 8 뿐 |
| TODOS 교차 참조 | **2건 적중 — 아래 「TODOS 정정」** |
| 완전성 | 판별식 2개(Task 3 허용목록 · Task 6 인접 경로 401)를 세워 재발을 막는다. 지름길 아님 |
| 배포 | 신규 아티팩트 0. 다만 **#397·#398·이 PR 모두 미배포** — 머지가 배포를 트리거하지 않는다(`workflow_dispatch` 전용) |

#### ★ TODOS 정정 2건 — plan 의 근거를 바꾼다

**정정 1 — D6 은 「신규 등재」가 아니라 「기존 결정의 경계 밖」이다.**
`TODOS.md:69` 에 이미 있고 **⚠️ 「이건 결함이 아니라 결정이다. 조용히 되돌리지 말 것」** 경고가 붙어 있다
(FR-UX-08 PR-B `/plan-design-review` D-B, 2026-07-31 Maxi 확정). 근거는 **64px 레일**에서
첫 글자 뱃지가 `ATLAS-12`·`ATLAS-13` 둘 다 `A` 라 5칸을 먹고 구분은 0 이라는 것.
그런데 **모바일 드로어는 264px 전체 폭**이라 그 근거가 하나도 성립하지 않는다.
→ D6 은 이 PR 이 만든 신규 회귀가 맞다. **단 Task 1 은 데스크톱 접힘 동작(`return null`)을
그대로 두어야 한다.** `useSidebarRailCollapsed` 가 모바일에서 false 를 주므로 이주만으로 둘 다 만족한다.
**Task 1 의 동반 테스트는 두 방향을 다 단언할 것** — ① 데스크톱 접힘 → 여전히 미렌더(결정 보존)
② 모바일 드로어 → 렌더(회귀 수정).

**정정 2 — classify 오분류는 이미 등재돼 있다. Task 9 ④ 는 중복이다.**
`TODOS.md:1080` 「`classify-task` 가 타입 이름을 언급한 제목을 그 타입의 작업으로 읽는다」.
내 실측(「스키마」→`type=migration`·`agent=db-engineer`)은 **타입명이 아니라 도메인 단어**라
트리거 축이 다르지만 **같은 항목의 새 실측**이다(그 항목의 처방 후보 ㉯ 「경로 신호가 type 신호와
충돌하면 경로가 이긴다」가 둘 다 덮는다). → **신규 항목을 만들지 말고 그 항목에 실측을 붙일 것.**

#### 1. 아키텍처

**[P1] (confidence 9/10) `backend/modules/identity-access/.../FieldPermissionController.kt:75` — `ResponseEntity<*>` 가 응답 타입을 지워 계약 분기를 보이지 않게 만든다.**

```kotlin
): ResponseEntity<*> {
    …
    ResponseEntity.ok(rules)
```

형제들은 타입을 실어 나른다 — `ProjectQueryController.list` 는 `ResponseEntity<DataResponse<List<ProjectResponse>>>`,
`SearchController.search` 는 `ResponseEntity<AqlSearchPageResponse<AqlSearchHit>>`.
**저장소에 응답 규약이 둘 있고**(`DataResponse<T>` envelope vs 맨 리스트) 이 컨트롤러만 후자인데,
`<*>` 라 컴파일러도 OpenAPI 도 어느 쪽인지 못 본다. 프론트가 envelope 을 가정한 건 합리적 추론이었다.
**프로덕션 장애의 근본 원인이 클라이언트 스키마가 아니라 여기다.**
→ 이번 PR 의 클라이언트 수정은 옳다(장애를 오늘 푼다). 백엔드 규약 수렴은 **다른 소비처를 깨는 API
변경**이라 별건 PR + TODOS 등재가 맞다.

**[통과] 형제 래퍼 오해 전수 스캔.** `apps/web/src/api/*.ts` 에서 `data: z.array(...)` 를 쓰는 곳은
`projects.ts:30` · `search.ts:80` 둘뿐이고 **둘 다 백엔드와 일치한다**(위 인용). 같은 결함의 다른 표면 0건.

**[통과] 보안 — `/ws` permitAll.** learnings 2026-07-15 규칙이 놓치는 각도까지 확인했다 —
SockJS 미사용이라 하위 폴백 경로 0 · `@RequestBody` 0 · 백프레셔 기설정 · CSWSH 는 인증이
CONNECT frame Bearer 라 비악용. 남은 1건은 아래 P2.

#### 2. 코드 품질

**[P1] (confidence 9/10) `apps/web/src/hooks/__tests__/sidebar-breakpoint-alignment.test.ts:16-20` — 판별식이 자기가 방어한다는 양식에 걸렸다.**

```ts
const FILES_USING_MOBILE_VARIANT = [
  'components/layout/Sidebar.tsx',
  'components/layout/TopBar.tsx',
  'components/layout/ShellLayout.tsx',
]
```

`apps/web/src` 에서 `max-*:` 변형을 쓰는 **프로덕션 파일은 4개**다 — 위 셋 + `components/layout/AccountMenu.tsx:102`
(`max-md:hidden`, **이 PR 이 추가**). 하드코딩 목록과 실제 집합이 서로를 검사하지 않는다.
오늘은 경계가 같아 무증상이지만, 훗날 `AccountMenu` 에 `max-sm:` 이 들어가면 「경계가 둘로 갈렸다」
검사가 **그 파일을 아예 안 본다**. 이 파일 KDoc 이 스스로 인용하는 `two-lists-never-check-each-other` 다.
→ **처방**. 목록을 하드코딩하지 말고 `apps/web/src/**` 를 훑어 `max-*:` 사용 파일을 **도출**한 뒤
그 집합에 `max-md` 외 변형이 없는지 단언한다. 도출로 바꾸면 신규 파일이 자동 편입된다.

**[통과] 줄수 래칫.** `lint-ratchet-baseline.ts` 상향 367→372 에 사유·대안 기각 근거·게이트 2 고지가
주석으로 남아 있다. 선례(288→289) 형식과 일치. 되돌릴 항목 아님.

**[통과] 즉사 계약 「h1 단 하나」.** `projects.$projectKey.board.tsx` 의 h1 은 **파일 전체에 1개**(`:425`).
형제 뷰와 같은 자리다. 통과.

#### 3. 테스트

**[P1] Task 1 의 동반 테스트가 한 방향만 재면 결정을 되돌린다.** 위 「TODOS 정정 1」 참조 — 양방향 단언 필수.

**[P2] Task 2(D5)의 RED 가 진짜 red 가 아니다.** plan 이 이미 「지금은 동작이 같아 green」이라고 적었다.
정직한 서술이지만, 그렇다면 **판정을 지는 것은 Task 3 하나뿐**이다. Task 3 이 없으면 Task 2 는
검증되지 않는 리팩터다. → Task 2 와 Task 3 의 순서 의존을 plan 이 이미 `depends-on: [1,2]` 로
걸어 뒀으므로 구조는 맞다. **Task 3 을 드롭하면 Task 2 도 함께 드롭해야 한다**를 명시할 것.

**[통과] Task 3 비-공허 확인이 설계에 들어 있다.** 허용목록에서 `use-sidebar-drawer.ts` 를 빼서
red 1회를 보는 절차 + 「GREEN 선커밋 뒤에」 조건까지 적혀 있다.

**[통과] E2E 좁은 폭 23/23.** 즉사 계약 「모바일 드로어는 모바일 폭에서만」이 실측으로 지켜졌다.

#### 4. 성능

**[통과]** 신규 네트워크 호출 0 · 신규 쿼리 0. `RecentIssuesMenu` 이주(Task 1)는 모바일 드로어가
열렸을 때 최근 이슈 제목 조회를 **다시 켠다** — `issueQueryKey` 캐시 공유라 세션 중 대부분 적중하고,
드로어는 사용자가 연 순간에만 열리므로 유휴 비용 0.

#### P2 잔여

| # | 항목 | conf |
|---|---|---|
| P2 | `setAllowedOrigins` 미설정 — Spring 기본값(동일 출처) 의존. `/ws` 가 이제 permitAll 이라 방어선이 하나 줄었고, 기본값은 버전 업그레이드 때 조용히 바뀔 수 있다 | 7/10 |
| P2 | D1 — `SecurityConfig` 주석이 없는 판별식 지목 | 10/10 |
| P2 | D4 — Esc 모순 주석 2곳 | 10/10 |
| P2 | D7 — 드로어에 포커스 트랩·`aria-modal`·Esc 없음 | 8/10 |
| P2 | D3 — 파일명 오도 | 9/10 |
| P2 | #397·#398·이 PR 모두 **미배포**. 머지가 배포를 트리거하지 않는다(`workflow_dispatch` 전용) — `/ws` 수정은 배포돼야 실제로 낫는다 | 9/10 |

#### 게이트 1 결정 3건 — eng 권고

| # | 결정 | 권고 |
|---|---|---|
| ① | F24 를 완주로 선언할지 | **안 한다.** D6 이 열려 있고 수단이 로드맵 기술(「Sheet」)과 다르다. D6 을 닫은 뒤 로드맵 표의 수단 기술을 실제 수단으로 고쳐 적고 완주 처리하는 것이 순서다 |
| ② | `setAllowedOrigins` 명시 | **명시한다.** 한 줄이고, 기본값 의존을 없앤다. permitAll 로 방어선이 하나 줄어든 경로에 암묵 기본값을 남길 이유가 없다 |
| ③ | D7 을 이번 PR 에서 닫을지 | **쪼갠다.** Esc 는 닫는다 — `CONTEXT_SHORTCUTS` 에 `app-shell` 항목 등록이라 싸고, D4 의 거짓 주석이 같이 참이 된다. **포커스 트랩·`aria-modal` 은 별건** — 셸 전역 포커스 동작을 바꿔 e2e 파장이 크고, 이 PR 의 성격(레이아웃 결함 수정)을 넘는다. TODOS 등재 |

**BLOCKER: 0 · P1: 3 · P2: 6**
P1 3건(판별식 하드코딩 · Task 1 양방향 단언 · `ResponseEntity<*>`)은 모두 plan 수정으로 흡수 가능하다.

### 렌즈 2 — `/plan-design-review` (2026-08-24)

> 절차 메모. Step 0.5 목업 생성을 **의도적으로 건너뛰었다.** 이 리뷰는 만들 UI 가 아니라
> **이미 화면에 나간 결정**을 판정한다 — 변형 목업은 증거가 아니다. 프로젝트 계약 §6 이
> 지정한 증거는 실브라우저 눈확인 + 기존 E2E 이고, 좁은 폭 E2E 23/23 은 이미 확보돼 있다.

#### 초기 평가 — 디자인 완성도 7/10

이미 구현된 결정들이라 「명세 부재」로 감점할 자리가 적다. 감점은 두 곳이다 —
**모바일에서 사라진 진입점의 대체 경로를 실측하지 않았고**(아래 BLOCKER), **드로어의 키보드
모델이 정의돼 있지 않다**(D7). 10/10 은 그 둘이 닫힌 상태다.

#### 🛑 BLOCKER-D1 — 모바일에서 「설정」 허브에 갈 수 없다

**근거 (실측).**

```tsx
// apps/web/src/components/layout/TopBar.tsx:193
<Link to="/settings" className="rounded-md p-1.5 hover:bg-accent max-md:hidden" aria-label="설정">
```

`apps/web/src` 전체에서 `/settings` **루트**로 가는 링크는 **이 한 줄뿐**이다. 이 PR 이 거기에
`max-md:hidden`(display:none)을 걸었다.

구현 주석은 「같은 진입점이 계정 메뉴와 사이드바에 있다」를 근거로 들었지만 **사실이 아니다.**
계정 메뉴(`AccountMenu.tsx:118-137`)는 **하위 7개**로 직접 간다 —
`profile`·`preferences`·`keymap`·`calendar`·`slack`·`mfa`·`pats`.
`/settings` 허브(`settings.index.tsx:50-110`)는 **11개**를 링크한다.

**차집합 4개가 모바일에서 도달 불가가 된다.**

| 소실 경로 | 성격 |
|---|---|
| `/settings/password` | **보안** — 비밀번호를 바꿀 수 없다 |
| `/settings/sessions` | **보안** — 세션을 끊을 수 없다 |
| `/settings/notifications` | 알림 설정 |
| `/settings/account-links` | 계정 연결 |

사이드바도 대체가 못 된다 — `MAIN_NAV_LINKS` 는 `/issues`·`/dashboards`·`/calendar` 뿐이고
`ADMIN_NAV_LINKS` 는 `isAdmin` 전용이다. **비관리자 모바일 사용자는 비밀번호를 바꿀 길이 없다.**

굿윌 저수지 관점에서 최악의 형태다 — 사용자가 원하는 것을 감추고, 그것도 보안 기능을 감춘다.
프로덕션 UI 결함을 고치는 PR 이 새 결함을 만든다.

**처방 (권고 ㉯).**

| 안 | 내용 | 판정 |
|---|---|---|
| ㉮ | `max-md:hidden` 제거, 톱니 유지 | 헤더 공간 문제를 되살린다. 다만 프로젝트 전환기를 이미 감춰 자리가 났으므로 재측정 가치는 있다 |
| **㉯** | **계정 메뉴에 「모든 설정」 항목 1줄 추가 → `/settings`** | **권고.** 계정 메뉴는 이미 설정 계열을 모아둔 자리이고 드롭다운이라 390px 폭 제약을 안 받는다. 한 줄이며 데스크톱에도 이득(허브 진입 2곳) |
| ㉰ | 사이드바 드로어에 설정 링크 추가 | 드로어는 「탐색」 자리라 계정/설정 계열과 성격이 다르다. 위계가 흐려진다 |

#### 판정 2 — `hidden` vs `sr-only` 처리 갈림은 **규칙이 일관적이다**

| 대상 | 처리 | 규칙 |
|---|---|---|
| 워드마크 「Atlas」 | `max-md:sr-only` | 같은 컨트롤(홈 링크)이 계속 필요 → 이름 보존 |
| 「만들기」 라벨 | `max-md:sr-only` | 같은 버튼이 계속 필요 → 이름 보존 |
| 프로젝트 전환기 | `max-md:hidden` | 기능이 드로어 `ProjectTree` 로 도달 가능 → 중복 제거 |
| 계정명 | `max-md:hidden` | `aria-hidden` 래퍼 안이라 이름은 트리거 `aria-label` 이 쥔다 → 소멸 무해 |
| 설정 톱니 | `max-md:hidden` | **규칙의 전제(「다른 경로가 있다」)를 만족하지 않는다** → BLOCKER-D1 |

규칙 자체는 옳다. 한 항목이 전제를 검증하지 않고 규칙을 적용했다.

**프로젝트 전환기 감춤은 승인.** 대체 경로가 실재하고(드로어 `ProjectTree`) 1탭 → 2탭 증가는
모바일 헤더 예산상 정당한 교환이다. Jira Cloud 모바일도 프로젝트 전환을 사이드 시트로 민다.

#### 판정 3 — 「최근 이슈」 수정의 전제는 **맞다**

`TODOS.md:69` 의 결정(FR-UX-08 PR-B D-B, 2026-07-31 Maxi 확정)은 **64px 레일**에서
첫 글자 뱃지가 `ATLAS-12`·`ATLAS-13` 둘 다 `A` 라 「5칸을 먹으면서 구분은 0」이라는 근거로
섹션 전체 미렌더를 택했다. **264px 드로어는 라벨 전체를 그린다** — 뱃지 축약이 아예 일어나지
않으므로 그 근거가 성립하지 않는다. 수정은 결정을 되돌리는 것이 아니라 **결정의 적용 범위를
그 근거가 성립하는 구간으로 좁히는 것**이다. 디자인 관점에서 승인.

★단 Task 1 의 동반 테스트는 **데스크톱 접힘에서 여전히 미렌더**임을 함께 단언해야 한다.
그래야 이 문서가 「조용히 되돌리지 말 것」이라 경고한 사고를 구조적으로 막는다.

#### 판정 4 — 드로어의 키보드 모델 (D7)

**출시 가능한 상태가 아니다.** 백드롭이 본문을 시각적으로 덮는 순간 그 UI 는 **모달로 읽힌다.**
포인터 사용자에게는 모달인데 키보드 사용자에게는 아니다 — 탭이 가려진 콘텐츠로 들어가고,
그 콘텐츠는 백드롭에 덮여 **보이지 않는 채로 포커스만 이동한다.** 「보이지 않는 곳으로 포커스가
사라진다」는 이 PR 이 `max-md:invisible` 로 이미 한 번 막은 결함과 **같은 형태**다
(`Sidebar.tsx` 주석이 그 이유를 정확히 적어놨다). 한쪽만 막았다.

`aria-hidden` 백드롭 주석의 근거는 거짓이다(Esc 미구현). 다만 **결론은 옳다** — 백드롭에
`role`·이름을 주지 않는 것이 맞다. 근거만 고치면 된다.

**디자인 권고 — Esc 와 포커스 가둠을 쪼개는 것에 반대한다.** 둘은 하나의 결함이다.
Esc 만 넣으면 「닫는 길이 늘었다」일 뿐 「보이지 않는 곳으로 포커스가 간다」는 그대로다.
다만 `aria-modal` + 본문 비활성은 셸 전역 동작이라 파장이 크다는 eng 지적도 맞다.
→ **절충 권고**. 이번 PR 에서 **Esc + 드로어 열림 시 첫 포커스 이동 + 닫힘 시 포커스 복귀**까지
넣는다(포커스의 시작·끝을 정의하면 「어디로 갔는지 모른다」는 사라진다). **완전한 포커스 트랩
(`aria-modal` + 뒤 콘텐츠 `inert`)만 별건**으로 뺀다.

#### 판정 5 — F24 완주 선언

**완주로 선언하지 않는다. eng 권고에 동의하되 이유가 하나 더 있다.**
로드맵의 「Sheet」는 **수단 기술**이고, 계약 §4 재사용 자산 레지스트리에 `sheet` 항목이 없다
(`popover.tsx` 만 있다). 즉 로드맵이 존재하지 않는 자산을 수단으로 적어둔 상태였고,
`max-md:` 오프캔버스는 **자산을 새로 만들지 않은 정당한 선택**이다(계약 §4 「만들기 전에 소비처를
grep」의 정신에 맞는다). → 로드맵 표의 F24 행을 **「모바일 드로어 (오프캔버스 + 백드롭)」**로
고쳐 적고, BLOCKER-D1 · D6 · D7 이 닫힌 뒤 완주 처리하는 것이 맞다.

#### 판정 6 — 미수정으로 남긴 것

| 항목 | 판정 |
|---|---|
| `ProjectTree` 2단 들여쓰기 | **미수정 승인.** 「반쪽만 고치면 위계가 더 나빠진다」가 옳다 — depth1 리프에 셰브론 자리를 주면 depth2 와 5px 차이가 되어 **깊이 신호가 소멸**한다. 위계 재설계는 별건 |
| polish 5건 | **미수정 승인.** 전부 시각 일관성 항목이고 사용자를 막는 것이 없다. 단 「`프로젝트 리드` 부제와 카드 설명이 완전 동일 문장」은 중복 텍스트 = 스캔 방해라 다음 UI PR 의 우선순위로 |
| F21 전수 적용 | **미수정 승인.** 컨테이너 관례가 둘로 갈린 것은 **결정 사항이지 결함이 아니다.** 48 라우트를 한 방향으로 모으는 것은 별건이 맞고, 이번에 1개를 형제 11개에 맞춘 방향(다수에 소수를 맞춤)이 옳다 |

#### 차원별 평가

| 차원 | 점수 | 10/10 이 되려면 |
|---|---|---|
| 정보 위계 | 8/10 | `BoardPage` h1 추가·FilterBar 상단 정렬로 개선. 남은 건 F21 컨테이너 통일 |
| 상호작용 상태 | 6/10 | 드로어 열림·닫힘·폭 전환·라우트 이동은 정의됨. **키보드 모델(포커스 시작/끝·Esc)이 없다** |
| 접근성 | 5/10 | BLOCKER-D1(설정 도달 불가) + D7(포커스) 둘 다 닫히면 9/10 |
| 반응형 | 7/10 | 모바일이 「스택」이 아니라 의도적 설계다. 감점은 BLOCKER-D1 하나 |
| AI slop 위험 | 9/10 | 새 패턴을 만들지 않고 기존 관례·프리미티브를 소비했다. 카드 그리드·히어로 없음 |
| 서브트랙션 | 8/10 | 감춘 것 5개 중 4개가 정당. 하나(설정)가 전제 미검증 |
| 신뢰 | 6/10 | 비밀번호·세션 관리가 모바일에서 사라지는 것은 신뢰 손상이 크다. 닫으면 9/10 |

#### 게이트 1 결정 3건 — design 권고

| # | 결정 | 권고 |
|---|---|---|
| ① | F24 완주 선언 | **안 한다.** eng 와 동일. 추가로 로드맵의 수단 기술 「Sheet」를 결과 기술로 고쳐 적을 것 — 계약 §4 에 `sheet` 자산이 없어 로드맵이 없는 자산을 지목하고 있었다 |
| ② | `setAllowedOrigins` | 보안 사안 — 디자인 렌즈 해당 없음 |
| ③ | D7 범위 | **eng 의 「Esc 만」보다 넓게.** Esc + 포커스 시작/복귀까지 이번 PR. 완전한 포커스 트랩(`aria-modal` + `inert`)만 별건. 이유는 위 판정 4 |

**BLOCKER: 1 · P1: 1 · 승인: 6**

### 렌즈 3 — `/plan-ceo-review` (2026-08-24)

> **왜 뒤늦게 발행됐나.** `DATA.md §1-5` 는 「Spring Security 필터 체인 변경은 plan-eng-review +
> **plan-ceo-review** 필수」를 절대 규칙으로 건다. `/bts-review-plan` 의 렌즈 표는 `type` 으로
> 분기하는데 이 작업의 `type` 이 `ui` 라 ceo 렌즈가 발행되지 않았다 — **규칙은 `type` 이 아니라
> 변경 대상이 켜는데**, 표가 그걸 못 본다. 독립 코드 리뷰가 BLOCKER B2 로 잡아 여기서 채운다.
>
> **절차 생략 명시.** 이 스킬의 Step 0 범위 의식(0C-bis 구현 대안 · 0D 확장 · 0F 모드 선택)은
> **돌리지 않았다.** 구현이 이미 끝났고 게이트 1 이 승인된 상태라 확장/축소 판정이 성립하지 않는다.
> 스킬의 기본값 규칙(「Bug fix or hotfix → HOLD SCOPE」)에 따라 **HOLD SCOPE** 로 정책·폭발 반경만 본다.

#### 판정 — **CONCERNS**

예외 자체는 정당하고 방어선도 촘촘하다. 다만 **R1 을 수용한 근거가 사실과 어긋나고**,
기각 대안 목록에 **예외를 아예 없애는 안 하나가 빠져 있다.**

#### C-1 (중대) — R1 수용 근거가 틀렸다. `/ws` 는 사내가 아니라 **인터넷에 열려 있다**

ADR 은 R1(동시 연결 상한 부재)을 「사내 1,000명 단일 호스트 규모에서 수용」이라 적었다.
그 문장은 **모집단이 사내로 묶여 있다**는 전제를 깔지만, 실측은 다르다.

| 층 | 상한 | 실측 |
|---|---|---|
| 엣지(nginx) | 없음 | `infra/` 전체에 `limit_conn`·`limit_req` **0건**. `nginx.conf:80` 의 `location /ws` 는 업그레이드 헤더만 붙여 그대로 프록시한다 |
| 앱(Tomcat) | 없음 | `application.yml` 에 `max-connections`·`threads.max`·`accept-count` **미설정** = 프레임워크 기본값 |
| 소켓 수명 | 30초 | `setTimeToFirstMessage` — 이번에 명시한 유일한 실질 상한 |

`bts.maxihan.com` 은 공개 도메인이다. 즉 **인증 없는 임의의 인터넷 클라이언트**가 소켓을 열고
30초를 붙들 수 있고, 그것을 막는 장치가 어느 층에도 없다.

**정직한 규모 산정.** 이 변경 **전에도** 익명 요청은 커넥션을 잡았다 — 다만 필터가 401 로 끊어
**밀리초** 단위였다. 지금은 같은 요청 하나가 최대 **30초**를 붙든다. 요청당 커넥션·초가
대략 **세 자릿수 배** 늘었다. 「새 공격면이 생겼다」가 아니라 **「기존 공격면의 단가가 급등했다」**가
정확한 표현이고, 상한이 없으면 그 단가가 곧 가용성이다.

**처방 (한 줄).** `nginx.conf` 의 `location /ws` 에 `limit_conn` 을 건다. 정상 사용자는 탭당
소켓 1개라 실사용을 막지 않는다. ADR 의 R1 문단도 「사내 1,000명」이 아니라 「공개 엔드포인트 ·
전 층 상한 부재」로 고쳐 적어야 한다 — **수용은 사실 위에서만 성립한다.**

#### C-2 — 기각 대안에 「예외를 안 만드는 안」이 없다

ADR §대안 4종은 전부 **WebSocket 을 전제**한 변형이다(쿼리 토큰 · 단명 티켓 · SockJS · 필터 검사).
빠진 것은 **롱폴링(`fetch` + `Authorization` 헤더)** 이다. `fetch` 는 브라우저 `WebSocket`·`EventSource`
와 달리 임의 헤더를 실을 수 있으므로, **permitAll 자체가 필요 없다.**

그렇다고 지금 갈아엎자는 것이 아니다 — WebSocket 은 이미 만들어져 있고 지연·서버 부하 면에서
더 낫다. 요점은 **조직이 「예외를 아예 안 받는 선택지」를 한 번도 저울에 올리지 않았다**는 것이다.
ADR 의 기각 목록은 「왜 이 방식인가」의 기록인데, 가장 근본적인 대안이 비어 있으면 그 기록이
다음 사람에게 「WebSocket 은 전제였다」로 읽힌다. **한 줄 추가로 족하다.**

#### C-3 — 기계 게이트가 없는 상태에서 예외가 쌓이고 있다

- permitAll 예외가 이제 **세 계열**이다 — 공개 대시보드 · iCal 피드 · 인바운드 웹훅 6경로 · 그리고 `/ws`.
- CI 4종은 **의도적으로 꺼져 있다**(2026-08-21 결정, `workflow_dispatch` 전용). 즉 이 예외를
  막을 장치가 **사람 한 명**뿐이다.

그 결정 자체는 되묻지 않는다(1인 개발 단계에서 값을 못 했다는 실증이 있다). 다만 **예외가 늘수록
사람 게이트의 단가가 오른다.** ADR 이 후속으로 적은 「permitAll 3경로군이 전부 메서드를 고정하는지
재는 판별식」은 그 단가를 낮추는 가장 싼 장치다 — 후속이 아니라 **다음 PR 의 첫 항목**으로 올릴 것을 권한다.

실제로 이번에 그 부재가 값을 치렀다. `/ws` 만 GET 고정을 안 따라온 것을 **사람이** 잡았다(리뷰 C1).
네 번째 경로가 생기면 같은 일이 반복된다.

#### 통과 — 되묻지 않는 것

| 항목 | 근거 |
|---|---|
| **예외의 필요성** | 브라우저 `WebSocket` API 가 업그레이드에 임의 헤더를 못 싣는 것은 웹 플랫폼의 구조적 제약이다. 프록시 문제가 아니라 규격이다 |
| **선행 ADR 2건과 다르다는 자기 인식** | 서명 검증(요청 내부) vs 다음 프레임 Bearer(요청 외부)의 차이를 ADR §D3 이 **스스로 적었다.** 논거를 빌려 쓰지 않은 것이 이 문서의 가장 좋은 점이다 |
| **폭발 반경** | `GET /ws` 한 경로 · 되돌리기는 한 줄 삭제 = **두 방향 문**. 되돌릴 수 없는 것을 만들지 않았다 |
| **데이터 노출** | CONNECT 성공 전 소켓은 어떤 데이터도 나르지 않는다. 인증 실패 = 데이터 0 |
| **R4 (한 PR = 한 BC 이탈)** | cross-BC `import` 0건 실측. 대가가 코드에 남지 않았다 |
| **R2·R3** | `Origin` 부재 요청은 통과해도 Bearer 없이 데이터 0. 프로퍼티 공유는 drift 위험이 분리 비용보다 크다는 판단이 합리적 |

#### 정책 권고

1. **머지 전** — ADR 의 R1 문단을 사실에 맞게 고친다(공개 엔드포인트 · 전 층 상한 부재). 수용하더라도 **무엇을 수용하는지**는 정확해야 한다.
2. **머지 전 또는 배포 전** — `nginx.conf` `location /ws` 에 `limit_conn`. 한 줄이고 실사용을 막지 않는다.
3. **머지 전** — ADR §대안에 롱폴링 한 줄 추가.
4. **다음 PR** — permitAll 경로군 메서드 고정 판별식.
5. **배포 시** — 이 PR 은 **미배포** 상태다. `/ws` 수정은 배포돼야 낫는다. 배포 후 콘솔 에러 소멸을 실화면에서 확인할 것.

### 🛑 게이트 2 요약 (2026-08-24 · Maxi 검토 대기)

#### 티어 — 선언과 실측

| | 값 | 근거 |
|---|---|---|
| **선언 티어** | **T2** | 착수 시점, `SecurityConfig` 경로 근거 |
| **실측 티어** | **T2** | `detect-tier.ts` 가 51파일 diff 를 판정 — 선언과 **일치**, 승격 없음 |
| 적중 표면 | `TEST` · **`SEC_BE`** · `GUARD_CI` · `SHELL` · `STYLE_COPY` · `DOC` · **`BE_MAIN`** · `FE_SRC` | |
| **`UNMAPPED`** | **0건** | 미분류 경로 없음 |
| 보안 렌즈 | **필수 (생략 불가)** — 수행함 | `securityLens: true` |

★classify 원출력은 `T1` 이었다(제목만 보므로 경로를 못 본다). 선언 T2 가 실측과 맞았다.

#### 건너뛴 단계 · 절차 이탈 (전량)

| # | 이탈 | 승인·근거 |
|---|---|---|
| 1 | **단계 순서** — 구현 12건이 [2]~[5] 보다 먼저 끝났다. spec·plan 은 **사후 작성** | 불가피(작업이 이미 존재) · 사후 spec 이 결함 D1~D7 + BLOCKER 를 드러냈다 |
| 2 | **티어 승격** — classify `T1` → 선언 `T2` | `/bts` 판정 5문 ①②. 실측이 T2 로 확인 |
| 3 | **worktree 미사용** — `git switch -c` 로 브랜치만 팜 | **Maxi 승인(D2)**. 이송 0 · husky 훅 네이티브 동작(실제로 3회 차단해 줌) |
| 4 | **slug 수동 지정** | 자동 생성 `ui-12-ws-permitall` 이 뜻을 잃음 |
| 5 | **줄수 래칫 상향** `BoardPage` 367→372 | h1 추가 5줄. `projectFavoriteHeader` 가 7개 값을 클로저로 잡아 함수 분해가 props 7개 컴포넌트 추출이 되어 범위 초과. 선례 288→289 형식으로 baseline 에 근거 주석 |
| 6 | **「한 PR = 한 BC」 이탈** — `notification` BC 프로덕션 코드 1파일 | **게이트 1 D5-A 승인**. `setAllowedOrigins` 는 같은 FR-NT-02 결함의 짝이라 분리하면 반쪽 수정이 된다. PR #395 선례와 같은 처리 |
| 7 | **sub-agent dispatch 미사용** — `bts-impl` wave dispatch 대신 컨트롤러 인라인 실행 | 세션 상위 지침이 Agent 도구 사용을 제한. TDD 규율(`test:`→`fix:`)과 wave 순서는 그대로 지켰다 |
| 8 | **리뷰 렌즈 발행 방식** — `/bts-review-plan` Step 3 은 2종을 한 응답에 발행하라고 하나 eng → design 순차 발행 | 실행은 어차피 순차라 결과 동일. 기록만 남긴다 |
| 9 | **Task 4 를 Task 8 에 흡수** | Esc 를 실제로 만들면 Task 4 가 고칠 주석이 참이 된다. 두 번 고칠 이유가 없다 |
| 10 | **ceo 렌즈 미발행** — `DATA.md §1-5` 가 요구하는 렌즈가 [4] 에서 자동 발행되지 않았다 | **해소함** — 독립 코드 리뷰의 BLOCKER B2 로 드러나 커밋 `7b40d7933` 에서 사후 수행, 판정 **CONCERNS**. 원인은 렌즈 분기가 `classify.type` 만 보고 변경 대상을 안 보는 것(`detect-tier` 는 경로로 `SEC_BE` 를 정확히 짚었다) — **부채 110** 등재 |
| 11 | **Task 11 의 `files` 메타와 실제가 다르다** — 계획은 2개(`AccountMenu.tsx`·`AccountMenu.test.tsx`), 실제는 4개(`AccountMenu.tsx` · `lib/settings-hub-links.ts` **신설** · `routes/settings.index.tsx` · `__tests__/settings-reachability.test.ts` **신설**) | 정본을 라우트 파일에서 `lib/` 로 뽑은 것은 **정당한 개선**이다 — 판별식이 정규식 파싱 대신 정본을 import 하게 된다. 다만 메타를 안 고쳤다. 재리뷰 지적 |
| 12 | **Agent 도구 예외 2회차** — 재리뷰도 `code-reviewer` 서브에이전트로 돌렸다 | **Maxi 승인(D2 · 이 세션)**. 지난 세션의 예외는 세션을 넘어오지 않으므로 다시 물었다. T2 는 독립 리뷰 2종이 계약이고, 자기 수정을 자가 채점하면 독립 리뷰가 아니다 |
| 13 | **e2e 전량 검증 보류** — 라운드 3 수정 뒤의 e2e 가 초록으로 확인되지 않았다 | **Maxi 결정** — 프로덕션 환경이 제대로 세팅된 뒤에 수행한다. 상세와 실측은 §🛑 e2e 전량 절. 이 항목은 **미검증**으로 게이트에 올린다 |

#### 검증 — 전량, 종료 코드로 판정 (**라운드 3** — 재리뷰 수정 반영 후 재실행)

재리뷰 BLOCKER-N1 + CONCERNS 를 고친 커밋 `92387be4d`~`8aaa7ffd9` **뒤에** 다시 돌린 값이다.
전 항목이 `EXIT=` 줄을 남긴다 — 파이프 없음. **e2e 만 보류이며 그 사유를 표 아래에 적는다.**

| 항목 | 결과 | 로그 |
|---|---|---|
| `pnpm -r run lint` | **`LINT_EXIT=0`** (경고 8건은 이 PR 밖 파일 — `SlackResultBanner.tsx`·`WeekGrid.tsx`, 선재) | `/tmp/lint-f4.log` |
| `pnpm -r run typecheck` | **`TC_EXIT=0`** | `/tmp/tc-f4.log` |
| `pnpm --filter web exec vitest run` (전량) | **`VITEST_EXIT=0`** · **585 files / 9,698 tests** | `/tmp/vt-f4.log` |
| `pnpm -r run build` | **`BUILD_EXIT=0`** | `/tmp/build-f4.log` |
| `pnpm --filter web exec playwright test` (전량) | 🛑 **보류 — Maxi 결정.** 아래 절 참조 | `/tmp/e2e-f4.log` |
| `./gradlew test ktlintCheck detekt` (**전 모듈**) | **`GRADLE_EXIT=0`** · BUILD SUCCESSFUL · `FAILED` **0건** · `app`·`identity-access`·`notification` 3모듈 실제 재실행 | `/tmp/gradle-f4.log` |
| `pnpm test:workflow` (판별식) | **`WF_EXIT=0`** · **412 / 412 pass · fail 0** | `/tmp/wf-c5.log` |
| `node scripts/build-doc-index.mjs --check` | **`DOCIDX_EXIT=0`** | `/tmp/di-f4.log` |
| `bash scripts/verify-master-plan.sh` | **`VMP_EXIT=0`** · FR 143/143 | `/tmp/vmp-f4.log` |

★**gradle 은 라운드 2 에서 한 번 red 였다.** 첫 실행(`/tmp/gradle-f2.log`)이 `GRADLE_EXIT=1` 로
끝났고 `FAILED` 는 `:modules:notification:ktlintTestSourceSetCheck` **단 하나** —
`WebSocketConfigTest.kt:5` 의 import 사전순 위반이다(C1~C3 수정 때 `assertThat` 을 잘못 끼웠다).
커밋 `9c40b48b6` 으로 고쳤다. 테스트 태스크는 그 실행에서 10개 모듈 전부 `FAILED` 없이 통과했고,
재실행에서 `:modules:notification:test` 가 `UP-TO-DATE` 인 것은 import 재배열이 **바이트 동일한
클래스**를 내기 때문이다. 고친 내용에 대한 ktlint·detekt 는 `/tmp/ktl.log` 에서 실제로 실행돼 통과했다.

#### 🛑 e2e 전량 — **보류** (Maxi 결정 · 프로덕션 환경 세팅 후 수행)

**초록이라고 적지 않는다.** 마지막으로 전량 통과한 실행은 라운드 3 커밋보다 **앞선다.**

| | 실측 |
|---|---|
| 마지막 초록 | `/tmp/e2e-f2.log` — `Running 707 tests` · **704 passed · 3 skipped · `E2E_EXIT=0`** (13.2m) |
| 그 실행 시점 | 라운드 2 직후. 라운드 3 커밋 `92387be4d`~`8aaa7ffd9` **이전**이다 |
| 라운드 3 재실행 | `/tmp/e2e-f4.log` — `Running 711 tests` · **`E2E_EXIT=1`** · 699 passed · **9 failed** · 3 skipped |

**9건의 정체 — 두 갈래로 갈린다.**

**① `[visual]` 4건 — 로컬에서 통과 불가(설계).** 실패 사유는 회귀가 아니라
`A snapshot doesn't exist at …/__screenshots__/issue-list-light.png, writing actual` 이다.
베이스라인이 **저장소에 없다**(`git ls-files` 0건). `playwright.config.ts` 가 스냅샷 접미
(`-darwin`)를 일부러 없앴고, 그 주석이 「baseline 은 러너와 동일 환경에서만 생성한다」를 계약으로
못박는다 — 개발 맥에서 만든 베이스라인을 커밋하면 러너에서 **조용히 새 파일을 만들며 초록**이 되는
가짜초록을 막기 위해서다.
★**f2 는 이 프로젝트를 아예 돌리지 않았다** — `Running 707` vs `Running 711`, 차이가 정확히
visual 4건이다. 즉 이 plan 이 지금까지 적어 온 「e2e 704 passed」는 **처음부터 chromium 범위**였다.
표가 그 범위를 숨기고 있었다.

**② `[chromium]` 5건 — 판정 미결.** f2 에서는 704 전량 통과했다. 그중
`workflow-scheme-assignment` E2E-5 는 **이미 등재된 부채**(「전량 실행에서만 strict mode 위반으로
죽는다」)이고 실패 메시지도 로케이터가 2개로 풀린 그 양식이다. visual 프로젝트가 같은 4 worker 를
나눠 쓰며 타이밍이 흔들렸을 가능성이 있으나 **확인하지 못했다** — `--project=chromium` 재실행을
시작했다가 Maxi 지시로 중단했다.

**라운드 3 이 `apps/web` 런타임에 남긴 변경.** `ShellLayout.tsx` 의 **주석 2곳**뿐이다(JSX 주석 ·
줄 주석). 나머지는 Kotlin 테스트 · vitest 판별식 · 문서다. 주석은 빌드 산출물에 남지 않으므로
**e2e 결과를 바꿀 경로가 없다.** 다만 이는 추론이고, 실행으로 확인한 것이 아니다.

**남은 로컬 산출물.** `apps/web/e2e/visual/__screenshots__/` 에 PNG 4개가 그 실패 실행으로
생성됐다(미추적). 프로덕션 러너에서 베이스라인을 만들기 전에 지우는 편이 안전하다 —
그대로 두면 이 맥의 다음 visual 실행이 그것과 대조해 **가짜초록**이 된다.

**게이트 2 판단에 필요한 것.** 이 항목은 **미검증**이다. 배포 전 눈확인 6항목(§시각 검증 기준)과
함께 프로덕션 환경에서 처리한다.

**뮤테이션 검증 8건 — 전부 red 확인 (GREEN 선커밋 뒤 실행 · 원복 완료)**

| # | 뮤테이션 | 결과 |
|---|---|---|
| 1 | `/ws` permitAll 줄을 끊는다 | `:modules:app` **EXIT=1** — 400 양성 판별자가 red |
| 2 | 허용목록에서 `use-sidebar-drawer` 제거 | **EXIT=1** |
| 3 | `RecentIssuesMenu` 를 직접 소비로 되돌림 | **EXIT=1** — 그 파일을 지목해 red |
| 4 | `Sidebar` 에 `max-lg:` 주입(경계 이원화) | **EXIT=1** — 그 파일을 지목해 red |
| 5 | `ShellLayout` 백드롭을 `md:hidden` → `lg:hidden` | **EXIT=1** — **B3 이 고친 바로 그 자리**. 수정 전에는 주석 속 `md:hidden` 에 매칭돼 초록이었다 |
| 6 | `SecurityConfig` 의 GET 고정을 주석으로만 남기고 등록에서 제거 | `:modules:identity-access` **EXIT=1** · `failures=3` — 수정 전에는 EXIT=0 이었다(BLOCKER-N1) |
| 7 | 같은 뮤테이션 · 행동 층 | `:modules:app` **EXIT=1** — `HEAD /ws` 가 **405** 로 바뀐다(401 이 아니다) |
| 8 | `TopBar` 를 prettier 형태로 리플로우 + 계정 메뉴 허브 링크 제거 | **EXIT=1** — 도달성 단언 **2건**이 red. 수정 전에는 4번째 단언만 걸려 진단이 엉뚱했다(C4) |

★5번은 **리뷰어가 표의 누락으로 지적한 줄**이다. 기존 4번(`Sidebar` 에 `max-lg:` 주입)은 다른
단언이라 B3 처방이 그 결함을 실제로 닫았는지를 표만 보고는 알 수 없었다.
★6·7 은 같은 한 줄을 끊고 **소스 층과 행동 층을 따로** 잰 것이다. 두 층이 함께 뚫리지 않는다는 증명.

**즉사 계약 8행** — `h1` 단 하나 포함 전량 통과(`board.tsx` h1 = 1개 실측).

#### ★ 검증 과정에서 잡은 것 (전량)

1. **`./gradlew … | tail -40` 로 돌려 종료 코드가 `tail` 의 것이 됐다.** 그래서 「백엔드 EXIT=0」을
   한 번 **잘못 보고했다.** 실제로는 `notification` 단독 테스트가 컨텍스트 로딩에서 죽어 있었고
   (`Could not resolve placeholder 'bts.security.cors.allowed-origins'` → 9개 클래스로 전파),
   전량 재실행에서야 드러났다. `bts-impl` 이 문서로 경고해 둔 그 함정이다.
2. **브레이크포인트 판별식의 하드코딩 목록이 양방향으로 틀려 있었다** — `max-md:` 를 안 쓰는
   `ShellLayout` 을 넣어 두고 실제로 쓰는 `AccountMenu` 를 빠뜨렸다. 도출식으로 교체했다.
3. **종료 코드 마커가 없는 로그를 「초록」으로 옮겨 적을 뻔했다.** 라운드 1 의 `lint`·`typecheck`·
   `build` 로그에는 `EXIT=` 줄이 아예 없었다 — 화면 꼬리만 보고 0 이라 적은 것이다. 1번과 같은
   결함의 변주다. 라운드 2 는 전 항목에 `echo "…_EXIT=$?"` 를 붙여 다시 돌렸다. 같은 신선도
   감사에서 `verify-master-plan` 이 ADR 문서 커밋(`7b40d7933`)보다 **앞선 실행**이었음도 드러나
   함께 재실행했다. **표에 적는 값은 그 커밋보다 뒤에 돌았는지까지 확인한다.**
4. **주석 제거를 정규식 한 방으로 했다가 가드를 더 공허하게 만들었다.** BLOCKER-N1 처방에서
   블록 주석을 비탐욕 정규식으로 지웠더니 `"/actuator/**"` 같은 **Ant 경로 패턴**의 슬래시-별표가
   여는 표기로 잡혀 아래 KDoc 의 닫는 표기까지가 통째로 지워졌고, 지키려던 배선이 함께 사라졌다.
   이 저장소는 문자열 리터럴에 그 표기를 5곳에서 쓴다. **함께 넣은 비-공허 짝
   (「주석 제거가 실제 배선까지 지우지는 않는다」)이 그것을 잡았다** — 짝이 장식이 아님을
   같은 PR 안에서 실측으로 보였다. 처방은 문자열 리터럴을 건너뛰는 스캐너.
5. **낡은 test XML 을 신선한 결과로 읽었다.** 컴파일이 깨져 종료 코드가 1 이었는데 XML 을 먼저
   읽어 「테스트 2건 실패」로 오독했다. 실제로는 **테스트가 실행조차 안 됐다.** 같은 라운드에서
   `:modules:app` 뮤테이션 결과도 앞 태스크 실패로 실행되지 않은 XML 을 볼 뻔했다.
   → 이후 전 판독에 **XML mtime 대조**를 붙였다. 저장소 메모리
   `lint-fails-first-leaves-stale-test-xml` 그대로다.
6. **Kotlin KDoc 본문에 블록 주석 표기를 글자 그대로 써서 컴파일이 깨졌다.** Kotlin 블록 주석은
   **중첩**되므로 여는 표기는 짝을 요구하고 닫는 표기 하나면 KDoc 이 거기서 끝난다.
   「주석 때문에 가드가 공허하다」를 설명하는 주석이 주석 문법으로 깨진 것이다. 그 사실을
   해당 KDoc 에 남겼다.

#### [6] 독립 리뷰 — 라운드 1·2 판정과 처리 (전량)

`code-reviewer` 서브에이전트를 **두 번** 돌렸다(Agent 도구 예외 · 이탈 #12). 라운드 2 는 라운드 1
수정이 실제로 닫혔는지를 커밋 메시지가 아니라 **코드와 뮤테이션으로** 다시 물은 것이다.

| 지적 | 라운드 1 | 라운드 2 판정 | 처리 |
|---|---|---|---|
| **B1** Escape 가 split view pane 닫기를 죽였다 | BLOCKER | **닫힘** — 2층 뮤테이션 red 실측 | `af2abdf79` |
| **B2** `/ws` permitAll 에 ADR 인용이 없다 | BLOCKER | **닫힘** — `infra/` 상한 0건 재확인 · 부채 109 양쪽 등재 | `8c6080776` · `7b40d7933` |
| **B3** 판별식 3종이 주석을 맞춰 공허 | BLOCKER | **부분** — 원 결함은 red / 후행 `//` 는 여전히 통과 | `18cf27a5f` · 잔여 **부채 113** |
| **B4** 판별식이 실제 표기를 놓친다 | BLOCKER | **닫힘** — 쌍따옴표·동적·`import type` 전부 red / 확장자만 우회 | `18cf27a5f` · 잔여 **부채 114** |
| **C1** GET 고정 | CONCERNS | **닫힘** (단 아래 N1 로 가드가 무력화 가능했다) | `8c6080776` |
| **C2** `setTimeToFirstMessage` | CONCERNS | **닫힘** — 그 줄 삭제 시 red 실측 | `8c6080776` |
| **C3** 와일드카드 거부 | CONCERNS | **닫힘** — `require` 삭제 시 red 실측 | `8c6080776` |
| **C4** 도달성 가드가 포매터 리플로우에 눈먼다 | CONCERNS | **안 닫힘** → 수정 | `ed1b34f78` |
| **C5** 브레이크포인트 스캔 범위가 과하다 | CONCERNS | **안 닫힘** · 부채 가능 | **부채 115** |
| **C6** 개발 오리진 차집합 판별식 부재 | CONCERNS | **안 닫힘** · 부채 가능 | **부채 112** |
| **C7** 실패 메시지가 이동한 정본을 가리킨다 | CONCERNS | **안 닫힘** → 수정 | `ed1b34f78` |

**라운드 2 신규 — BLOCKER 1 · CONCERNS 4**

| 신규 | 무엇 | 처리 |
|---|---|---|
| 🛑 **N1** | 이 PR 이 **신설한** `/ws` 소스 가드가 주석 제거 없이 단언한다. GET 고정을 주석으로만 남기고 등록에서 빼도 **EXIT=0** — `POST /ws`·`DELETE /ws` 가 익명이 돼도 초록이었다. B3 이 프론트에서 닫은 양식을 같은 PR 이 보안 표면에 새로 만든 것이다 | **수정** `92387be4d`(소스 층) · `7e1195f2e`(행동 층 — `HEAD /ws` 401) |
| **N2** | `withoutComments()` 가 후행 주석을 못 지우고 3벌 복제 | **부채 113** |
| **N3** | B1 수정이 자기 주석 3곳을 거짓으로 만들었다. 특히 `ShellLayout.tsx:85` 는 **회귀를 만든 배치를 다음 사람에게 지시**한다 | **수정** `71b7e4b9e` |
| **N4** | 모듈 지정자 정규식이 확장자 표기를 놓친다 | **부채 114** |
| **N5** | 부채 109 의 처방(`limit_conn` 한 줄)이 `limit_conn_zone` 누락으로 **실행 불가** | **정정** `8aaa7ffd9` |

**리뷰가 게이트 2 요약 자체도 검증했다.** 라운드 2 리뷰어가 티어 판정·vitest·판별식·doc-index·
verify-master-plan 을 **직접 재실행해** 표의 값과 대조했고 전부 일치했다. 그때 지적된 누락 1건
(뮤테이션 표에 B3 이 고친 뮤테이션이 없다)은 위 뮤테이션 표 5번으로 채웠다.

**남은 미처리 — 전부 장부에 있다.** 부채 **109**(배포 전 필수) · **111**(permitAll 메서드 고정
판별식 · ceo C-3) · **112~115**. 산문으로만 남기지 않은 이유는 `debt-ledger-mapping` 판별식이
`TODOS.md` ↔ 마스터 §전수 매핑 양방향 일치를 강제하기 때문이다 — 장부 밖 산문은 기계가 안 본다.

#### 범위 밖으로 남긴 것 (전부 `TODOS.md` + 마스터 매핑 **104~115** 등재)

| 항목 | 왜 안 했나 |
|---|---|
| F21 컨테이너 관례 전수 적용 | 48 라우트 규모 · 어느 쪽으로 모을지가 Maxi 결정 |
| `ProjectTree` 2단 들여쓰기 | 반쪽 수정 시 depth2 와 5px 차이가 되어 **깊이 신호가 소멸**한다 |
| 설정 화면 마감 5건 | 사용자를 막지 않는 시각 일관성 |
| 드로어 **완전** 포커스 트랩(`aria-modal` + `inert`) | 게이트 1 D4-A — 셸 전역 포커스 동작 변경이라 별건 |
| `FieldPermissionController` 의 `ResponseEntity<*>` + 응답 규약 이원화 | **프로덕션 장애의 근본 원인.** 수렴은 다른 소비처를 깨는 API 변경이라 별건 |
| classify 오분류(「스키마」→`migration`) | 기존 항목에 실측만 추가 — 신규 등재 아님 |

#### F24 판정

**완주로 선언하지 않는다**(두 렌즈 일치). 로드맵 표의 수단 기술만 「Sheet」→「오프캔버스 + 백드롭」
으로 고쳤다 — 계약 §4 재사용 자산에 `sheet` 항목이 없어 로드맵이 **없는 자산을 지목**하고 있었다.
드로어 완전 포커스 트랩이 닫히면 그때 완주 처리한다.

#### 배포

★**#397 · #398 · 이 PR 모두 미배포다.** 머지가 배포를 트리거하지 않는다(워크플로우 4종이
`workflow_dispatch` 전용). `/ws` 수정과 필드 권한 수정은 **배포돼야 실제로 낫는다** —
`bash infra/deploy/bts-deploy.sh`(수십 분 · Docker).

#### 눈확인 — 미완 (계약 §6)

E2E 707건이 좁은 폭까지 덮었지만, 계약 §6 이 요구하는 **실브라우저 눈확인은 배포 후**로 남는다.
확인 항목은 위 `### 시각 검증 기준` 6개다. 게이트 2 승인 시 그 항목을 배포 후 체크리스트로 넘긴다.
