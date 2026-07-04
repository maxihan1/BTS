<!-- FR-UX-04 Slash 명령어(Cmd+K 명령 팔레트)의 프론트 전용 아키텍처 + 명령 범위 결정 ADR -->

# ADR — FR-UX-04 Slash 명령어 (Cmd+K 명령 팔레트): 프론트 전용 아키텍처 + 명령 범위

- 날짜: 2026-07-04
- 상태: 채택 (Accepted)
- 관련 FR: FR-UX-04
- 관련 PR: #236

## 맥락 (Context)

product 문서(`docs/plan/product/personalization.md §4.2`)는 FR-UX-04(Slash 명령어)를
`personalization` 그룹에 분류하고, D 단계를 다음과 같이 명세한다.

- D1. 도메인 — Command
- D2. 명세 — `/issue`, `/search`, `/goto` 등
- D3. 데이터 모델 — 활용(명령어 정의는 코드 상수), 신규 테이블 없음
- D4. 백엔드 — `POST /api/v1/commands/execute`
- D5. 백엔드 테스트
- D6. 프론트 UI — cmdk 명령 팔레트 (`Cmd+K`)
- D7. E2E

코드베이스 조사 결과.

1. **cmdk는 이미 설치·실사용 중.** `apps/web/package.json`에 `"cmdk": "^1.1.1"`. FR-IS-09(라벨 자동완성,
   PR #79)에서 `LabelAutocompleteInput.tsx`가 이미 사용한다. 신규 외부 의존성 도입이 아니다.
2. **"personalization"은 백엔드 물리 모듈이 아니다.** 논리 그룹일 뿐. FR-UX-01(퀵 필터)은 agile-planning,
   FR-UX-02(즐겨찾기)는 favorites, FR-UX-03(Inbox)은 notification에 각각 구현됐다. 개인 설정
   `PreferencesController`는 identity-access의 최소 시연 구현이며 "실제 도메인은 personalization BC"라 명시.
3. **명령 3종은 본질적으로 네비게이션.** `/goto`는 기존 라우터 이동, `/search`는 기존 이슈 검색
   라우트(`/issues?q=`) 이동, `/issue`는 기존 새 이슈 폼(`/issues/new`) 이동이다. 세 명령 모두
   서버 상태를 바꾸는 mutation이 아니라 클라이언트 라우팅으로 귀결된다.
4. **선례 존재.** FR-UX-01이 논리 소속(personalization)과 물리 구현(agile-planning)을 분리했고
   (`docs/decisions/2026-07-04-fr-ux-01-quick-filter-bc.md`), FR-SR-01도 같은 논리≠물리 패턴.

## 결정 (Decision)

### D1. 아키텍처 — 프론트 전용 (백엔드 command executor 미도입)

명령 팔레트를 **프론트 전용**으로 구현한다. 명령 레지스트리는 프론트 코드 상수(`commands.ts`)로 두고,
cmdk 팔레트가 명령을 파싱해 기존 라우터/기존 검색·이슈 라우트로 직접 dispatch한다.
**product 문서 D4(`POST /api/v1/commands/execute`) + D5(백엔드 테스트)는 도입하지 않는다.**

근거. 명령 3종이 전부 네비게이션이라 서버 왕복이 불필요하고(맥락 §3), 서버 executor는 파싱/권한/실행을
중앙화하지만 지금 명령들엔 서버에서 수행할 mutation이 없다. 얇은 통과 엔드포인트를 만드는 것은
CLAUDE.md §2(Simplicity First) 위반이며 왕복 지연만 추가한다. 향후 서버측 실행이 필요한 액션 명령
(`/assign`, `/transition` 등)이 생기면 그때 executor를 도입한다(YAGNI).

### D2. 논리적 FR 소속은 personalization 유지 (논리 ≠ 물리)

`docs/plan/fr-index.md`의 FR-UX-04 BC 매핑(`personalization`)은 **변경하지 않는다.**
물리 구현은 `apps/web`(프론트 전용)이지만 FR의 논리적 소속은 개인화 영역으로 유지한다.
→ fr-index의 카운트/BC 합계 변경 없음(전수 동기화 카운트 영향 0).

### D3. 명령 범위 — /goto · /search · /issue 3종, 전부 네비게이션

MVP 명령은 3종. 모두 라우팅으로 귀결하며 즉석 mutation은 없다.
- `/goto <이슈키|프로젝트|보드|대시보드>` — 기존 라우트로 이동.
- `/search <질의>` — 이슈 검색 라우트(`/issues?q=`)로 이동.
- `/issue <제목>` — 새 이슈 폼(`/issues/new`)으로 이동, 제목 프리필(즉석 생성 아님).

Q2 응답의 "빠른 이동만(액션 명령 제외)"과 "/issue 포함"은 `/issue`를 폼 이동으로 정의함으로써
양립한다(세 명령 모두 네비게이션 = 액션 명령 0). `/assign` 등 서버 mutation 명령은 후속.

### D4. cmdk 재사용 (신규 의존성 0)

FR-IS-09가 도입한 `cmdk`를 명령 팔레트에 재사용한다. `Cmd+K`(mac) / `Ctrl+K`(win/linux) 토글.
새 라이브러리 도입·Maxi 승인 불필요.

## 결과 (Consequences)

- **product 문서 deviation.** `personalization.md §4.2`의 D3~D5(데이터 모델/백엔드/백엔드 테스트)를
  "프론트 전용, 백엔드 없음"으로 조정한다. D6/D7은 유지. 이 조정은 구현 PR 안에서 전수 동기화한다
  (CLAUDE.md §명세/범위 변경 시 전수 동기화). fr-index BC 매핑·카운트는 불변.
- **백엔드 sub-agent 미사용.** 이 PR은 frontend-engineer + qa-engineer 중심. backend-engineer 태스크 0.
- **접근성/성능 게이트.** cmdk 팔레트 응답 100ms(§NFR), WCAG 2.1 AA — E2E/axe로 검증.
- **키맵 충돌.** `Cmd+K`가 기존 단축키와 충돌하지 않는지 확인(현재 전역 `Cmd+K` 미사용).
