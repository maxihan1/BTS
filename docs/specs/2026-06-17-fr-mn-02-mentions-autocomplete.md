# FR-MN-02 — 멘션 자동완성 (스펙)

> slug: fr-mn-02-mentions-autocomplete · type: ui · agent: frontend-engineer
> BC: issue-tracking (데이터 경로 identity-access) · 선행: FR-MN-01 완료
> 작성: 2026-06-17

## 개요 / 범위

이슈 본문(description) 편집 textarea에서 `@`를 입력하면 사용자 typeahead popover를 띄워, 후보 선택 시 `@username` 토큰을 caret 위치에 삽입한다. **프론트 전용**(백엔드 신규 0, 기존 `GET /api/v1/users?query=` 재사용). FR-MN-01 백엔드가 `updateIssue`에서 본문의 `@username`을 추출·발행하므로, 자동완성은 그 입력을 돕는 UI 레이어다.

**적용 위치 (단일)**: `apps/web/src/components/issue/IssueDescription.tsx`의 편집모드 Write 탭 textarea. (이슈 생성 폼엔 description textarea 부재, 댓글 기능 부재 → 후속.)

**deviation (Maxi 확정 2026-06-17)**: 명세 `/autocomplete?q=`·prefix·TipTap → **기존 `?query=` 재사용·substring·textarea**.

## 사용자 시나리오 (Given-When-Then)

- **S1 트리거**. Given 이슈 본문 편집 중, When textarea에 `@jo`를 입력하면, Then 250ms 후 username/displayName에 "jo"가 부분일치하는 사용자 후보 popover가 뜬다.
- **S2 선택(클릭)**. Given 후보 popover가 열림, When "John Doe (@jdoe)" 후보를 클릭하면, Then `@jo`가 `@jdoe `(뒤 공백)로 치환되고 caret이 공백 뒤로 이동, popover 닫힘.
- **S3 선택(키보드)**. Given popover 열림, When ↓/↑로 후보를 이동하고 Enter(또는 Tab)를 누르면, Then 활성 후보가 삽입되고 줄바꿈/탭은 입력되지 않는다(preventDefault).
- **S4 닫기**. Given popover 열림, When Escape를 누르면, Then popover만 닫히고 textarea 포커스·입력 내용은 유지된다(줄바꿈 없음).
- **S5 비트리거(이메일)**. Given 본문에 `mail a@b`를 입력, When `@`가 단어 중간(앞 문자가 공백/시작이 아님)이면, Then popover가 뜨지 않는다.
- **S6 결과 없음**. Given `@zzzz` 입력 후 일치 사용자 0건, Then popover는 닫힌 상태(또는 후보 없음 → 미표시)로 유지되고 일반 타이핑을 막지 않는다.
- **S7 다중 멘션**. Given 본문에 이미 `@alice `가 있음, When 뒤에 `@bo`를 입력하면, Then 두 번째 `@bo`에 대해 독립적으로 popover가 뜬다.
- **S8 저장 연동**. Given `@jdoe `를 삽입하고 저장하면, Then 기존 onSave(markdown) 경로로 본문이 저장되고(별도 변경 없음), 백엔드 FR-MN-01이 멘션을 추출·발행한다.

## 기능 요구사항 (FR)

- **FR1 트리거 감지**. caret 직전 텍스트에서, `@`가 (a) 문자열 시작이거나 (b) 바로 앞이 공백/개행일 때만 멘션 트리거로 인식한다. `@`부터 caret까지가 "활성 멘션 쿼리"다.
- **FR2 쿼리 추출**. 활성 멘션 쿼리는 `@` 다음부터 caret까지의 연속 문자열이며, **공백/개행을 만나면 종료**(그 지점 이후엔 트리거 아님). 쿼리에 허용되는 문자: 영숫자 및 username 허용 문자(서버 매칭에 위임, 프론트는 공백 경계만 본다).
- **FR3 후보 조회**. 활성 쿼리 길이 **≥ 1**일 때 `fetchUsers(query)`(기존 `?query=` substring)를 **250ms debounce** 후 호출. 쿼리 길이 0(`@`만 입력)에서는 50명 덤프 방지를 위해 조회/표시하지 않는다.
- **FR4 후보 표시**. 각 후보는 `displayName`(없으면 username) + `@username`을 함께 보여준다. 최대 표시 = 백엔드 MAX_RESULTS(50) 그대로. listbox/option ARIA(`role=listbox/option`, `aria-activedescendant`).
- **FR5 삽입**. 후보 확정 시 활성 멘션 구간(`@`~caret)을 `@<username> `(뒤 공백 1개)로 치환. caret은 삽입 토큰+공백 뒤로 이동(rAF 복원, `isConnected` 가드).
- **FR6 키보드**. popover 열림 상태에서 ArrowDown/Up=후보 순환, Enter/Tab=활성 후보 확정(preventDefault), Escape=닫기(preventDefault, 포커스 유지). 닫힘 상태에선 textarea 기본 동작.
- **FR7 닫기 조건**. (a) Escape, (b) 후보 확정, (c) blur(클릭 우선 위해 150ms 지연), (d) 활성 쿼리 종료(공백 입력/caret 이탈), (e) 후보 0건.
- **FR8 IME 조합**. compositionstart~end 사이에는 트리거 감지·삽입을 보류한다(한글/일본어 조합 중 selectionStart가 조합영역을 가리켜 토큰을 끊는 것 방지, TemplateContentField 선례).
- **FR9 활성 멘션 정의**. 활성 멘션 = caret 직전에서 역방향으로 스캔해 만나는 **첫 `@`**이며, 그 `@`와 caret 사이에 공백/개행이 없고 FR1 경계(앞 문자=시작/공백)를 만족해야 한다. 조건 불충족 시 활성 멘션 없음(popover 닫힘).
- **FR10 쿼리 문자셋**. 쿼리 종료 경계는 공백/개행이다. 그 외 문자(영숫자·`._-` 등)는 쿼리에 포함해 서버 substring 매칭에 위임한다(프론트 별도 필터 없음).
- **FR11 popover 위치**. caret 픽셀 추적(mirror-div) 없이 **편집 textarea 바로 아래 docked**로 렌더(LabelAutocompleteInput 선례, `absolute mt-1 w-full`). v1 단순화.

## 비기능 요구사항 (NFR)

- **NFR1 의존성 0 지향**. 신규 npm 패키지 도입 금지(DEVELOPMENT.md §17). 기존 `cmdk`(설치됨) 키보드 컨텍스트 또는 순수 DOM/이벤트로 구현. 신규 의존성 필요 시 Maxi 확인.
- **NFR2 재사용**. `apps/web/src/api/users.ts`의 `fetchUsers`/`userSummarySchema`, `@/hooks/use-debounce`, LabelAutocompleteInput의 listbox·blur·onMouseDown preventDefault 패턴, TemplateContentField의 `spliceToken`/`restoreCaretAfterFrame`/IME 패턴을 재사용한다(중복 신설 금지).
- **NFR3 PII**. 엔드포인트는 이미 인증 게이트(401). email/displayName을 콘솔/로그에 출력하지 않는다.
- **NFR4 a11y (WCAG AA)**. combobox 패턴 ARIA, 키보드 단독 사용 가능, 활성 후보 시각 강조.
- **NFR5 성능**. debounce 250ms + 백엔드 cap 50. 매 키 입력마다 fetch 금지.

## API 인터페이스 (REST)

**신규 0.** 기존 재사용.

```
GET /api/v1/users?query=<q>      (UsersController, 인증 필수)
→ 200 [{ id, username, displayName|null, email|null }]   // substring ILIKE, ≤50건
```

프론트: `fetchUsers(query)` (`apps/web/src/api/users.ts`).

## 데이터 모델 변경

**없음.** users 테이블 읽기만(기존). 마이그레이션 0.

## 엣지 케이스

- **E1 미포커스 textarea selectionStart**. jsdom=null vs 실브라우저=0 (memory `jsdom-browser-textarea-selectionstart`). `selectionStart ?? 0` fallback. **실 caret 동작은 E2E로 검증**(단위만으론 가짜그린).
- **E2 `@@`/연속**. `@@` 또는 `@` 직후 다시 `@`는 트리거 경계 규칙(FR1)로 자연 처리(앞 문자가 `@`면 비트리거). FR-MN-01 파서와 완전 일치 불요(프론트는 트리거 판정만).
- **E3 줄바꿈 사이**. `@` 다음 줄바꿈이 오면 쿼리 종료(FR2). 멀티라인 본문에서 각 줄 독립.
- **E4 빠른 타이핑/경합**. debounce 후 도착한 응답이 이미 닫힌 popover에 반영되지 않도록 open 상태/현재 쿼리 가드.
- **E5 후보에 자기 자신**. UI는 자기 자신도 후보로 표시(백엔드가 알림에서 self 제외, username 자체는 유효). 별도 필터 없음.
- **E6 caret을 멘션 구간 밖으로 이동**. 화살표/클릭으로 caret이 활성 쿼리 밖이면 popover 닫힘(FR7d).
- **E7 권한/열람제한**. description이 restricted/noneditable이면 애초에 편집 textarea가 렌더 안 됨(IssueDescription 기존 게이팅) → 멘션 UI도 자연 비활성.
- **E8 네트워크 오류/401**. fetch 실패 시 popover 미표시(후보 0 취급), 타이핑은 방해하지 않음. 토스트 강제 안 함(typeahead는 best-effort).
- **E9 코드블록/펜스 내 `@`**. FR-MN-01 파서는 코드스팬/펜스 내 멘션을 무시하지만, **autocomplete는 v1에서 코드블록을 억제하지 않는다**(마크다운 파싱 비용 회피). 코드블록 안에서 후보를 선택해도 백엔드가 추출하지 않으므로 **오발송 0** — UI-백엔드 불일치는 무해·저빈도로 수용(known limitation).
- **E10 MSW 테스트 핸들러**. 단위/E2E가 `GET /api/v1/users?query=`의 substring 필터를 시드 가능해야 한다(기존 `apps/web/src/mocks/user-handlers.ts` 확장/활용). 멘션 후보 시나리오용 사용자 fixture 필요.

## Brainstorming Check

✅ 통과 (1회, 기술 스펙 직접 작성 + 4개 실제 선례 기반 자기 sanity-check로 대체 — office-hours/interactive brainstorming은 잘 정의된 deviation-확정 FR에 부적합, memory `bts-spec-office-hours-mismatch` 적용).

발견·해소한 gap.
- popover 위치 미명세 → FR11(textarea 아래 docked) 추가.
- 활성 멘션 정의 모호 → FR9(역방향 첫 `@`+공백 없음+경계) 추가.
- 쿼리 문자셋 경계 → FR10(공백 경계만) 추가.
- 코드블록 내 트리거 처리 → E9(v1 비억제, 오발송 0) 명시.
- 테스트 인프라 → E10(MSW user-handlers) 명시.

미해소(의도적 후속/수용).
- 이슈 생성 폼·댓글 멘션 → description textarea/댓글 기능 부재로 후속 FR.
- caret 픽셀 정밀 위치 → docked로 단순화(후속 개선 여지).

## 제약 조건

- 한 PR = 한 BC(issue-tracking 프론트). 백엔드/identity-access 변경 0.
- 기존 onSave(markdown) 계약 불변 — 멘션 UI는 textarea 값만 조작.
- 기존 IssueDescription E2E/단위 회귀 0.

## 측정 가능한 완료 기준

1. 본문 편집 중 `@jo` 입력 → 일치 사용자 popover 표시(S1).
2. 클릭·키보드(Enter/Tab) 선택 → `@username ` 삽입, caret 정위치(S2/S3).
3. Escape로 닫힘·포커스 유지, 줄바꿈 미입력(S4).
4. 이메일 등 비트리거에서 popover 미표시(S5).
5. 단위 테스트: 트리거 감지·쿼리 추출·토큰 splice·키보드 네비·IME 보류.
6. E2E(Playwright): 이슈 상세 → 본문 편집 → `@` 타이핑 → 후보 선택 → 저장 → 본문에 `@username` 포함. 기존 issue E2E 회귀 0.
7. `pnpm verify`(lint+typecheck+test+build) 그린.
