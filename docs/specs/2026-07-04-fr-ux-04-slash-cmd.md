<!-- FR-UX-04 Slash 명령어(Cmd+K 명령 팔레트) 스펙 — 프론트 전용 -->

# FR-UX-04 Slash 명령어 (Cmd+K 명령 팔레트) — 스펙

- FR: FR-UX-04 | BC: personalization(논리) / apps/web(물리, 프론트 전용)
- ADR: docs/decisions/2026-07-04-fr-ux-04-slash-cmd.md
- 관련 PR: #236

## 개요

`Cmd+K`(mac) / `Ctrl+K`(win·linux)로 여는 전역 **명령 팔레트**. 슬래시 명령으로
빠른 네비게이션을 제공한다. 백엔드 없음(전부 클라이언트 라우팅으로 귀결).

## 사용자 시나리오 (Given-When-Then)

### S1. 팔레트 열기/닫기
- Given 로그인한 사용자가 아무 페이지에 있고
- When `Cmd+K`(또는 `Ctrl+K`)를 누르면
- Then 화면 중앙에 명령 팔레트가 열리고 입력창에 포커스가 간다.
- And `Esc`를 누르거나 바깥(overlay)을 클릭하면 닫힌다.

### S2. 기본 목록(빠른 이동)
- Given 팔레트가 열렸고 입력이 비어 있으면
- Then 자주 가는 페이지 바로가기 목록이 보인다 — 내 이슈(`/issues`), 검색(`/search`),
  대시보드(`/dashboards`), 받은 편지함(`/inbox`) + 사용 가능한 명령 힌트(`/goto`, `/search`, `/issue`).
- When 정적 바로가기 항목을 ↑/↓로 이동하고 `Enter`(또는 클릭)하면 해당 라우트로 이동하고 팔레트가 닫힌다.
- When **명령 힌트**(`/goto`·`/search`·`/issue`)를 선택하면 라우팅하지 않고 입력창에 해당 명령 prefix(`/goto ` 등)를 채우고 대기한다(인자 입력 후 `Enter`로 실행).

### S3. `/goto <이슈키>` — 이슈로 이동
- Given 팔레트에 `/goto PROJ-12`를 입력하고
- When `Enter`를 누르면
- Then `/issues/PROJ-12`로 이동하고 팔레트가 닫힌다.
- 이슈 키 형식(`^[A-Z][A-Z0-9]*-\d+$`, 입력은 대소문자 무시→대문자 정규화)에 맞아야 실행.

### S4. `/search <질의>` — 검색 결과로 이동
- Given 팔레트에 `/search 로그인 버그`를 입력하고
- When `Enter`를 누르면
- Then `/search?q=로그인 버그`로 이동하고, 검색 페이지가 그 질의로 AQL 검색을 실행한다.
- 명령은 `q`만 전달한다. `projectKey`는 optional(SearchRouteAdapter) — 프로젝트 스코프 선택은 검색 페이지에 위임한다(팔레트 책임 아님).

### S5. `/issue <제목>` — 새 이슈 폼(제목 프리필)으로 이동
- Given 팔레트에 `/issue 결제 실패 조사`를 입력하고
- When `Enter`를 누르면
- Then `/issues/new?summary=결제 실패 조사`로 이동하고, 새 이슈 폼의 제목 필드가 프리필된다.
- 즉석 생성(mutation)이 아니라 **폼 이동**이다(ADR D3 — "빠른 이동만"과 양립).

## 기능 요구사항 (FR)

- FR1. 전역 단축키 `Cmd+K`/`Ctrl+K`로 팔레트를 토글한다. 브라우저 기본 동작을 `preventDefault`한다.
- FR2. 팔레트는 **로그인 상태에서만** 활성(Header와 동일 조건). 비로그인 시 훅/렌더 없음.
- FR3. 입력이 `/`로 시작하면 명령 모드. 첫 토큰이 명령어(`goto`/`search`/`issue`), 공백 뒤 나머지가 인자.
- FR4. 명령 3종을 지원한다: `/goto`(이슈 이동) · `/search`(검색 이동) · `/issue`(새 이슈 폼 이동).
- FR5. 인자가 비었거나 형식이 틀리면 실행하지 않고 팔레트 안에 안내 문구를 표시한다(라우팅 없음).
- FR6. 명령 실행/항목 선택 후 팔레트를 닫고 입력 상태를 초기화한다.
- FR7. `/issue` 프리필을 위해 `issues.new` 라우트에 `summary`(선택) search param을 추가하고 폼 기본값에 반영한다.

## 비기능 요구사항 (NFR)

- NFR1. 팔레트 열기~렌더 p95 ≤ 100ms(§NFR personalization 측정표 "명령 팔레트(cmdk) 응답").
- NFR2. WCAG 2.1 AA — role/aria(cmdk 기본 `role="dialog"` + listbox), 키보드 전용 조작 완결, focus trap, `Esc` 닫기, **닫힌 후 직전 포커스 요소로 복원**.
- NFR3. 신규 외부 의존성 0 — 기존 `cmdk`(FR-IS-09 도입) 재사용.
- NFR4. IME(한글 조합) 중 `Enter`는 조합 확정으로 처리하고 명령을 실행하지 않는다(isComposing 가드).

## API 인터페이스 (REST)

**없음.** 프론트 전용. product 문서 D4 `POST /api/v1/commands/execute`는 미도입(ADR D1).
명령은 기존 라우트/기존 검색·이슈 경로로 클라이언트에서 dispatch.

## 데이터 모델 변경

**없음.** 명령 정의는 프론트 코드 상수(`commands.ts`).

## 엣지 케이스

- E1. 알 수 없는 명령(`/foo`) → 실행 안 함, "알 수 없는 명령" 안내.
- E2. 인자 없는 명령(`/goto` 만) → 실행 안 함, 사용법 힌트.
- E3. `/goto` 인자가 이슈 키 형식 아님(`/goto 안녕`) → 실행 안 함, 형식 안내.
- E4. 대소문자 이슈 키(`/goto proj-12`) → `PROJ-12`로 정규화 후 이동.
- E5. 비로그인 상태에서 `Cmd+K` → 아무 동작 없음(FR2).
- E6. 입력창/textarea에 타이핑 중 `Cmd+K` → 팔레트는 여전히 열림(전역). 단 `preventDefault`로 브라우저 검색바 차단.
- E7. 팔레트 열린 상태에서 다시 `Cmd+K` → 닫힘(토글).
- E8. 존재하지 않는 이슈 키로 이동(`/goto ZZZ-999`) → 라우팅은 성공, 대상 페이지가 기존 404/빈 상태 처리(팔레트 책임 아님).
- E9. 다른 모달(이슈 생성/공유 등)이 열린 상태에서 `Cmd+K` → 팔레트는 전역 최상위 오버레이로 열린다(중첩 허용, cmdk Dialog가 최상위 z-index). 닫으면 하위 모달로 포커스 복원.

## 제약 조건

- C1. 팔레트는 `__root.tsx`의 `RootLayout`에 마운트(전역 단일 인스턴스). Header와 동일하게 인증 시만.
- C2. 라우팅은 TanStack Router `router.navigate`/`useNavigate` 사용(code-based 라우팅, 기존 RouteAdapter 패턴 유지).
- C3. 명령 파서/레지스트리는 순수 함수로 분리(단위 테스트 대상). 컴포넌트에서 라우팅 부수효과 분리.
- C4. DESIGN.md 토큰 준수(overlay/card/muted/border/ring 등). 새 색/토큰 신설 금지.
- C5. UI 문자열은 기존 관례(컴포넌트별 `*Strings` 객체, 한글 하드코딩) 따름. 문장 끝 콜론 금지([[ktlint-detekt]]/i18n 콜론 학습).

## Brainstorming Check

✅ 통과 (sanity-check 1회 보강). 발견 gap 4건 모두 수정 가능(Maxi 결정 불필요) — 반영 완료.
- 명령 힌트 클릭 동작(입력창 prefill, S2) / `/search` q만 전달·projectKey 위임(S4) /
  포커스 복원(NFR2) / 모달 중첩 정책(E9).
- 핵심 검증: SearchRouteAdapter의 projectKey는 optional → `/search?q=` 라우팅 성립 확인.

## 측정 가능한 완료 기준

- [ ] `Cmd+K`/`Ctrl+K`로 팔레트 토글(E2E).
- [ ] `/goto PROJ-12` → 이슈 상세 이동(E2E).
- [ ] `/search <질의>` → 검색 페이지가 질의로 실행(E2E).
- [ ] `/issue <제목>` → 새 이슈 폼 제목 프리필(E2E).
- [ ] 명령 파서 단위 테스트(정상/E1~E4 분기).
- [ ] 비로그인 시 팔레트 미표시(단위/E2E).
- [ ] axe-core 0 violations, `Esc`/키보드 조작 완결.
