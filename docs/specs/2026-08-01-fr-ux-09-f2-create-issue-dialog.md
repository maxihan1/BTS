# FR-UX-09 F2 — 이슈 생성 모달 (CreateIssueDialog) 스펙

> 날짜: 2026-08-01
> FR: **FR-UX-09** (§4.7 D2/D6)
> BC: issue-tracking (프론트 소비 — 백엔드 변경 0)
> PR: #331
> ADR: [2026-08-01-fr-ux-09-f2-create-issue-dialog](../decisions/2026-08-01-fr-ux-09-f2-create-issue-dialog.md)
> plan: [2026-08-01-fr-ux-09-f2-create-issue-dialog](../plans/2026-08-01-fr-ux-09-f2-create-issue-dialog.md)

## 배경 — 착수 전 실측이 뒤집은 전제 2건

1. **`routes/issues.new.tsx` 는 이미 폼과 라우트가 분리돼 있다.** 파일 1행 주석이
   *"IssueCreateForm(라우터 비의존) + IssueCreateRouteAdapter(useNavigate 연결)"* 로 명시한다.
   → 모달화는 **재작성이 아니라 감싸기**다.
2. **기존 생성 폼은 2필드가 아니다.** `projectKey`·`summary` 외에
   `ComponentMultiSelect`(FR-CM-03) · `IssueSecurityLevelSelect`(FR-PM-06) ·
   `CustomFieldInput` × N(FR-IS-10, required 검증 포함) · URL `summary` 프리필(FR-UX-04 FR7)이
   이미 있다. F2 가 5필드를 더하면 **총 10종 + 커스텀 필드 N개**다.

## 사용자 시나리오 (Given-When-Then)

### S1. 상단바에서 이슈 만들기 (주 경로)

- **Given** 로그인한 사용자가 임의의 화면(보드·백로그·이슈 목록 등)에 있다.
- **When** 상단바 **만들기** 버튼을 누른다.
- **Then** **URL 이 바뀌지 않은 채** 생성 모달이 그 자리에서 열린다.
  프로젝트는 **활성 프로젝트**(FR-UX-07)가 미리 선택돼 있고, 이슈 유형은 **작업(task)** 이 기본이다.

### S2. 한 번의 제출로 7필드 확정

- **Given** 생성 모달이 열려 있다.
- **When** 프로젝트·유형·제목·본문·담당자·우선순위·라벨을 채우고 **만들기** 를 누른다.
- **Then** `POST /api/v1/issues` **1회**로 이슈가 만들어지고, 모달이 닫히며
  생성된 이슈 상세로 이동한다. 담당자·우선순위·라벨이 **이미 반영된 상태**다
  (후속 `PATCH` 0회 — B1 이 연 계약을 소비).

### S3. 담당자를 비워두면 자동 배정이 유지된다

- **Given** 생성 모달에서 담당자 칸을 **한 번도 건드리지 않았다**.
- **When** 만들기를 누른다.
- **Then** 요청 본문에 `assigneeId` **키 자체가 없고**, 서버의 자동 배정
  (`resolveDefaultAssignee` — 컴포넌트 리드 → 프로젝트 리드)이 그대로 동작한다.

### S4. 담당자를 명시적으로 비우면 미할당으로 확정된다

- **Given** 생성 모달에서 담당자를 골랐다가 **해제** 버튼을 눌렀다.
- **When** 만들기를 누른다.
- **Then** 요청 본문에 `assigneeId: null` 이 실려 **자동 배정이 비활성**되고 미할당으로 확정된다.
- **핵심.** S3 와 S4 는 **화면상 똑같이 "비어 있음"** 이지만 서버 동작이 다르다.
  이 차이를 사용자가 알 수 있도록 담당자 칸에 안내 문구를 노출한다 (FR-6).

### S5. 딥링크로 들어와도 같은 모달을 본다

- **Given** 사용자가 `/issues/new` 를 북마크했거나 `c` 단축키·커맨드 팔레트로 이동했다.
- **When** 그 주소로 진입한다.
- **Then** 이슈 목록 위에 **같은 생성 모달**이 열린 상태로 렌더된다.
  모달을 닫으면 `/issues` 로 이동한다.

### S6. 본문을 비우면 프로젝트 템플릿이 들어온다

- **Given** 프로젝트에 이슈 템플릿(FR-TM-01)이 설정돼 있다.
- **When** 본문을 비운 채 만들기를 누른다.
- **Then** 서버가 템플릿으로 본문을 채운다(기존 동작). 본문 칸에 그 사실을 안내한다.

## 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| **FR-1** | `CreateIssueDialog` 는 `open`·`onOpenChange` **제어 컴포넌트**다. URL 을 스스로 읽거나 바꾸지 않는다. (F3 의 보드·백로그 진입점이 URL 변경 없이 열 수 있어야 하므로 **이것이 설계 조건**이다.) |
| **FR-2** | 폼 본체는 기존 `IssueCreateForm` 을 확장해 **한 벌만** 유지한다. 모달과 라우트가 같은 컴포넌트를 감싼다. |
| **FR-3** | 프로젝트 입력을 **자유 텍스트 → 셀렉터**로 바꾼다. 목록은 `useProjects(archived=false)`, 기본 선택은 활성 프로젝트(`use-resolved-active-project`). |
| **FR-4** | 이슈 유형 셀렉터를 추가한다. 목록은 `fetchIssueTypes()`(전역 5종), 기본값은 `key === 'task'`(서버 fallback 과 일치). `meta/IssueTypeSelect` 재사용. |
| **FR-5** | 본문(`description`) 입력을 추가한다. |
| **FR-6** | 담당자 칸을 추가한다. `meta/IssueAssigneeSelect` 재사용(`currentAssignee=null`·`canEdit=true`). 검색은 `useUsers(debounced)`. **"비워두면 자동으로 배정됩니다"** 안내를 노출한다. |
| **FR-7** | 우선순위 셀렉터를 추가한다. `meta/IssuePrioritySelect` 재사용, 기본값 **3(보통)**. |
| **FR-8** | 라벨 입력을 추가한다. `IssueLabelsEdit` 에서 **`LabelChipsEditor` 를 추출**해 생성 폼은 편집기만 쓴다(저장 버튼 없음). 상세 화면의 `IssueLabelsEdit` 는 `LabelChipsEditor + 저장 버튼` 래퍼로 **동작 무변경**. |
| **FR-9** | `CreateIssueInput` 에 `typeId`·`description`·`assigneeId`·`priority`·`labels` 를 추가하고 `createIssue()` 가 본문에 싣는다. |
| **FR-10** | **`assigneeId` 3-state.** 폼 상태는 `string \| null \| undefined`. `undefined` 면 **본문에서 키를 뺀다**(자동 배정 유지), `null` 이면 `null` 을 싣는다(미할당 확정), 값이면 그 값을 싣는다. |
| **FR-11** | `routes/issues.new` 는 모달을 `open` 으로 마운트하는 얇은 어댑터가 된다. 닫으면 `/issues` 로 navigate. |
| **FR-12** | 상단바 만들기 버튼은 **로컬 상태로 모달을 연다**(URL 불변). 단축키 `c`·커맨드 팔레트는 현행 `/issues/new` navigate 를 **유지**하며, 라우트가 모달을 렌더해 결과적으로 같은 화면에 도달한다. |
| **FR-13** | 기존 필드(컴포넌트 다중선택 · 보안등급 · 커스텀 필드 N · URL `summary` 프리필)는 **전부 유지**한다. |
| **FR-14** | **MSW `createIssueHandler` 가 신규 5필드를 실제로 반영해야 한다** (아래 §B-1 BLOCKER). 요청 본문 타입에 `typeId`·`description`·`assigneeId`·`priority`·`labels` 를 추가하고, 응답을 fixture 스프레드가 아니라 **요청 값 기반**으로 만든다. `assigneeId` 는 **키 부재 / `null` / 값** 3-state 를 구분한다 — 키가 없을 때만 기존 자동 배정 시뮬레이션을 돌린다. 미존재 사용자 sentinel(`'__NOT_FOUND__'`)에 **422 `ASSIGNEE_NOT_FOUND`** 를 돌려준다. |

## 비기능 요구사항 (NFR)

| ID | 요구사항 | 판정식 |
|---|---|---|
| **NFR-1** | 모달의 접근 가능한 이름이 **고유**해야 한다 (`role="dialog"` 가 e2e 에 164 발생). | `DialogTitle` 에 `새 이슈 만들기` 를 두어 `getByRole('dialog', { name: '새 이슈 만들기' })` 가 단건 매칭 |
| **NFR-2** | 필드 10종+N 이라 세로가 길다. 본문 영역만 스크롤하고 **만들기 버튼은 항상 보인다**. | 본문 래퍼 `max-h-[60vh] overflow-y-auto` (`GadgetCatalogModal:117` 선례) + `DialogFooter` 는 스크롤 영역 밖 |
| **NFR-3** | 레이아웃은 기존 관행을 따른다 — **단일 컬럼**. 폭만 `max-w-md` → `max-w-xl`. | 기존 Dialog 소비처 20곳이 전부 단일 컬럼 |
| **NFR-4** | 모달이 닫혀 있을 때 프로젝트·유형·컴포넌트·커스텀필드 쿼리를 **쏘지 않는다**. | 각 훅 `enabled` 가드 |
| **NFR-5** | WCAG AA — 터치 타깃 44px, 포커스 트랩, Esc 닫기. | 재사용 컴포넌트가 이미 `min-h-[44px]` 보유 · Radix Dialog 기본 제공 |
| **NFR-6** | 사용자 문자열은 `i18n/ko.ts` 에 둔다. | 컴포넌트 내 한글 리터럴 0 |

## API 인터페이스 (REST)

**신규 엔드포인트 0.** 기존 `POST /api/v1/issues` 를 소비할 뿐이다.

```
POST /api/v1/issues
{
  "projectKey": "ATLAS",          // 필수
  "typeId": 2,                    // 신규 전송 (null 이면 서버가 task fallback)
  "summary": "제목",               // 필수, ≤200 (프론트 폼은 ≤500 → 백엔드가 200 이므로 폼도 200 으로 맞춘다)
  "description": "본문",           // 신규 전송. null/공백이면 서버가 템플릿 대체 (FR-TM-01)
  "componentIds": [],             // 기존
  "securityLevelId": null,        // 기존
  "customFields": {},             // 기존
  "assigneeId": "<uuid>|null|키생략", // 신규 전송, 3-state
  "priority": 3,                  // 신규 전송, 1..5
  "labels": ["backend"]           // 신규 전송, ≤20개 · 개별 ≤50자 · 공백-only 금지
}
→ 201 { "data": IssueResponse }
```

**응답 스키마 변경 0** — `issueResponseSchema` 는 손대지 않는다.

### 서버 오류 계약 (프론트가 표시해야 하는 것)

| 상황 | 응답 | 화면 |
|---|---|---|
| 담당자로 지정한 사용자가 없음 | **422** `ASSIGNEE_NOT_FOUND` | 담당자 칸 옆 에러 |
| 라벨 개수/길이/공백-only 위반 | **400** | 라벨 칸 옆 에러 (클라이언트 검증이 먼저 막으므로 방어선) |
| 우선순위 범위 밖 | **400** | 우선순위 칸 옆 에러 |
| 권한 없음 | **403** | 폼 상단 배너 |

## 데이터 모델 변경

**없음.** 마이그레이션 0 · 백엔드 코드 변경 0.

## 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| E1 | 프로젝트를 바꾸면 컴포넌트·커스텀 필드가 **이전 프로젝트 것으로 남는다** | 프로젝트 변경 시 `componentIds`·`customFields` 를 **초기화**한다 (기존 폼도 같은 위험 보유 — 이번에 함께 닫는다) |
| E2 | 담당자를 골랐다가 **프로젝트를 바꾼다** | 담당자는 전역 사용자라 프로젝트 무관 → 유지 |
| E3 | 모달을 닫았다 다시 연다 | 폼 상태 **초기화**. 반쯤 채운 값이 남아 다음 생성에 새는 것을 막는다 |
| E4 | 라벨을 20개까지 채운 뒤 추가 입력 | 입력창 `disabled` (기존 `IssueLabelsEdit` 동작 계승) |
| E5 | 제목이 200자를 넘음 | 폼 검증 400 이전에 차단. **기존 폼의 500자 상한은 백엔드 200 과 어긋나 있다** — 200 으로 맞춘다 |
| E6 | 커스텀 필드 required 미입력 | 기존 `customFieldRequiredError` 경로 유지 |
| E7 | 제출 중 재클릭 | 만들기 버튼 `disabled` (mutation pending) |
| E8 | `/issues/new` 직접 진입 후 모달 닫기 | `/issues` 로 navigate (뒤로 갈 히스토리가 없을 수 있음) |
| E9 | 활성 프로젝트가 없거나 아카이브됨 | 프로젝트 셀렉터를 **미선택** 상태로 두고 필수 검증에 맡긴다 |

## 제약 조건

1. **`shortcuts.ts` 를 건드리지 않는다.** `c` 단축키의 목적지는 `/issues/new` 그대로다
   (`shortcuts.test.ts:121` `toHaveLength(5)` 무수정 green 이 판정식 — §4.8 의 4중 계약).
2. **`IssueLabelsEdit` 의 외부 동작을 바꾸지 않는다.** 추출은 내부 구조만 바꾸고,
   기존 `__tests__/IssueLabelsEdit.test.tsx` 가 **무수정 green** 이어야 한다.
3. **응답 스키마(`issueResponseSchema`)를 건드리지 않는다.** 건드리면 산재한 인라인 mock 이
   동시에 깨진다 (learnings 2026-05-30).
4. **MSW 핸들러는 실제 백엔드 계약을 흉내내야 한다.** `assigneeId` 키 유무를 구분하고,
   미존재 사용자에 422 를 돌려준다. 안 그러면 가짜 그린이 된다 (learnings 2026-06-25).
5. **UI 를 바꾸는 PR 은 같은 화면의 기존 E2E 를 함께 돌린다** (learnings 2026-05-31).
   대상 — `keyboard-shortcuts.spec.ts` · `keymap.spec.ts` · `custom-fields.spec.ts` ·
   `issue-ui-regression.spec.ts` (전부 `/issues/new` 의존).
6. **BC 격리.** issue-tracking 프론트만. 백엔드 파일 0 변경.

## 측정 가능한 완료 기준

| # | 기준 | 측정 |
|---|---|---|
| C1 | 유닛 테스트 전량 green | `pnpm test` 실패 0 |
| C2 | 타입 검사 통과 | `pnpm typecheck` EXIT 0 (vitest 는 타입을 안 본다 — learnings 2026-05-30) |
| C3 | **기존 `IssueLabelsEdit.test.tsx` 무수정 green** | git diff 로 해당 파일 변경 0 확인 |
| C4 | **`shortcuts.test.ts:121` 무수정 green** | 동일 |
| C5 | `/issues/new` 의존 E2E 4파일 green | 변경이 필요하면 사유를 PR 본문에 명시 |
| C6 | 신규 E2E — 모달 열기 → 7필드 입력 → 1회 제출 → 상세에 5필드 반영 | `POST /issues` **호출 1회** · 후속 `PATCH` **0회** 를 네트워크로 단언 |
| C7 | `assigneeId` 3-state 3케이스 | 요청 본문 실측 — 키 부재 / `null` / 값 |
| C8 | 린트 | `pnpm lint` EXIT 0 |
| C9 | 정본 동기화 | `bash scripts/verify-master-plan.sh` EXIT 0 |

## Brainstorming Check

✅ 통과 (1회 iteration). 스펙을 코드 실측으로 되짚어 **BLOCKER 1건 + 관찰 4건**을 찾고 스펙에 반영했다.

### 🔴 B-1 (BLOCKER) — MSW 생성 핸들러가 신규 5필드를 전부 무시한다 → 가짜 그린

`mocks/issue-handlers.ts:418` `createIssueHandler` 의 요청 본문 타입에는
`projectKey`·`summary`·`componentIds`·`securityLevelId`·`customFields` **5개뿐**이고,
응답은 `{ ...createdIssueFixture, ...일부 덮어쓰기 }` 다.

**결과 3가지.**
1. `typeId`·`description`·`priority`·`labels` 는 **요청과 무관하게 fixture 값**이 돌아온다.
   *"5필드가 반영됐다"* 를 단언하는 테스트는 **프론트가 아무것도 안 보내도 통과**한다.
2. `assigneeId` 는 더 나쁘다 — 핸들러가 **자기 자동배정 시뮬레이션 결과로 덮는다**(`:439-455`).
   프론트가 보낸 담당자는 아예 읽히지 않는다.
3. 그래서 완료 기준 C6·C7 이 **공허**해진다.

learnings 2026-06-25(`<input type="date">` bare date — MSW lexical 비교가 형식 불일치를 가짜로
통과시킨 사고)와 **동형**이다. 처방을 **FR-14** 로 승격했다.

### B-2 (선재 결함) — 제목 길이 상한이 프론트 500 · 백엔드 200 으로 어긋나 있다

`routes/issues.new.tsx:35` `SUMMARY_MAX_LENGTH = 500` vs `CreateIssueRequest.summary`
`@field:Size(max = 200)`. **201~500자 제목은 프론트 검증을 통과한 뒤 백엔드 400** 을 맞는다.
이번 PR 이전부터 존재하는 결함이고(**PRE_EXISTING**), 폼을 만지는 김에 200 으로 맞춘다 (E5).

### B-3 — `IssueTypeSelect` 는 빈 목록을 방어하지 않는다

`value: number` 필수 + placeholder option 없음. `availableTypes` 로딩 전에 렌더하면
빈 `<select>` 가 된다. 유형 목록 로딩 완료 전에는 셀렉터를 비활성으로 두거나 스켈레톤을 낸다.

### B-4 — 프로젝트를 바꿔도 이전 프로젝트의 컴포넌트·커스텀 필드가 남는다

기존 폼도 가진 성질이다. 프로젝트가 바뀌면 `componentIds`·`customFields` 를 초기화한다 (E1).

### B-5 — `custom-fields.spec.ts` 는 `history.pushState` 로 라우트를 바꾼다

`:328-330` 이 `window.history.pushState({}, '', '/issues/new')` 로 **JS 컨텍스트(MSW store)를
유지한 채** 이동한다. 라우트가 모달을 렌더하도록 바뀌어도 이 경로가 동작하는지는
**추론이 아니라 실행으로** 확인한다 (C5).
