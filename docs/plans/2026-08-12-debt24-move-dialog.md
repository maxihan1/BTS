# 이슈 이동 다이얼로그 오류 처리 4건 (기술부채 2파 · PR #367)

> slug: debt24-move-dialog
> type: bugfix
> agent: frontend-engineer
> 생성: 2026-08-12
> PR: #367 · 브랜치 `fix/debt24-move-dialog` · worktree `.worktrees/debt24-move-dialog`
> 관련 FR: **FR-MV-01** (이슈 이동 마법사 — 이 부채 4건이 전부 그 D6 산출물 위에 있다)
> 선행: #365(0파 결정 등재) · #366(1파) · #375(3파). 순서 제약 없음(master §순서 제약).

## 닫는 부채 4건

`docs/plans/2026-08-12-debt24-master.md` §전수 매핑의 항목 번호로 가리킨다. **줄번호는 쓰지 않는다**(휘발성).

| 매핑 # | 항목 | 성격 |
|---|---|---|
| `11` | 이동 대상 프로젝트 키 **대소문자 정규화 부재** | 선재 |
| `12` | 이동 `errorProjectNotFound` 도 **prod 도달 불가** 문구다 | 선재 |
| `17` | 이동 `errorPreview` 가 500·단절에도 「키를 확인해 주세요」라 말한다 | 선재 |
| `18` | 이동 403 문구에서 「다른 대상 프로젝트를 시도」가 함께 사라졌다 | 신규 |

## 도메인 정리

**fast-track(bugfix) 스킵.** 신규 도메인 개념 0. 기존 FR-MV-01 어휘(대상 프로젝트 키 · preview · 매핑) 그대로.

## 스펙

**fast-track(bugfix) 스킵.** 처방은 `TODOS.md` 각 항목의 `★Maxi 확정` 블록과 아래 §착수 시 확정 3건이 정본.

## 착수 시 확정 — Maxi 결정 3건 (2026-08-12)

| # | 항목 | 확정 |
|---|---|---|
| A | `17` 처방 | **상태별 3분기 + 403 과 첫 문장 겹침 제거.** 403 → 현행 `errorPreviewForbidden` 유지 · 5xx·비-ApiError → 신규 `errorPreviewTemporary` · 그 외 4xx → `errorPreview` **재작성(키 언급 제거)** |
| B | `12` 분기 | **삭제하지 않고 유지 + 「비-prod 전용」 주석.** 개발 리졸버에서는 실제로 도달하는 경로다 |
| C | `12` 동기화 범위 | **잘못된 전제 4곳 전부 갱신** — 백엔드 `IssueMoveController.kt` KDoc 1줄 포함 |

**C 의 파생 의무.** PR #367 본문은 「백엔드는 건드리지 않는다」라 적혀 있다. C 를 택한 이상 **같은 PR 에서 본문도 정정**해야 한다. 안 고치면 본문이 거짓 정본으로 남는다(`[[two-lists-never-check-each-other]]` 양식).

## ★실측 — 항목 `18` 의 판정 전제가 거짓이다 (2026-08-12)

`TODOS.md` 항목 `18` 과 PR #367 본문은 **「③ 적용 후 403 원인이 3개 → 2개로 준다」** 를 근거로 「문구를 줄일 글자 예산이 생긴다」고 적었다. **틀렸다.**

| 입력 | ③(형식 게이트) 적용 후 | 서버 판정 |
|---|---|---|
| `infra` (소문자 · 형식 위반) | 클라이언트에서 차단 | 요청 없음 |
| `NOPE` (**형식 OK · 미존재**) | **통과** | 403 |

`PROJECT_KEY_PATTERN = /^[A-Z][A-Z0-9]{1,9}$/` 은 **형식만** 본다. 존재 여부는 모른다. 그러므로 403 도달 원인은 **여전히 3개**다.

```
① 형식은 맞는데 없는 키 (오타 NOPE)   ← 남는다
② 이 이슈 UPDATE 권한 없음
③ 대상 프로젝트 CREATE 권한 없음
```

⇒ **「대상 프로젝트 키를 확인해 주세요」 앞부분을 뺄 근거가 없다.** PR 본문 자기 표(`NOPE → 403 → 403`)가 이미 그렇게 적혀 있어 **서술과 표가 서로 어긋나 있었고, 표가 맞다.**

**이 실측은 Task 5 에서 눈확인으로 재확인한 뒤 결론을 확정한다** — 지금은 「유지가 유력」까지만이고, 계획서가 결론을 선점하지 않는다.

### ★되기록 — Task 5 결과 (2026-08-12 · 계획서의 「유지가 유력」은 틀렸다)

**위 실측은 맞았다.** `NOPE` 는 게이트를 통과해 403 을 받는다(T4-8 「게이트는 존재를 모른다」).
**그러나 거기서 도출한 「유지가 유력」은 틀렸다** — 반대쪽 전제를 안 쟀기 때문이다.

E2E-6 이 실브라우저에서 잰 줄수.

| 안 | 자수 | 1280px(박스 464) | 390px(박스 342) | 320px(박스 272) |
|---|---|---|---|---|
| 이전(행동 안내 없음) | 53 | 2줄 | 2줄 | 2줄 |
| 원안 그대로 복원 | 74 | **2줄** | 3줄 | 3줄 |
| **현행(압축 복원 · Maxi 확정)** | **55** | **2줄** | **2줄** | **2줄** |

「70자를 넘기면 두세 줄이 된다」는 반대 근거가 **데스크톱에서는 거짓**이었고, 55자로 압축하면
**모든 폭에서 대가가 0** 이다. ⇒ 유지가 아니라 **되살림**이 결론이다.

**교훈 두 개.** ① 전제 하나가 거짓이라고 결론이 그 반대편으로 확정되지 않는다 — **반대쪽 전제도
따로 재야 한다.** ② 한 폭만 재고 결론 냈으면 원안 74자를 되살려 모바일을 3줄로 만들었다
(`[[measured-the-wrong-thing-twice]]`).

## 실측 근거 — 서버 검사 순서 (항목 `11`·`12` 의 뿌리)

| 대상 | 실측 |
|---|---|
| `MovePreviewService.kt:185-186` | 권한 assert 2개가 존재 확인(`:188` 이슈 · `:191-192` 대상 프로젝트)보다 **앞** |
| `IssueMoveService.kt:189-190` | 같은 순서 (`:210` 이 존재 확인) |
| `IdentityAccessIssuePermissionResolver.kt:77` | `resolveProjectId(scope) ?: return false` — **미존재 프로젝트 = 거부(403)** |
| `ProjectDirectory` | raw SQL `WHERE key = :key` — **정확 일치**(대소문자 구분) |
| `MovePreviewService` 가 던지는 예외 전수 | `IssueAccessDenied`(403) · `IssueNotFound`(404) · `IssueProjectNotFound`(404, 비-prod 전용) **3종뿐** |
| `MOVE_ERROR_CODES.PROJECT_NOT_FOUND` 프론트 소비처 | `MoveIssueDialog.tsx` **1곳** (grep 전수) |

## ★설계 제약 — 줄수 래칫이 코드 추가를 막는다

`apps/web/src/test/lint-ratchet-baseline.ts:20` 이 `Function 'MoveIssueDialog'` 를 **326줄로 동결**했고, `lint-ratchet.test.ts:337` 「베이스라인 대비 늘어난 함수가 없다」가 `lines > frozen` 에서 실패시킨다. 장부를 **올리는 방향은 부채 항목 `22` 가 이미 지적한 회귀**다.

⇒ **항목 `11`·`17` 의 새 로직은 컴포넌트 함수 안에 넣지 않는다.** `apps/web/src/lib/` 순수 함수 모듈로 빼고 컴포넌트는 호출 1줄만 갖는다. 부수 효과로 판정 로직이 DOM 없이 직접 테스트된다.

## Plan

### Task 1. 프로젝트 키 형식 판정을 공용 모듈로 추출

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/project-key.ts`, `apps/web/src/lib/project-key.test.ts`, `apps/web/src/routes/projects.new.tsx`]
- depends-on: []

**왜 추출인가.** 항목 `11` 의 확정 처방은 「**기존** `PROJECT_KEY_PATTERN` 재사용」이다. 그런데 그 상수는 지금 `routes/projects.new.tsx:53` 의 **파일 지역 상수**다. 이동 다이얼로그가 라우트 파일을 import 하는 것은 잘못된 결합이고, 복사하면 **두 벌이 되어 갈라진다**(`[[two-lists-never-check-each-other]]`). 한 곳에서 읽게 만드는 것이 「생성 화면과 이동 화면이 같은 판정을 낸다」를 **구조로** 보장하는 유일한 방법이다.

**RED**:
- 파일: `apps/web/src/lib/project-key.test.ts` (신규)
- 테스트:
  ```ts
  describe('isValidProjectKey', () => {
    it.each(['INFRA', 'AB', 'A1', 'ABCDEFGHIJ'])('형식에 맞는 키 %s 를 통과시킨다', ...)
    it.each(['infra', 'Infra', 'A', '1ABC', 'ABCDEFGHIJK', 'AB-1', 'AB 1', ''])(
      '형식에 어긋난 키 %s 를 거절한다', ...)
    it('앞뒤 공백은 호출자가 trim 한 값을 넘긴다는 계약을 지킨다 — 함수 자체는 공백을 봐준다', ...)
  })
  ```
- 실패 메시지 (예상): `Cannot find module '@/lib/project-key'`

**GREEN**:
- 파일: `apps/web/src/lib/project-key.ts` (신규 — 첫 줄 한글 헤더 주석 필수, 글로벌 §6)
- 내용. `PROJECT_KEY_PATTERN` 상수 + `isValidProjectKey(value: string): boolean` 1줄 구현. **정규식 자체는 한 글자도 바꾸지 않는다**(백엔드 `PROJECT_KEY_REGEX` · DB CHECK `projects_key_check` 와의 3중 정합이 기존 KDoc 에 적혀 있다 — 그 KDoc 도 함께 옮긴다).

**REFACTOR**:
- `projects.new.tsx` 가 지역 상수를 지우고 `@/lib/project-key` 에서 import. **내 변경이 만든 orphan 만 제거**(글로벌 §3).
- `.refine` 호출부는 `isValidProjectKey` 를 쓰되 「빈 값이면 형식 검증 건너뜀」 기존 의미는 그대로 유지.

**검증**: `cd apps/web && node_modules/.bin/vitest run src/lib/project-key.test.ts src/routes` · `node_modules/.bin/tsc -p tsconfig.app.json --noEmit`

---

### Task 2. 이동 다이얼로그가 요청 전에 키 형식을 차단 (항목 `11`)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/MoveIssueDialog.tsx`, `apps/web/src/components/issues/MoveIssueDialog.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [1]

**RED**:
- 파일: `apps/web/src/components/issues/MoveIssueDialog.test.tsx`
- 테스트 4개 (`describe('T4-8: 요청 전 키 형식 차단')`):
  ```ts
  it('소문자 키를 넣고 「다음」을 누르면 preview 요청이 나가지 않는다', ...)
    // MSW 핸들러에 호출 카운터를 걸어 requestCount === 0 을 단언한다.
    // ★단언 대상은 「문구가 떴다」가 아니라 「요청이 0회다」 — PR 완료 조건이 그것이다.
  it('★비-공허 짝. 형식이 맞는 키는 preview 요청이 1회 나간다', ...)
    // 게이트가 전부를 막아 버리면 위 테스트는 공허하다. 이 짝이 그것을 배제한다.
  it('Enter 키로 제출해도 같은 게이트가 걸린다', ...)
    // [[fr-ux-09-f2-create-issue-dialog-done]] — 「폼 밖 안전 ≠ 폼 안 안전」.
    // onKeyDown 경로가 handleNext 를 우회하면 게이트가 반쪽이 된다.
  it('형식 오류 문구는 403 문구와 다른 문자열이다', ...)
    // errorKeyFormat !== errorPreviewForbidden. 같으면 사용자가 두 상황을 구분 못 한다.
  ```
- 실패 메시지 (예상): 요청 카운터가 `1` (게이트 없음) · `s.errorKeyFormat` 미정의

**GREEN**:
- `i18n/ko.ts` `issueMoveStrings` 에 `errorKeyFormat` 추가.
  문구안. `'프로젝트 키는 대문자로 시작하는 대문자·숫자 2~10자입니다.'`
  (생성 화면 `projectCreateLabels.keyInvalid` 와 **같은 사실**을 말하되 문구 통일 여부는 구현자가 실측 후 판단 — 두 문구가 다르면 그 근거를 주석에 남긴다.)
- `MoveIssueDialog.tsx` `handleNext()` 최상단.
  ```ts
  const key = targetProjectKey.trim()
  if (key === '') return
  if (!isValidProjectKey(key)) { setPreviewError(s.errorKeyFormat); return }
  ```

**REFACTOR**:
- Enter 경로(`onKeyDown`)는 이미 `handleNext()` 를 호출하므로 **게이트를 두 곳에 복사하지 않는다**. 복사하면 그 순간 두 벌이 된다.
- 추가된 줄수를 재고 `MoveIssueDialog` 함수가 **326줄 이하**인지 확인. 넘으면 판정부를 `lib/` 로 뺀다.

**검증**: `node_modules/.bin/vitest run src/components/issues/MoveIssueDialog.test.tsx src/test/lint-ratchet.test.ts`

---

### Task 3. preview 실패 문구를 상태별 3분기로 (항목 `17`)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/move-error-message.ts`, `apps/web/src/lib/move-error-message.test.ts`, `apps/web/src/components/issues/MoveIssueDialog.tsx`, `apps/web/src/components/issues/MoveIssueDialog.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [2]

**RED (순수 함수)**:
- 파일: `apps/web/src/lib/move-error-message.test.ts` (신규)
- 테스트:
  ```ts
  describe('resolvePreviewErrorMessage', () => {
    it('403 이면 권한 전용 문구', ...)                      // errorPreviewForbidden
    it.each([500, 502, 503])('%i 이면 일시적 문제 문구', ...) // errorPreviewTemporary
    it('ApiError 가 아닌 오류(네트워크 단절)면 일시적 문제 문구', ...)
    it.each([400, 401, 404, 409, 422])('%i 이면 그 외 4xx 문구', ...)  // errorPreview
    // ★비-공허 짝 2종 — 이게 없으면 위 단언들이 「전부 같은 문구」여도 통과한다.
    it('세 문구가 서로 다르다', ...)
    it('403 이 아닌 두 문구는 「키」라는 단어를 포함하지 않는다', ...)
  })
  ```
- 실패 메시지 (예상): `Cannot find module '@/lib/move-error-message'`

**RED (컴포넌트 — 기존 테스트 뒤집기)**:
- `MoveIssueDialog.test.tsx:602` 「preview 500 이면 기존 문구가 그대로 나온다 (비-공허 짝)」 를 **「preview 500 이면 일시적 문제 문구가 나온다」로 교체**한다.
  ⚠️ TODOS 항목 `17` 이 이미 지적했듯 그 초록은 **틀린 안내가 유지되는 것을 지키고 있었다**. 삭제가 아니라 **판정을 뒤집는 교체**다.
- 404 케이스 신설 (그 외 4xx 문구).

**GREEN**:
- `i18n/ko.ts`. `errorPreviewTemporary` 신규 + `errorPreview` 재작성(첫 문장에서 키 언급 제거).
  ```
  errorPreview          : '이슈 이동 정보를 불러오지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.'
  errorPreviewTemporary : '이슈 이동 정보를 불러오지 못했습니다. 일시적인 문제일 수 있으니 잠시 후 다시 시도해 주세요.'
  ```
  각 문구 위 KDoc 에 **어느 상태 코드가 오는지**와 근거(`MovePreviewService` 예외 3종 실측)를 적는다.
- `apps/web/src/lib/move-error-message.ts` (신규 — 한글 헤더 주석 필수).
- `MoveIssueDialog.tsx` catch 블록을 `setPreviewError(resolvePreviewErrorMessage(err))` 1줄로 축약. 기존 장문 주석은 **함수 KDoc 으로 이사**(정보를 버리지 않는다).

**REFACTOR**:
- **비-ApiError 를 「일시적」으로 묶는 것의 대가를 KDoc 에 명시한다.** Zod 스키마 파싱 실패(계약 파손)도 여기 들어온다 — 「일시적」은 그 경우 정확하지 않지만, **키를 의심하게 만들지 않는다**는 항목 `17` 의 요구는 충족한다. 계약 파손을 따로 가르는 것은 이 부채의 범위 밖이며, 갈라야 한다면 후속 항목으로 등재한다.

**검증**: `node_modules/.bin/vitest run src/lib/move-error-message.test.ts src/components/issues/MoveIssueDialog.test.tsx src/test/lint-ratchet.test.ts`

---

### Task 4. 「대상 프로젝트 미존재 404」 잘못된 전제 4곳 갱신 (항목 `12`)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/MoveIssueDialog.tsx`, `apps/web/src/i18n/ko.ts`, `apps/web/src/api/issue-move.ts`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueMoveController.kt`, `apps/web/src/components/issues/MoveIssueDialog.test.tsx`]
- depends-on: [3]

**⚠️ RED 를 만들 수 없는 task 다 — 정직하게 적는다.** 분기는 **이미 구현돼 있고 올바르게 동작한다**. 결함은 「이 분기가 운영에서 언제 도는지」를 **주석 4곳이 틀리게 적어 둔 것**이다. 그러므로 이 task 의 테스트는 red-first 가 아니라 **특성화 테스트(characterization test — 현재 동작을 못 박아 두는 테스트)** 이고, 판별력은 **뮤테이션으로 실증**한다.

**테스트 (특성화)**:
- 파일: `MoveIssueDialog.test.tsx`
- `it('move 실행이 404 PROJECT_NOT_FOUND 를 내면 대상 프로젝트 미존재 토스트를 낸다')`
  — 지금 이 분기를 재는 테스트가 **한 건도 없다**(T4-7 은 403 만 잰다). 커버리지 0 인 분기다.
- **뮤테이션 판별**. 분기를 지우면 이 테스트가 red 가 되는지 실증한다.
  ⚠️ `[[mutation-test-requires-committed-baseline]]` — **GREEN 을 먼저 커밋한 뒤** 뮤테이션을 넣고 `git checkout --` 로 원복한다. 미커밋 상태에서 하면 남의 작업까지 날아간다.

**GREEN (주석·KDoc 만 · 실행 코드 0줄)**:

| # | 파일 | 지금 | 고칠 방향 |
|---|---|---|---|
| 1 | `MoveIssueDialog.tsx` PROJECT_NOT_FOUND 분기 | 주석 없음 | 「비-prod 전용 — 운영 리졸버는 미존재 프로젝트를 권한 거부로 판정(`IdentityAccessIssuePermissionResolver:77`)하므로 이 404 는 `DevAllow…` 에서만 도달」 |
| 2 | `i18n/ko.ts` `errorProjectNotFound` | `/** 404 대상 프로젝트 없음 에러 */` — 도달 불가 단서 없음 | 같은 사실 명시 |
| 3 | `api/issue-move.ts` `previewMove`·`moveIssue` `@throws` | `404(이슈/프로젝트 없음)` — **반만 참** | **이슈 404 는 운영에서도 난다 / 대상 프로젝트 404 만 비-prod 전용** 으로 갈라 적는다 |
| 4 | `IssueMoveController.kt:62` KDoc | `대상 프로젝트 미존재 → 404` | 「단 운영에서는 `:189-190` 권한 assert 가 먼저라 403 으로 걸린다」 |

**★4번(백엔드) 을 뭉뚱그리지 말 것.** TODOS 항목 `12` 가 명시했듯 **「404 는 못 나온다」로 뭉개면 실재하는 분기(이슈 미존재 404)를 지운다.** 도달 불가한 것은 **대상 프로젝트 미존재 404** 뿐이다.

**REFACTOR**: 없음. 실행 코드 무변경이 이 task 의 계약이다.

**검증**: `node_modules/.bin/vitest run src/components/issues/MoveIssueDialog.test.tsx` · 백엔드는 KDoc 만이므로 `./gradlew :backend:issue-tracking:compileKotlin :backend:issue-tracking:ktlintMainSourceSetCheck` (전체 test 는 Task 6 에서 1회)

---

### Task 5. 403 문구 재판정 + 실제 렌더 폭 눈확인 (항목 `18`)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-move.spec.ts`, `apps/web/src/i18n/ko.ts`, `TODOS.md`]
- depends-on: [2, 3]

**무엇을 재는가.** 「문구를 바꿀까」가 아니라 **「③ 적용 후 이 403 에 도달하는 원인이 몇 개인가」** 를 먼저 재고, 그 개수에 맞춰 판정한다(항목 `18` Maxi 확정).

**측정 1 — 원인 개수 (코드 실측)**:
- ③ 게이트를 통과하는 **형식 OK · 미존재** 키(`NOPE`)가 여전히 403 을 받는지 E2E 로 확증한다.
- 확증되면 **원인은 3개 그대로**이고, 「키를 확인해 주세요」 앞부분을 뺄 근거가 없다 → **현행 53자 유지**가 결론.
- ⚠️ **반증되면 계획서 §실측 문단이 틀린 것이다.** 그때는 결론을 뒤집고 그 사실을 계획서에 되기록한다.

**측정 2 — 렌더 폭 눈확인**:
- `issue-move.spec.ts` 에 시나리오 추가. Step 1 에서 403 을 유발하고 인라인 에러 영역(`role="alert"`)의 `boundingBox()` 로 **실제 줄수**를 측정 + 스크린샷 1장.
- 셀렉터는 **하드코딩 금지** — `issueMoveStrings` 를 import 해 쓴다(learnings 2026-05-26 「E2E 셀렉터 i18n 정본 import」, 이 spec 이 이미 그 패턴을 쓴다).

**결론 기록 (어느 쪽이든)**:
- `ko.ts` `errorPreviewForbidden` KDoc 에 **재판정 결과와 근거**를 남긴다.
- `TODOS.md` 항목 `18` 을 ✅ 로 닫으며 **「원인 3→2」 전제가 실측으로 뒤집힌 사실**을 명시한다.
- ⚠️ 문구를 되살리기로 결론 나더라도 **「관리자에게 문의」는 되살리지 않는다**(재리뷰가 지적한 막다른 길).

**검증**: `cd apps/web && node_modules/.bin/playwright test e2e/issue-move.spec.ts` + 스크린샷 육안 확인 (라이트/다크 양쪽)

---

### Task 6. 장부 전수 동기화 + 검증

**메타**.
- agent: `frontend-engineer`
- files: [`TODOS.md`, `docs/plans/2026-08-12-debt24-master.md`, `docs/plans/2026-08-12-debt24-move-dialog.md`]
- depends-on: [1, 2, 3, 4, 5]

**할 일**:
1. `TODOS.md` 항목 `11`·`12`·`17`·`18` 을 ✅ 로 전환하고 각 해소 블록에 **실측·결정 근거**를 남긴다.
2. `docs/plans/2026-08-12-debt24-master.md` §전수 매핑 4행 + §PR 별 집계 `#367` 행 + 합계 갱신.
3. **PR #367 본문의 「백엔드는 건드리지 않는다」 정정** (결정 C 의 파생 의무) + 워크플로우 체크리스트 갱신.
4. 이 계획서 §실측 문단에 Task 5 결과를 되기록.

**검증**:
- `grep -c '^## ⬜' TODOS.md` 가 **21 → 17** (Task 5 결론에 따라 신규 후속 등재가 생기면 그만큼 조정하고 **그 사실을 명시**한다 — 조용히 숫자만 맞추지 않는다)

  **★실제 결과 = 21 → 4건 해소 → 17 → 신규 1건 등재 → 18.** 최종 검증에서
  `pnpm test:workflow` 가 **로컬에서만 15건 죽는 것**(CI 는 #365·#366·#375 연속 success)을
  발견했다. `node --test` 에 `--experimental-strip-types` 가 없어 로컬 Node 22.14 가 `.ts` 를
  못 읽는 것이고, **main 트리 대조군에서도 동일하게 62/15** 라 선재로 판정했다.
  매핑 `27` 로 등재(총계 26 → 27). 닫은 4건만 세고 신규를 안 적으면 그게 바로 이 장부가
  막으려는 것이다.

  ⚠️ **그 15건이 죽는 동안 실제 커버리지는 77 → 213 으로 3배 차이였다.** 로컬에서 초록으로
  보이던 62건은 `.mjs` 뿐이고, 장부·훅 배선을 지키는 `.ts` 판별식은 한 번도 안 돌았다.
  이 PR 의 장부 편집은 `--experimental-strip-types` 를 붙여 **213/213 통과**로 확인했다.
- `bash scripts/verify-master-plan.sh` (종료 4 차단 통과)
- `cd apps/web && node_modules/.bin/vitest run` 전량 · `node_modules/.bin/tsc -p tsconfig.app.json --noEmit` · `node_modules/.bin/eslint src`
- `./gradlew :backend:issue-tracking:test` (Task 4 가 백엔드를 건드렸으므로 1회)
- `node scripts/build-doc-index.mjs` 재실행 (새 plan 문서 등재)

## Plan 메타

- task 수: 6
- 예상 시간: task × 4분 ≈ 24분 (직렬 기준). **파일 겹침이 커 병렬 여지가 거의 없다** — `MoveIssueDialog.tsx` 와 `i18n/ko.ts` 를 Task 2·3·4 가 공유한다. 예상 wave 수 5 (T1 → T2 → T3 → T4·T5 → T6)
- 구현 규율: **TDD red→green→refactor**. 단 **Task 4 는 RED 불가(특성화 테스트 + 뮤테이션 실증)** — 위 task 본문에 근거 명시
- 병렬 dispatch: bts-impl 이 메타(depends-on + files)로 wave 계산
- 추가 검증: typecheck · eslint · vitest 전량 · playwright · ktlint · 줄수 래칫(`lint-ratchet.test.ts`) · `verify-master-plan.sh`

## 가짜 그린 주의 — 이 PR 에서 특히 조심할 것

| 위험 | 어디서 | 막는 장치 |
|---|---|---|
| **도달 불가 조합을 지키는 테스트** (`[[unreachable-state-fixture-is-fake-green]]`) | ③ 적용 후 소문자 키는 **서버에 도달하지 않는다** — 그 경로의 서버 응답을 스텁하는 테스트를 남기면 가짜 그린 | Task 2 가 「요청 0회」를 재고, 소문자 + 서버 403 조합 픽스처를 만들지 않는다 |
| **게이트가 전부를 막아 단언이 공허** | Task 2 | 비-공허 짝 — 형식 OK 키는 요청 1회 |
| **세 문구가 실은 같은 문자열** | Task 3 | 비-공허 짝 2종 (서로 다름 + 「키」 미포함) |
| **목이 삼킨 prop** (`[[mock-swallowed-prop-is-invisible-to-unit-tests]]`) | Task 5 의 렌더 폭은 유닛으로 못 잰다 | E2E `boundingBox()` + 스크린샷 |
| **UI 변경이 기존 E2E 셀렉터를 깬다** (learnings 2026-05-31) | 새 오류 문구 추가 | Task 5 에서 `issue-move.spec.ts` 전량 실행 |
