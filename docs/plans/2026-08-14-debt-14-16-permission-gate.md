# 권한 게이트 훅 소비처 3곳의 계약을 고정하고 임포트 화면의 언마운트·E2E 사각을 닫는다

> 티어: T2
> slug: debt-14-16-permission-gate
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-14
> 부채. TODOS.md 매핑 `14`(임포트 CREATE 게이트 E2E 부재) · `15`(로딩 프레임 계약 소비처 2곳 공백) · `16`(권한 재조회 언마운트)

## Brief

**사용자 원문.** 「권한 게이트 3건 먼저 진행하자」 — `/context-restore` 로 복원한 남은 일 1번(부채 ⬜ 20건)에서
`docs/plans/2026-08-12-debt24-master.md` §PR 별 집계의 **「권한 게이트」 묶음**(항목 `14`·`15`·`16`)을 지목.

**이 묶음을 먼저 하는 이유(정본 §순서 제약).** 항목 `8`·`22`(IssueCreateForm 줄수)가 이 묶음 **다음**으로
고정돼 있다. 이유 둘 — ① 줄수 래칫 베이스라인은 게이트 변경이 끝난 뒤 재측정해야 값이 안 흔들린다
② 항목 `15` 가 로딩 프레임 계약을 `IssueCreateForm.tsx` 에 넣으므로, 분할(`8`)을 먼저 하면
**계약을 어느 조각에 넣을지가 다시 열린다.** 계약 고정 → 줄수 정리 순서다.

**classify 결과와 선언의 불일치 (의도적 오버라이드 · 게이트 2 요약에 재기재).**

| | 분류기 출력 | 이 PR 의 선언 | 사유 |
|---|---|---|---|
| type | `qa` | **`ui`** | 판정 순서상 `qa`(5번)가 `ui`(7번)보다 앞서 **제목에 「E2E」가 있으면 위치 무관하게 qa** 로 떨어진다(제목 순서를 바꿔 2회 실측) |
| agent | `qa-engineer` | **`frontend-engineer`** | 작업 대부분이 `apps/web/src` 동작 변경인데 qa-engineer 는 **구현 코드 수정 금지** 에이전트다. 항목 `14`(E2E) task 만 plan 메타로 `qa-engineer` 지정 |
| tier | `T1` | **`T2`** | 혼합 최고 티어. 항목 `16` 이 장부에 **T2 로 등재**돼 있다 |
| slug | `14-15-16-create-e2e` | **`debt-14-16-permission-gate`** | 분류기 slug 는 E2E 만 하는 PR 로 읽힌다 |

**★신규 부채 후보 (이 PR 이 등재).** 위 type 오분류는 이 작업 한 건의 문제가 아니다 —
**E2E 를 함께 넣는 모든 혼합 PR 이 구조적으로 qa-engineer 로 오배정**된다. 등재는 §Sanity Check 에서 확정한다.

## 도메인 정리

**BC.** `apps/web` 단일. 프론트는 단일 SPA 라 BC 분할이 없다(`classify.primary_bc = null` 은 오류가 아니다).
**백엔드 0 · 마이그레이션 0 · 신규 API 0 · 신규 의존성 0.** 서버 계약은 한 줄도 건드리지 않는다.

**영향 엔티티.** 도메인 엔티티 변경 없음. 이 PR 이 다루는 것은 **렌더 계약**이다 —
「권한 판정이 아직 안 끝난 프레임에 화면이 무엇을 그리는가」.

**관련 ADR: 없음.** `docs/decisions/` 전수 grep(`useIssueCreatePermissionGate` · `CREATE 게이트` ·
`명시 거부`) **0건**. 이 게이트의 근거 정본은 ADR 이 아니라 훅 KDoc
(`components/issue/create/use-issue-create-permission-gate.ts:4-26`)이다.

**용어 제안 1건 (Maxi 승인 전까지 glossary 미반영).** 「**로딩 프레임 계약**」 —
권한 쿼리가 정착하기 전 프레임에 그 화면이 무엇을 그리기로 약속했는가. 이 저장소에 이미 2종이 실재한다.

| 계약 | 로딩 프레임에 그리는 것 | 소비처 |
|---|---|---|
| `no-verdict-while-loading` | 판정을 **하나도** 안 그린다(h1 + 로딩 표시만) | 임포트 라우트 |
| `open-while-loading` | 폼·액션을 **그대로 연다**(미지를 거부로 읽지 않음) | `IssueCreateForm` · `IssueMetaPanel` |

**★두 계약은 통합 대상이 아니다.** 임포트는 존재(404)·권한 두 판정이 경쟁해 **틀린 사유를 먼저 읽는**
결함이 실재했고(게이트2 재리뷰 C1), 나머지 둘은 로딩 중 폼을 닫으면 **정상 사용자를 차단**한다.
폐기된 예약 PR #370 본문의 「3곳 전부 같은 계약」을 그대로 구현하면 회귀다.

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1 (항목 14).** Given 이슈 생성 권한이 없는 사용자, When `/projects/ATLAS/settings/import` 진입,
Then 임포트 폼 대신 「생성 권한이 없습니다」 카드가 보이고 `h1` 은 그대로 남는다.

**S2 (항목 14).** Given 아무 사용자, When 존재하지 않는 `/projects/BOGUS/settings/import` 진입,
Then 「프로젝트를 찾을 수 없습니다」 카드가 보인다 — **권한 탓을 하지 않는다.**

**S3 (항목 16).** Given 임포트 job 이 진행 중(파일 선택 완료 또는 폴링 중), When 창을 30초 넘게
벗어났다 돌아와 권한이 재조회되고 `CREATE:false` 로 뒤집힘, Then **화면이 유지되고** 파일 선택·jobId·
진행률이 살아 있다. 사용자가 제출하면 서버 403 이 최종 판정한다.

**S4 (항목 16 대조군).** Given 임포트 화면에 진행 중인 것이 없음, When 같은 재조회로 `CREATE:false`,
Then 거부 카드로 교체된다 — **게이트를 죽이는 것이 아니다.**

**S5 (항목 15).** Given `IssueCreateForm` · `IssueMetaPanel`, When 권한 쿼리가 아직 pending,
Then 폼과 클론 액션이 **열려 있다**(현행 동작 — 이 PR 은 그것을 **단언으로 고정**한다).

### Jira 대조

**대응 화면 없음 → 대조 불가, ADS 준용.** Jira Cloud 의 CSV 임포트는 사이트 관리자 전용 별도 화면이라
「프로젝트 설정 안의 임포트 탭 + 권한 거부 카드」에 대응하는 화면이 없다. 새 UI 컴포넌트를 만들지
않으므로(§재사용 자산) 조작감 갭도 발생하지 않는다.

**즉사 계약 교차 결과** (`docs/design/jira-parity-contract.md` §2).
- `<h1>` 단 하나 + 이름 verbatim → **이 PR 이 h1 문자열을 건드리지 않는다.** 로딩·거부·미존재 3분기가
  전부 `h1` 을 남기는 현행 구조를 그대로 둔다. 신규 E2E 도 이 h1 을 셀렉터로 쓴다.
- `role="dialog"` 고유 label → 신규 다이얼로그 0건, 해당 없음.
- 나머지 계약(내비 aria-label · 팔레트 · 단축키) → 건드리는 표면 0.

### 기능 요구사항

| ID | 요구 | 항목 |
|---|---|---|
| R1 | 임포트 CREATE 거부 경로에 E2E 시나리오가 있다 (S1) | 14 |
| R2 | 임포트 프로젝트 미존재 경로에 E2E 시나리오가 있다 (S2) | 14 |
| R3 | 게이트 훅 소비처는 **전부** 자기 로딩 프레임 계약을 선언한다 | 15 |
| R4 | 선언과 실제 소비처 집합의 **차집합이 양방향으로 공집합**임을 판별식이 강제한다 | 15 |
| R5 | `open-while-loading` 2곳에 pending 프레임 단언이 있다 (S5) | 15 |
| R6 | 임포트 진행 중에는 **어느 판정도** 화면을 언마운트하지 못한다 (S3) | 16 |
| R7 | 진행 중이 아니면 두 판정이 그대로 작동한다 (S4) | 16 |
| R8 | 동결 중 제출이 403 을 받으면 **권한 문구**가 뜬다 (EC4) | 16 |
| R9 | 동결 중에는 **비-파괴적 안내**가 상태 변화를 알린다 (디자인 D5) | 16 |

**★R9 은 디자인 렌즈가 넣었다 (Maxi 확정 A · 2026-08-14).** 동결하면 화면이 동결 전과 완전히
같아서 사용자는 권한이 바뀐 것을 **제출할 때까지 모른다**. 큰 파일을 올리는 중이면 그만큼을 버린다.

- **문구 톤은 「거부됐다」가 아니라 「바뀐 것 같다」.** 게이트는 사전 신호일 뿐이라 아직 성공할
  수도 있다 — 단정하면 사용자가 성급히 포기한다.
- **`role="status"`(polite)**. `alert`(assertive)는 작업을 끊는다. 이 신호는 에러가 아니다.
- **i18n 밖에 쓰지 않는다** — `i18n/import-labels.ts` 에 키를 신설한다(장부 항목 `21` 이 다루는
  결함을 이 PR 이 새로 만들지 않는다). 색은 DESIGN.md §C 시맨틱 상태색을 쓰고 하드코딩 금지.

### 상호작용 상태표

| 상태 | 사용자가 보는 것 | 스크린리더 |
|---|---|---|
| 로딩(존재·권한 미정착) | `h1` + 「로딩 중...」 — 폼도 카드도 없다 | `role="status"` (선재) |
| 부재(404) | 「프로젝트를 찾을 수 없습니다」 카드 | 정적 |
| 거부(CREATE:false) | 「생성 권한이 없습니다」 카드 (`destructive` 톤) | 정적 |
| 정상 | 모드 토글 + 폼/위저드 | — |
| **동결(신규)** | **현재 화면 그대로 + 비-파괴적 안내 1줄** | **`role="status"`** |
| 제출 실패 403 | `ErrorAlert` 에 서버 `detail` 문구 (선재) | `role="alert"` (선재) |

**★R6 은 리뷰 A2 로 넓어졌다 (Maxi 확정 2026-08-14).** 등재 본문은 권한 재조회만 적었지만
바로 위 `isProjectMissing` 분기도 `useProject` 의 focus 재조회를 타므로 **임포트 중 프로젝트가
삭제되면 똑같이 화면이 사라진다.** 같은 결함의 두 번째 문이라 함께 닫는다. 동결 규칙은
「**진행 중이면 판정을 그리지 않는다**」 하나로 단순해진다.

```
                   ┌──────────── 임포트 화면 렌더 판정 ────────────┐
  useProject ──────▶ isProjectLoading ┐
  useProjectPermissions ─▶ isPermsLoading ┘─▶ isResolving ──▶ [로딩 표시 · h1 유지]
                                                   │ 아니오
                                                   ▼
                              isChildBusy(진행 중) ──예──▶ [현재 화면 유지 · 판정 안 그림]
                                                   │ 아니오          ↑
                                                   ▼                 │ onBusyChange
                                    isProjectMissing ──예──▶ [부재 카드]
                                                   │ 아니오          │
                                                   ▼                 │
                                  isCreateDenied ──예──▶ [거부 카드]  │
                                                   │ 아니오          │
                                                   ▼                 │
                                        [ImportForm | ImportMappingWizard] ─┘
                                                   │ 제출
                                                   ▼
                                     서버 403 → detail「이 작업을 수행할 권한이…」
```

**R3·R4 의 판별식이 재는 것.** 「세 화면이 같은 계약을 따르는가」가 **아니다.**
「게이트 훅을 쓰는 소비처가 전부 **어느 쪽 계약을 따르는지 선언돼 있는가**」다.

**★R6 의 「진행 중」 정의.** `ImportForm` 은 `phase: 'form' | 'tracking' | 'done'` 과 `file` 을 갖는다
(`ImportForm.tsx:374-380`). 장부가 「파일 선택·jobId·진행률이 날아간다」라 적었으므로
**진행 중 = 파일이 선택됐거나 phase 가 `form` 이 아님**. 폴링만으로 좁히면 파일만 고른 사용자를 못 지킨다.

### 비기능 요구사항

- **네트워크 요청 증가 0.** 게이트와 정착 판정은 같은 queryKey 를 쓰므로 TanStack Query 가 합친다
  (현행 구조 유지 · `import.tsx:200-203` KDoc).
- **판별식 실행 비용** — 소스 스캔 1회, 기존 프론트 가드(`msw-single-setupserver.test.ts` 등)와 동급.
- **CI 잡 추가 0.** 판별식을 `apps/web` 에 두면 이미 도는 frontend-ci 안에서 실행된다.

### API 인터페이스 (REST)

**변경 없음.** 신규·수정 엔드포인트 0. 소비하는 계약은 기존
`GET /api/v1/users/me/project-permissions?projectKey=` · `GET /api/v1/projects/{key}` 둘뿐이다.

### 데이터 모델 변경

**없음.** 마이그레이션 0 · jOOQ 재생성 0.

### 엣지 케이스

| EC | 상황 | 기대 |
|---|---|---|
| EC1 | `projectKey` 빈 문자열 | 두 쿼리 `enabled:false` → `isLoading` 거짓. 로딩 화면에 갇히지 않는다(현행 보존) |
| EC2 | `IssueCreateForm` 에서 프로젝트 전환 | queryKey 가 바뀌어 pending 프레임이 **반복 재발**한다. 그 구간에도 폼은 열려 있어야 한다 |
| EC3 | 임포트 진행 중 `projectKey` 변경 | `key={projectKey}` 재마운트 → **진행 중 신호가 초기화돼야 한다.** 안 하면 게이트가 영구 동결된다 |
| EC4 | 동결 중 사용자가 제출 | 서버 403 → 기존 에러 문구 경로. 게이트 동결이 유출을 만들지 않는다 |
| EC5 | 재조회가 500·단절로 실패 | TanStack Query 가 이전 `data` 를 유지 → 게이트 불변(현행). 회귀 금지 |
| EC6 | 매핑 모드(`ImportMappingWizard`) | `ImportForm` 과 **같은 보호**를 받아야 한다. 게이트가 페이지 1곳에 있으므로 함께 덮인다 |
| EC7 | 판별식이 자기 자신·테스트 파일을 소비처로 오인 | 스캔 대상에서 테스트 파일 제외. **오탐 대조군을 함께 둔다** |
| EC8 | 임포트 중 프로젝트가 삭제됨(404) | 진행 중이면 부재 카드로도 교체하지 않는다 (리뷰 A2) |
| EC9 | `onBusyChange` 미배선인 3번째 임포트 모드 추가 | **required prop** 이라 타입이 막는다. 두 자식의 사용처는 라우트 1곳뿐(실측) |

### 제약 조건

1. **줄수 래칫 동결값을 건드리지 않는다.** `IssueCreateForm`(227) · `IssueMetaPanel`(317) 은
   `lint-ratchet-baseline.ts` 에 동결돼 있고 `max-lines-per-function` 은 **주석도 줄로 센다**
   (`skipComments` 미설정). 소스에 마커 주석을 넣는 방식은 두 동결값을 함께 올린다 —
   항목 `8`·`22`(다음 묶음)가 줄이려는 바로 그 두 함수다.
2. **E2E 강제 플래그는 전역이다.** `E2E_FORCE_CREATE_FALSE_KEY` 는 프로젝트별이 아니다.
   한 테스트 안에서 권한 있는 시나리오와 섞지 않는다. (테스트 **간** 격리는 Playwright 가 컨텍스트마다
   새 localStorage 를 만들어 보장한다 — `import.spec.ts:31-33` 이 실측으로 적어 둔 사실.)
3. **`key={projectKey}` 재마운트 가드가 공허해지지 않게 한다.** 로딩 프레임이 생기면 T-IM-4·T-IM-9 가
   무력해진다. 기존 테스트가 쓰는 `seedGateQueries` 방식(캐시 선충전)을 그대로 따른다.
4. **지연은 벽시계가 아니라 deferred 게이트로 만든다.** 벽시계 지연은 러너 부하로 「먼저 정착」이
   뒤집혀 간헐 실패가 된다(`CompleteSprintDialog.test.tsx:531` 선례).
5. **BC 격리 · 한 PR 한 BC.** apps/web 밖 소스 변경 0.

### 측정 가능한 완료 기준

- [ ] E2E 2 시나리오가 `pnpm --filter web test:e2e import` 에서 통과 (R1·R2)
- [ ] 게이트 훅 소비처 3곳이 전부 계약 선언을 갖는다 (R3)
- [ ] 차집합 판별식이 **양방향** — 선언에서 1건을 지우면 red · 소비처를 1곳 늘리면 red (R4 · 비-공허 짝)
- [ ] `IssueCreateForm` · `IssueMetaPanel` 에 pending 프레임 단언 신설, 각각 뮤테이션으로 red 확인 (R5)
- [ ] 진행 중 재조회 뒤집힘 테스트가 red→green (R6 — 권한·부재 **두 분기**) · 진행 중이 아닐 때
      두 카드 교체 테스트가 유지 (R7 비-공허 짝 2종)
- [ ] 동결 중 제출 403 이 권한 문구를 낸다 (R8) · `detail` 제거 뮤테이션으로 red 확인
- [ ] 동결이 판정을 가린 구간에만 `role="status"` 안내가 뜬다 (R9 · 비-공허 짝 포함) ·
      신규 문구가 `i18n/import-labels.ts` 안에 있다(하드코딩 0)
- [!] `lint-ratchet-baseline.ts` **무변경** (제약 1) — **미달성.** `ImportMappingWizard::Arrow function`
      288 → **289**(+1). 296 → 289 까지 내린 뒤의 구조적 최소치이고, 이탈이 겨눈 두 함수
      (`IssueCreateForm`·`IssueMetaPanel`)는 소스 0줄 변경이다. 사유는 §구현 결과 · 베이스라인 주석
- [ ] `pnpm verify` · `pnpm test:workflow` EXIT=0 · frontend-ci 전잡 green
- [ ] 눈확인 1회 — **느린 네트워크에서 로딩 → 판정 전환** (항목 15 가 지정한 유일한 눈확인 문장)

## Sanity Check

**gap 4건 발견 — 3건은 스스로 보강(❓), 1건은 게이트 1 에서 Maxi 판정.**

**❓ 발견 1 — 「진행 중」의 정의가 등재 본문에 없었다.** 장부는 「job 진행 중」이라고만 적었는데
실측하니 상태가 둘(`file` 선택 · `phase`)이다. 파일만 고르고 아직 제출 안 한 사용자도 잃을 것이 있다.
→ R6 에 정의를 명시하고 폴링 전용 해석을 배제했다.

**❓ 발견 2 — 진행 중 신호의 초기화 경로가 빠져 있었다.** `key={projectKey}` 재마운트와 겹치면
**게이트가 영구 동결**되는 실패 모드가 열린다(EC3). 이건 「고치려는 결함의 거울상」이라 반드시 테스트가 필요하다.
→ EC3 신설 + 완료 기준에 R7 대조군을 넣었다.

**❓ 발견 3 — 마커 주석 방식이 동결 베이스라인을 건드린다.** `max-lines-per-function` 이 주석을 세므로
소스 마커는 항목 `8`·`22` 가 줄이려는 두 함수의 동결값을 올린다.
→ 제약 1 로 못박고, 선언을 **중앙 레지스트리 + 차집합 판별식**으로 두는 안을 아래 D 로 올린다.

**★Maxi 확정 (2026-08-14) — 판별식의 배치·선언 형식 = A안.** 아래 표의 A 를 채택하고 B·C 를 기각했다.
결정 근거는 A 행의 사유 그대로다. 이 결정으로 §제약 1(동결 베이스라인 무변경)이 **구현 제약이 아니라
설계 결과**가 된다 — 소스 파일은 한 줄도 안 늘어난다.

| 안 | 선언 위치 | 트리거 | 문제 |
|---|---|---|---|
| **A (권고)** | `apps/web` 안 판별식 파일의 레지스트리 | **frontend-ci** (`paths: apps/web/**`) | 선언이 코드와 떨어져 있다 — 차집합 양방향이 그것을 덮는다 |
| B | 소스 호출부 마커 주석 | frontend-ci | **동결 베이스라인 2건 상승**(제약 1). 항목 `8`·`22` 와 정면 충돌 |
| C | `scripts/workflow/` 판별식 | workflow-scripts-ci | ❌ **`paths` 가 `apps/web/**` 를 의도적으로 제외** — 4번째 소비처를 추가하는 PR 에서 **0회 실행**된다 |

**권고 A.** 선례가 이미 여럿이다 — `apps/web` 안에서 소스를 fs 로 훑는 가드가
`msw-single-setupserver.test.ts` · `command-palette/boundary.test.ts` · `__tests__/skeleton-usage.test.ts`
등으로 실재하고, `frontend-ci` 는 `apps/web/**` 를 통째로 걸어 **소비처를 추가하는 그 PR 에서 반드시 돈다.**
C 는 이 저장소가 "가장 나쁜 사각"이라 부르는 형태(판별식을 무력화하는 PR 이 정확히 그 결함을 만드는 PR)다.

## Plan

**★이 계획을 관통하는 함정 1건.** `projects.$projectKey.settings.import.test.tsx:37,53` 은
`ImportForm` 과 `ImportMappingWizard` 를 **둘 다 `vi.mock` 으로 격리**한다. 여기에 새 prop
(`onBusyChange`)을 태우면 **목이 prop 을 삼켜 유닛은 전량 초록인데 실물 배선이 끊긴다** —
메모리 `[[mock-swallowed-prop-is-invisible-to-unit-tests]]` 의 양식 그대로다.
그래서 Task 6·7(자식이 신호를 낸다)과 Task 8(페이지가 신호를 받는다)을 **반드시 갈라 두고**,
Task 8 의 목은 신호를 **실제로 발화**하는 조작 가능한 목으로 만든다. 한쪽만 있으면 가짜 그린이다.

### Task 1. 임포트 CREATE 거부·프로젝트 미존재 E2E 2 시나리오 (항목 14 · R1·R2)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/import.spec.ts`]
- depends-on: []

**RED**: 기존 동작(PR #361 구현분)에 대한 **커버리지 신설**이라 처음부터 초록이다.
red 는 아래 뮤테이션으로 만든다 — 초록만 보고 넘어가면 이 테스트는 아무것도 안 지킨다.
- S4. `addInitScript` 로 `__bts_e2e_force_create_false='true'` → `loginAsAlice` →
  `/projects/ATLAS/settings/import` → 「생성 권한이 없습니다」 카드 + `h1` 유지 단언
- S5. 플래그 없이 `/projects/BOGUS/settings/import` → 「프로젝트를 찾을 수 없습니다」 카드

**GREEN**: 소스 변경 0. 스펙 파일만 추가한다.

**★이 E2E 는 MSW 위에서 돈다 — 서버 강제의 증거가 아니다 (리뷰 C2).** 「화면이 막혔다」로
백엔드 게이트의 안전을 주장하면 안 된다(`[[already-works-is-not-proof-unless-real-server]]`).
서버 몫은 이미 덮여 있다 — `ImportJobServiceTest.kt:108`
`accept throws ImportAccessDeniedException when actor lacks CREATE permission`.
이 문장을 스펙 파일 머리 주석에 남겨 다음 사람이 오독하지 않게 한다.

**REFACTOR**: 플래그 상수 미러 주석을 `field-permissions.spec.ts:31-32` 형식에 맞춘다
(src 상수를 직접 import 하지 않고 값만 동기화 + 출처 명시 — 기존 관례).

**검증**:
- `pnpm --filter web test:e2e import`
- 기존 E2E: `apps/web/e2e/import.spec.ts`(S1~S3) · `apps/web/e2e/import-mapping.spec.ts` —
  같은 파일에 시나리오를 더하므로 **전량 실행**으로 strict mode 위반 유무를 함께 본다
  (매핑 `28` 과 같은 양식의 사고를 이 PR 이 만들지 않게)
- 뮤테이션: `import.tsx` 의 `isCreateExplicitlyDenied` 분기를 지워 S4 red · `isProjectMissing`
  분기를 지워 S5 red 를 각각 눈으로 본다
- 눈확인: 없음(문구·레이아웃 변경 0)

### Task 2. `IssueCreateForm` pending 프레임 단언 (항목 15 · R5 · EC2)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/__tests__/IssueCreateForm.test.tsx`]
- depends-on: []

**RED**: 권한 응답을 **deferred 게이트**로 붙잡은 채(제약 4 — 벽시계 금지) 프로젝트를 고르고
제목을 입력해 제출하면 **네트워크가 나간다**를 단언한다. 기존 게이트 3테스트
(`:698`·`:718`·`:747`)는 성공·에러 **정착만** 덮어 이 프레임이 비어 있다.
- 실패 메시지 (예상): 지금은 초록이다 → 아래 뮤테이션으로 red 를 만든다

**GREEN**: 소스 변경 0. `open-while-loading` 은 **현행 동작**이고 이 task 는 그것을 고정한다.

**REFACTOR**: deferred 헬퍼를 파일 안 기존 MSW 유틸 옆에 두고, 테스트 종료 전 게이트를 열어
지연 쿼리 누수를 남기지 않는다(`CompleteSprintDialog.test.tsx:531` 규율).

**검증**:
- `pnpm --filter web test IssueCreateForm`
- 뮤테이션: `use-issue-create-permission-gate.ts` 를 `!isLoading && CREATE !== true` 로 바꿔 red
  (= 미지를 거부로 읽는 구현). 훅 KDoc 이 금지한 바로 그 형태다

### Task 3. `IssueMetaPanel` pending 프레임 단언 (항목 15 · R5)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.test.tsx`]
- depends-on: []

**RED**: 권한 응답 deferred 구간에서 **클론 액션이 보인다**를 단언한다.
- 실패 메시지 (예상): 초록 → 뮤테이션으로 red

**GREEN**: 소스 변경 0(현행 동작 고정).

**REFACTOR**: 단언 이름에 계약명(`open-while-loading`)을 넣어 Task 4 레지스트리와 말이 맞게 한다.

**검증**:
- `pnpm --filter web test IssueMetaPanel`
- 뮤테이션: Task 2 와 같은 훅 뮤테이션 1회로 두 테스트가 함께 red 인지 본다

### Task 4. 소비처→계약 레지스트리 + 차집합 판별식 (항목 15 · R3·R4)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/create/__tests__/permission-gate-loading-contract.test.ts`]
- depends-on: []

**RED**: 레지스트리를 **비운 채** 판별식을 먼저 쓴다 → 소스를 훑어 찾은 소비처 3곳이 전부
「선언 없음」으로 나와 red.
- 실패 메시지 (예상): `선언되지 않은 소비처 3건: routes/projects.$projectKey.settings.import.tsx, components/issue/IssueCreateForm.tsx, components/issue/IssueMetaPanel.tsx`

**GREEN**: 레지스트리에 3건을 계약과 함께 선언한다.
- 임포트 라우트 → `no-verdict-while-loading`
- `IssueCreateForm` · `IssueMetaPanel` → `open-while-loading`

**REFACTOR**: 스캔은 `apps/web/src` 만 훑고 **테스트 파일을 제외**한다(EC7).
JSDoc 에 「이 판별식이 재는 것은 세 화면이 **같은** 계약을 따르는가가 아니다」를 못박는다 —
폐기된 예약 PR #370 이 정확히 그 오독을 문서에 남겼다.

**검증**: `pnpm --filter web test permission-gate-loading-contract`

### Task 5. 판별식 비-공허 짝 2종 + 오탐 대조군 (항목 15 · R4)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/create/__tests__/permission-gate-loading-contract.test.ts`]
- depends-on: [4]

**RED**: 차집합 판정을 **순수 함수**로 뽑고 픽스처로 양방향을 잰다 — 소스 스캔 결과에 기대지
않으므로 파일이 어떻게 바뀌어도 이 짝은 살아 있다.
- ① 선언에 있는데 실제 소비처가 아님(stale 선언) → 위반
- ② 실제 소비처인데 선언 없음(4번째 화면) → 위반
- ③ 오탐 대조군 — 정확히 일치하면 위반 0

**GREEN**: 순수 함수 + 실물 스캔이 그 함수를 쓰도록 배선.

**REFACTOR**: 「위반 형태를 문서에 적으면 그게 위반으로 읽힌다」를 피한다 — 예시 문자열에
훅 이름을 그대로 쓰지 말고 픽스처가 조립해 갖게 한다(#383 에서 판별식이 자기 자신을 3종 오탐한 양식).

**검증**: `pnpm --filter web test permission-gate-loading-contract` — ①②가 red 로 뜨는지
**일부러 통과시켜** 1회 확인 후 원복

### Task 6. `ImportForm` 이 진행 중 신호를 낸다 (항목 16 · R6 자식측)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/import/ImportForm.tsx`, `apps/web/src/components/import/ImportForm.test.tsx`]
- depends-on: []

**RED**: `onBusyChange` 목을 넘기고 ① 마운트 직후 `false` ② 파일 선택 후 `true`
③ 제출해 `phase==='tracking'` 이면 `true` ④ 「다시 시도」로 `form` 복귀 + 파일 없음이면 `false`
를 단언 → prop 이 없어 컴파일·실행 실패.
- 실패 메시지 (예상): `onBusyChange is not a function` / 타입 에러

**GREEN**: `onBusyChange: (busy: boolean) => void` — **required prop** (리뷰 A1 · EC9).
optional 로 두면 3번째 모드가 조용히 미배선된다. 사용처가 라우트 1곳뿐이라 required 비용은 0.
`useEffect(() => onBusyChange(isBusy(file, phase)), [file, phase, onBusyChange])`.

**★deps 에 `onBusyChange` 를 반드시 넣는다 (리뷰 B1).** 빼면 `react-hooks/exhaustive-deps` 가
운다. **disable 주석으로 덮으면 안 된다** — `lint-ratchet.test.ts` 가 `noInlineConfig` 로 그
주석을 무시하고 다시 잡는다. 부모가 `useState` setter(안정 참조)를 그대로 넘기므로 재실행 폭주는 없다.

**REFACTOR**: 「진행 중」 판정식을 컴포넌트 밖 `isBusy(file, phase)` 로 빼고 근거 주석(스펙 R6)을 단다.
**줄수 확인** — `ImportForm` 함수는 동결 대상이 아니지만 200줄 상한 자체는 살아 있다.

**검증**: `pnpm --filter web test ImportForm`

### Task 7. `ImportMappingWizard` 가 진행 중 신호를 낸다 (항목 16 · EC6)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/import/mapping/ImportMappingWizard.tsx`, `apps/web/src/components/import/mapping/ImportMappingWizard.test.tsx`]
- depends-on: []

**RED**: 같은 계약으로 ① 초기 `upload` 단계·파일 없음 → `false` ② 파일 선택 또는 다음 단계 진입
→ `true` 를 단언 → prop 부재로 실패.

**GREEN**: `ImportForm` 과 **같은 prop 이름·같은 의미·같은 required 여부**로 구현한다.
상태는 최상위 컴포넌트가 갖고 있다(실측 `:783-785` `step`·`format`·`file`) — 자식으로 내려갈 필요 없다.
deps 규율은 Task 6 과 동일(리뷰 B1).

**REFACTOR**: 동결값(`Arrow function` 288)을 넘기지 않게 판정식을 컴포넌트 밖으로 뺀다.
**`lint-ratchet-baseline.ts` 는 건드리지 않는다**(제약 1).

**검증**: `pnpm --filter web test ImportMappingWizard` + `pnpm --filter web test lint-ratchet`

### Task 8. 페이지가 신호를 받아 게이트를 동결한다 (항목 16 · R6·R7·EC3·EC4)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.import.tsx`, `apps/web/src/routes/projects.$projectKey.settings.import.test.tsx`, `apps/web/src/i18n/import-labels.ts`]
- depends-on: [6, 7]

**RED**: 새 `describe('재조회 중 화면 유지')` 8종.
- ① **R6** 진행 중(목이 `onBusyChange(true)` 발화) → 권한 캐시를 `CREATE:false` 로 뒤집고
  refetch → **폼이 그대로 있고** 거부 카드가 없다
- ② **R7 비-공허 짝** 진행 중이 아님 → 같은 뒤집기 → **거부 카드로 교체된다**
- ③ **EC8** 진행 중 → 프로젝트 조회를 404 로 뒤집기 → **부재 카드로도 교체되지 않는다** (리뷰 A2)
- ④ **EC8 비-공허 짝** 진행 중이 아님 → 같은 404 → **부재 카드로 교체된다**
- ⑤ **EC3** 진행 중 상태에서 `projectKey` 변경 → 재마운트로 신호가 `false` 로 초기화되어
  게이트가 다시 산다 (영구 동결 방지 — 이 결함의 거울상)
- ⑥ **EC5 무회귀** 재조회가 500 이면 이전 `data` 가 유지돼 게이트가 흔들리지 않는다
- ⑦ **R9** 동결 중이면 안내가 보이고 `role="status"` 다 · **비-공허 짝** 동결이 아니면 안 보인다
- ⑧ **R8 · EC4** 동결 중 제출 → MSW 가 403 + `detail:"이 작업을 수행할 권한이 없습니다."` →
  **그 문구가 뜬다**(「잠시 후 다시 시도하세요」가 아니다). 리뷰 C1 — 이 계약은 지금 맞게
  동작하지만(백엔드 `ImportExceptionHandler.kt:70-76` ↔ 프론트 `ImportForm.tsx:44-53`)
  **아무것도 검사하지 않는다.** `detail` 이 사라지면 폴백이 「잠시 후 다시 시도」라 권한이
  회수된 사용자에게 영원히 틀린 안내가 된다 — 매핑 `17` 이 닫은 것과 같은 결함 양식이다.

**★목 교체.** `vi.mock` 의 `ImportForm`/`ImportMappingWizard` 를 **`onBusyChange` 를 실제로
발화하는 목**으로 바꾼다(테스트가 누르는 버튼 1개). 지금 목은 `projectKey` 만 읽어 prop 을
통째로 삼킨다 — 그대로 두면 ①②③이 전부 가짜 그린이다.

**GREEN**: 페이지에 `const [isImportInProgress, setIsImportInProgress] = useState(false)` 를 두고
(명명은 리뷰 B2) **부재·거부 두 분기 앞에** 동결을 건다 — 로딩 → **진행 중이면 현재 화면 유지** →
부재 → 거부 → 폼. 두 자식에 `onBusyChange={setIsImportInProgress}` 전달(setter 는 안정 참조라
Task 6·7 의 deps 규율과 맞는다).

**R9 안내** — 동결이 실제로 판정을 가린 구간(`isImportInProgress && (isProjectMissing || isCreateDenied)`)
에서만 `role="status"` 한 줄을 폼 **위에** 렌더한다. 문구는 `import-labels.ts` 신규 키,
색은 DESIGN.md §C 시맨틱 상태색. **진행 중이지만 판정이 멀쩡하면 안 띄운다** — 그게 비-공허 짝이다.

**REFACTOR**: §스펙의 판정 흐름 ASCII 다이어그램을 이 파일 KDoc 에 옮겨 넣는다(리뷰 A3 —
`ImportForm.tsx:355-368` 의 phase 다이어그램이 이미 같은 관례다). 함께 적을 것 —
「게이트는 사전 신호이고 최종 판정은 서버 403 이다」(훅 KDoc·`ImportJobService.kt:36-38` 와 같은 말),
**동결이 유출이 아닌 이유**, 그리고 **왜 두 분기 다 동결하는가**(같은 결함의 두 문 · 리뷰 A2).

**검증**:
- `pnpm --filter web test settings.import`
- 기존 E2E: `apps/web/e2e/import.spec.ts` · `apps/web/e2e/import-mapping.spec.ts`
  (계약 §5 사전 grep — 이 화면을 타는 스펙 전량)
- 눈확인: **느린 네트워크에서 로딩 → 판정 전환** (항목 15 가 지정한 유일한 눈확인) — 라이트/다크

### Task 9. 뮤테이션 비-공허 확인 전수 + 최종 검증 (완료 기준 마감)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/create/use-issue-create-permission-gate.ts`, `apps/web/src/routes/projects.$projectKey.settings.import.tsx`, `apps/web/src/components/import/ImportForm.tsx`]
- depends-on: [1, 2, 3, 4, 5, 6, 7, 8]

**RED/GREEN 없음 — 검증 전용 task.**
**★반드시 GREEN 을 먼저 커밋한 뒤 시작한다.** 미커밋 상태에서 뮤테이션을 원복하면 소실이다
(CLAUDE.md 함정 · #383 에서 실물 1회 발생).

뮤테이션 목록(각각 red 를 눈으로 보고 `git checkout --` 로 원복).
- M1 게이트 훅을 `!isLoading && CREATE !== true` → Task 2·3 red
- M2 페이지 `isCreateExplicitlyDenied` 분기 제거 → Task 1 S4 red
- M3 페이지 `isProjectMissing` 분기 제거 → Task 1 S5 red
- M4 동결 조건을 거부 분기에서만 제거 → Task 8 ① red
- M5 `onBusyChange` 호출 제거(`ImportForm`) → Task 6 red **+ Task 8 ①이 함께 red 인지 확인**
  (목이 prop 을 삼키는지 여부가 여기서 드러난다)
- M6 레지스트리에서 1건 삭제 → Task 4 red
- M7 레지스트리에 가짜 경로 1건 추가 → Task 5 ① red
- M8 동결을 부재(404) 분기에서만 제거 → Task 8 ③ red (리뷰 A2 가 넓힌 몫이 실제로 지켜지는가)
- M9 MSW 403 응답에서 `detail` 제거 → Task 8 ⑧ red (리뷰 C1 의 계약 의존이 실제로 검사되는가)
- M10 안내 조건을 `isImportInProgress` 단독으로 넓힘 → Task 8 ⑦ 비-공허 짝 red
  (판정이 멀쩡할 때도 안내가 뜨면 그건 경고 피로다)

**검증**: `pnpm verify` · `pnpm test:workflow` · `node scripts/build-doc-index.mjs --check` ·
`bash scripts/verify-master-plan.sh` 전부 EXIT=0 · `lint-ratchet-baseline.ts` diff 0줄

## Plan 메타

- **task 수**: 9 · **예상 wave**: 3
  (wave1 = Task 1·2·3·4·6·7 파일 교집합 0 → 병렬 / wave2 = Task 5·8 / wave3 = Task 9)
- **구현 규율**: TDD red-first (T2). 단 Task 1·2·3 은 **기존 동작에 대한 커버리지 신설**이라
  red 를 뮤테이션으로 만든다 — 이 사실을 숨기지 않고 각 task 에 적었다.
- **추가 검증**: typecheck · eslint · vitest · playwright(import 2종) ·
  `pnpm test:workflow` · 줄수 래칫 · 문서 인덱스 · master-plan 정합
- **백엔드 검증 없음** — ktlint·detekt·gradle 대상 파일 0.

## 구현 결과 (← /bts-impl)

**뮤테이션 10종 실측 — 2건이 계획의 예상을 뒤집었다.**

| 뮤테이션 | 결과 | 잡은 것 |
|---|---|---|
| M1 게이트를 `!isLoading && CREATE !== true` 로 | ⚠️ **신규 2건을 못 잡음** | 로딩 중엔 `!isLoading` 이 거짓이라 게이트가 꺼진다 — pending 프레임을 안 건드린다 |
| **M1′** 게이트를 `CREATE !== true` 로(로딩 가드 없이) | ✅ red | pending 프레임 단언 2건 정확히 |
| M2 거부 분기 제거 | ✅ red | E2E S4 |
| M3 부재 분기 제거 | ✅ red | E2E S5 |
| M4 거부 분기 동결 제거 | ✅ red | R6 · R9 |
| M5 `ImportForm` 신호 호출 제거 | ⚠️ **자식 4건 red · 페이지 초록** | 아래 ★ |
| M6 레지스트리 1건 삭제 | ✅ red | 선언되지 않은 소비처 |
| M7 레지스트리에 가짜 경로 | ✅ red | 썩은 선언 |
| M8 부재 분기 동결 제거 | ✅ red | EC8 |
| M9 403 `detail` 추출 제거 | ✅ red | EC4 + 선재 403 테스트 2건 |
| M10 안내 조건을 진행중 단독으로 | ✅ red | R9 비-공허 짝 |

**★M1 의 교훈.** 「미지를 거부로 읽는다」에는 **두 형태**가 있고 둘이 서로 다른 것을 깬다.
`!isLoading && CREATE !== true` 는 **정착한 미지**(조회 실패)를 거부로 읽고,
`CREATE !== true` 는 **로딩 중**까지 거부로 읽는다. 훅 KDoc 이 경고한 것은 전자인데
이 PR 이 새로 고정한 pending 프레임 계약을 깨는 것은 **후자**다. 뮤테이션을 전자로만
돌렸다면 신규 단언 2건이 비-공허라고 **잘못 결론**낼 뻔했다.

**★M5 의 교훈 — 목이 프롭을 삼키는 사각은 없앤 것이 아니라 갈라 둔 것이다.**
실물 `ImportForm` 의 신호 호출을 지워도 **페이지 테스트는 초록**이다. 페이지 목이 신호를
스스로 발화하기 때문이다. 이건 목을 쓰는 한 구조적이라 없앨 수 없다 — 대신
① 자식 테스트가 **실물이 신호를 내는가**를, ② 페이지 테스트가 **부모가 받아 쓰는가**를
따로 잡는다. 둘을 잇는 것(프롭 이름 일치)은 테스트가 아니라 **타입**이 강제한다
(`onBusyChange` 가 required 라 이름을 바꾸면 컴파일 에러). 계획의 방어가 실제로 필요했음이 실증됐다.

**★계획이 빠뜨린 테스트 1건을 뮤테이션 준비 중 발견했다.** 리뷰 C1(P1)이 요구한
R8·EC4(403 문구) 테스트를 Task 8 RED 를 쓸 때 R9 2건으로 대체해 버렸다. M9 를 준비하며
「이걸 잡을 테스트가 없다」로 드러나 `ImportForm.test.tsx` 에 추가했다(커밋 `623102102`).

**⚠️ 절차 이탈 2건.**
1. **`lint-ratchet-baseline.ts` 를 1줄 올렸다** (`ImportMappingWizard::Arrow function` 288 → 289).
   완료 기준은 「무변경」이었다. 진행 중 신호를 본문에 남기는 최소가 훅 호출 1줄이라 구조적으로
   288 을 못 지킨다 — 효과 본문·판정식·KDoc 을 전부 컴포넌트 밖으로 빼고 props 구조분해도
   1줄로 되돌려 **296 → 289** 까지 내린 뒤의 값이다. 이탈이 겨눈 위험(항목 `8`·`22` 가 줄이려는
   두 함수)은 건드리지 않았다 — `IssueCreateForm`·`IssueMetaPanel` 은 **소스 0줄 변경**이다.
2. **서브에이전트 없이 인라인 구현했다**(세션 제약). 잃은 독립 verifier 자리는 [6] 리뷰가 메운다.

## 게이트 2 대응 (← /bts-codereview · Maxi 확정 A)

리뷰 2종 모두 **BLOCKER 0**. 재현된 지적 3건 + 권고 1건을 **머지 전에 닫았다**(Maxi 확정 A안).

| | 지적 | 재현 | 처리 |
|---|---|---|---|
| **A** | 마법사 동결 배선이 **무검증** — `onBusyChange` 를 `() => {}` 로 바꿔도 타입체크 통과 + 테스트 40/40 초록. 목의 `mock-wizard-busy-on` 은 정의 1곳·클릭 **0곳**인 죽은 버튼이었다 | ✅ | 페이지 테스트 2건(EC6 + 비-공허 짝) 신설. **같은 뮤테이션이 이제 red** |
| **B** | 장부 미갱신 — 14·15·16 이 ⬜ 로 남고 §PR별 집계에 행 없음. 판별식은 두 목록이 **서로만** 검사해 둘 다 안 고치면 초록 | ✅ | 3항목 ✅ 전환 + 표 3행 + 집계 행 + **신규 7건 등재**(33~39) |
| **C** | 판별식이 **별칭 import 에 눈이 먼다** — `as useGate` 로 쓰면 6/6 초록 | ✅ | 탐지를 호출부 → **import 문**으로 교체. `from '…'` 로 좁혀 KDoc 언급 오탐 회피. **alias 프로브가 이제 red** |
| **D** | 안내 문구가 404 경로에서 **틀린 사유**를 말한다(유보 조건은 권한·부재 둘 다인데 문구는 권한만) | ✅ | 사유 중립 문구로 교체 + 404 경로 안내 테스트 1건. 「제출이 거부될 수 있습니다」도 뺐다 — 마법사 confirm 은 거부가 아니라 **접수 뒤 뒤늦게 실패**한다 |

**닫지 않고 등재한 것.** 매핑 `35`(서버 confirm CREATE 재검사 부재 — cross-BC 라 별도 PR) ·
`37`(레지스트리 계약 **값** 미검증) · `38`(자식 효과 cleanup 부재) · `36`(DESIGN.md aria-live 정책) ·
`33`·`34`(하네스) · `39`(리뷰 렌즈가 같은 worktree 를 동시 뮤테이션 — 이번에 실측됐다).

**교차 검증에서 갈린 것.** 래칫 288→289 를 적대적 렌즈는 「287까지 여지 있음」, `code-reviewer` 는
**baseline 을 1로 낮춰 실측해 289·슬랙 0** 확인 후 「실질적 여지 없음」. 실측한 쪽을 채택했다.

**⚠️ 완료 기준 정정.** §측정 가능한 완료 기준의 「`lint-ratchet-baseline.ts` **무변경**」 줄은
**지켜지지 않았다**(289 로 +1). 이탈 사유는 그 파일 주석과 §구현 결과에 있다.

## 리뷰 결과

### 렌즈 라우팅 — 표에 정의되지 않은 조합에 들어왔다

`bts-review-plan` 분기 표에서 `type=ui` 행은 **「이 표 미진입」**이다(ui 는 보통 T1 이라 이 단계에
오지 않는다). 항목 `16` 때문에 T2 로 선언되어 도달했고, 그 행이 주는 렌즈는 `plan-design-review`
**1종**뿐인데 T2 는 **2종**을 요구한다. 표 자신의 탈출구(「분기 미정의 상태로 조용히 지나가지
않는다」)를 따라 **`plan-eng-review` + `plan-design-review` 2종**을 돌렸다. → 등재 후보 2.

### plan-eng-review — 지적 7건 · BLOCKER 0 · 전량 반영

| # | 등급 | 확신 | 지적 | 처리 |
|---|---|---|---|---|
| A1 | P2 | 9/10 | `onBusyChange` 가 optional 이면 3번째 모드가 조용히 미배선 (`import.tsx:234,236` 이 유일 사용처) | **required prop** — Task 6·7 · EC9 |
| A2 | P2 | 9/10 | `isProjectMissing` 분기도 focus 재조회를 타는데 동결 대상에서 빠졌다 | **Maxi 확정 A** — 두 분기 동결 · R6 확장 · EC8 |
| A3 | P3 | 9/10 | 판정 상태기계 다이어그램 부재 | §스펙에 신설 + Task 8 REFACTOR 가 KDoc 으로 이관 |
| B1 | P1 | 8/10 | `exhaustive-deps` 를 disable 주석으로 덮으면 `noInlineConfig` 래칫이 다시 잡는다 | Task 6 deps 규율 명문화 |
| B2 | P3 | 7/10 | `isChildBusy` 명명 | `isImportInProgress` — Task 8 |
| C1 | P1 | 9/10 | **EC4 에 대응하는 task 가 없었다.** 403 `detail` ↔ 프론트 폴백 문구 의존을 아무것도 검사 안 함 | Task 8 ⑦ + 뮤테이션 M9 |
| C2 | P2 | 8/10 | 항목 14 E2E 는 MSW 위 — 서버 강제의 증거가 아니다 | Task 1 에 명시 + `ImportJobServiceTest.kt:108` 인용 |

**성능 0건.** `useEffect` 1개 · 렌더 분기 1개 · 네트워크 증가 0(같은 queryKey 합류).

**Prior learning applied**. `already-works-is-not-proof-unless-real-server` (9/10, 2026-07-27) → C2.

### plan-design-review — 종합 5/10 → 9/10 · 결정 1건 · BLOCKER 0

분류 **APP UI** · 하드 리젝션 **0/7** · 리트머스 6 YES · 1 N/A(모션 없음, 필요도 없음).
**목업 생성 안 함** — 이 PR 이 그리는 화면 4종은 전부 이미 배포됐고 픽셀이 안 바뀐다.
없는 화면을 지어내면 실물과 어긋난 참조를 남긴다.

| 패스 | 전 | 후 | 처리 |
|---|---|---|---|
| 1 정보 위계 | 8 | 9 | 판정 흐름 다이어그램(리뷰 A3)이 이미 메웠다 |
| 2 상태 커버리지 | **6** | **9** | 「동결」 상태의 사용자 체감이 없었다 → §상호작용 상태표 신설 + R9 |
| 3 사용자 여정 | **5** | **9** | 권한 회수 경로의 감정 곡선 부재 → R9 가 「마지막에 놀람」을 제거 |
| 4 AI 슬롭 | 9 | 9 | 새로 그리는 것 0. 기존 카드는 시맨틱 토큰 사용 |
| 5 디자인 시스템 | 7 | 9 | 신규 컴포넌트 0. R9 문구는 i18n + §C 상태색으로 못박음 |
| 6 반응형·접근성 | **5** | **9** | 동결 전환이 스크린리더에 무음이었다 → `role="status"` |
| 7 미해결 | — | 0 | D5 로 해소 |

**★D5 (Maxi 확정 A).** 동결하면 화면이 동결 전과 **완전히 같아** 사용자는 제출할 때까지 모른다.
상태를 지키는 것과 사실을 말하는 것은 배타적이 아니다 → 비-파괴적 안내(R9).

**선재 관측 2건 (이 PR 이 만든 것 아님 · 고치지 않는다).**
① 로딩 프레임이 DESIGN.md 등재 `Skeleton` 프리미티브 대신 「로딩 중...」 평문을 쓴다.
② **DESIGN.md 에 `aria-live` 정책이 아예 없다**(전수 grep 0건). → 등재 후보 4.

### 무엇이 이미 있는가 (재사용)

| 필요한 것 | 이미 있는 것 | 이 계획의 처리 |
|---|---|---|
| 로딩 프레임 계약 구현 | `settings.workflow-scheme.tsx:92-98` · 임포트 라우트(#361) | 복제하지 않고 **선언만** 한다 |
| E2E 권한 강제 도구 | `E2E_FORCE_CREATE_FALSE_KEY` + `field-permissions.spec.ts:358-364` | 그대로 쓴다 |
| 소스 스캔 가드 선례 | `msw-single-setupserver` · `command-palette/boundary` · `skeleton-usage` | 같은 형식으로 신설 |
| 서버측 CREATE 게이트 | `ImportJobService` fail-fast + `ImportJobServiceTest.kt:108` | 인용만 — 백엔드 변경 0 |
| 403 문구 | `ImportExceptionHandler.kt:70-76` → `ImportForm.tsx:44-53` | 동작을 **테스트로 고정**(C1) |

### 범위 밖 (NOT in scope)

- **백엔드 403 문구 계약의 판별식** — cross-BC 라 「한 PR 한 BC」에 걸린다. 프론트 반쪽만 이 PR 이 잠근다. → 등재 후보 3
- **항목 8·22(IssueCreateForm 줄수)** — 정본 §순서 제약이 이 묶음 **다음**으로 고정. 동결값 무변경이 그 전제다
- **게이트 훅 이외의 `useProjectPermissions` 소비처 ~20곳** — 이 항목의 계약은 **게이트 훅** 소비처에 한정
- **분할 옵션(미채택)** — 테스트 전용 PR(14+15) / 동작 변경 PR(16) 로 가르면 파일 교집합 0 이라 가능하다. 장부가 이 묶음을 확정해 뒀고 CI 왕복이 2배라 한 PR 로 간다

### 장부 등재 후보 3건 (게이트 1 에서 판정)

1. **`classify-task` 가 E2E 를 포함한 혼합 PR 을 `qa-engineer` 로 오배정한다.** 판정 순서상
   `qa`(5번)가 `ui`(7번)보다 앞서 제목에 「E2E」가 있으면 **위치 무관하게** qa 로 떨어지고
   (제목 순서를 바꿔 2회 실측), qa-engineer 는 구현 코드 수정이 금지된 에이전트다.
2. **`bts-review-plan` 분기 표에 `ui`@T2 가 미정의다.** ui 행은 「미진입」인데 실제로 도달했고
   렌즈 1종만 줘 T2 의 2종을 못 채운다.
3. **임포트 403 의 `detail` ↔ 프론트 폴백 문구가 서로를 검사하지 않는다.** 이 PR 이 프론트 반쪽만
   고정한다. 백엔드에서 `detail` 이 사라지면 권한 회수 사용자가 「잠시 후 다시 시도」를 영원히 읽는다.
4. **DESIGN.md 에 `aria-live` 정책이 없다.** 전수 grep 0건. 화면이 조용히 바뀌는 자리마다
   `role="status"`/`alert` 선택이 개발자 재량으로 남고, 이 PR 이 그 판단을 한 번 더 즉흥으로 한다.

### 병렬화

| 레인 | task | 건드리는 모듈 |
|---|---|---|
| A | 1 | `apps/web/e2e/` |
| B | 2 → 3 | `apps/web/src/components/issue/` (교집합 있음 — 직렬) |
| C | 4 → 5 | `apps/web/src/components/issue/create/__tests__/` |
| D | 6 → 7 → 8 | `apps/web/src/components/import/` · `apps/web/src/routes/` (8 이 6·7 의뢰) |

A·B·C·D 동시 착수 → 전부 머지 후 Task 9(뮤테이션)는 **GREEN 선커밋 뒤 단독**.
⚠️ 레인 B 와 C 는 `components/issue/` 아래를 함께 건드리나 파일 교집합은 0 이다.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | clean | 7 issues, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | clean | score: 5/10 → 9/10, 1 decision |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**CROSS-MODEL:** 두 렌즈가 **같은 곳을 독립적으로 가리켰다** — eng 는 「동결이 만드는 새 상태에
대응 task 가 없다」(C1), design 은 「동결 상태의 사용자 체감이 없다」(패스 2·3·6). 한쪽은 계약
검사(R8), 한쪽은 사용자 신호(R9)로 갈라져 둘 다 남았다.

**VERDICT:** ENG + DESIGN CLEARED — 지적 8건 전량 계획에 반영, BLOCKER 0. 🛑 게이트 1 대기.

NO UNRESOLVED DECISIONS
