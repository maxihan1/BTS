# /admin/slack 라우트 가드 2→4 봉합 + admin 라우트 가드 행렬 음성 테스트 — 스펙

> slug: admin-slack-beforeload-2-4-requirepasswordchanged
> type: auth · BC identity-access (변경 파일은 `apps/web`)
> PR: #309 · plan: `docs/plans/2026-07-25-admin-slack-beforeload-2-4-requirepasswordchanged.md`
> 작성: 2026-07-25

## 배경 — 실측된 결함 2겹

### (1) 배선 결함 — `/admin/slack`만 2-가드

`apps/web/src/router.ts` 58 라우트 중 admin 11개 전수 열거 결과, 10개는 4-가드
(`requireAuth` → `requireSystemAdmin` → `requirePasswordChanged` → `requireMfaEnrolled`)인데
`/admin/slack`(router.ts:623 블록)만 `composeGuards(requireAuth, requireSystemAdmin)` 2-가드다.

`requireSystemAdminFull`(router.ts:8)이 그 4-가드 합성의 공유 상수이며, 4개 라우트가 이 상수를,
6개 라우트가 동일 인자로 `composeGuards`를 인라인 호출한다. 즉 **가드 집합은 10개가 모두 같고
표기만 두 갈래**다.

**결과.** `mustChangePassword: true`(관리자가 초기 비밀번호를 아직 안 바꾼 상태) 또는
`mfaEnrollmentRequired: true`(2단계 인증 미등록) 상태의 시스템 관리자가 `/admin/slack` URL로
직접 도달하면 **강제 리다이렉트 없이 슬랙 연동 관리 화면에 진입**한다. 다른 관리자 화면 10곳은
모두 막힌다.

### (2) 검증 결함 — 기존 음성 테스트가 행렬의 일부만 덮는다

`apps/web/src/router.admin-guards.test.tsx`가 이미 존재하고 오늘 green이다. 그런데 덮는 범위가
**3라우트 × 가드 1종**이다.

- 라우트 — `GUARDED_WORKFLOW_SCHEME_ROUTES` **하드코딩 3개**(workflow-schemes 계열)뿐.
  `/admin/slack`·`/admin`·`/admin/audit-logs`·`/admin/global-permissions`·
  `/admin/notification-policies`·`/admin/users/new`·`/admin/webhooks`·
  `/admin/webhooks/$id/deliveries` **8개가 목록 밖**.
- 가드 차원 — `isSystemAdmin: false` → `/dashboard` 1종 + admin 통과 1종.
  `requireAuth`(미인증)·`requirePasswordChanged`·`requireMfaEnrolled` **3차원 미검증**.

그래서 `/admin/slack`의 가드 2개가 빠져 있어도 **테스트는 초록**이다. 이것이 결함 (1)이 #299부터
지금까지 살아남은 기전이다. 메모리 `guard-handler-matrix-blindfold`("개수 말고 행렬 전수 열거")와
`archunit-vacuous-rule-silent-pass`가 경고한 형태 그대로다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (핵심 결함).** Given 시스템 관리자이고 `mustChangePassword === true`이며 다른 조건은 통과,
  When `/admin/slack`에 URL로 직접 도달, Then `/settings/password`로 redirect된다.
  *(현재는 화면에 진입한다 — 이것이 고칠 결함)*
- **S2 (핵심 결함).** Given 시스템 관리자이고 `mfaEnrollmentRequired === true`이며 다른 조건은 통과,
  When `/admin/slack`에 직접 도달, Then `/settings/mfa`로 redirect된다. *(현재는 진입한다)*
- **S3 (회귀 금지).** Given 인증된 비-관리자(`isSystemAdmin === false`), When `/admin/slack`,
  Then `/dashboard`로 redirect된다. *(현재도 동작 — 깨뜨리지 않는다)*
- **S4 (회귀 금지).** Given 미인증(`accessToken === null`), When `/admin/slack`,
  Then `/login`으로 redirect된다. *(현재도 동작)*
- **S5 (양성).** Given 4조건 전부 통과한 시스템 관리자, When `/admin/slack`, Then 통과(throw 없음).
- **S6 (미래 회귀 가드).** Given 누군가 새 `/admin/*` 라우트를 4-가드 없이 추가, When 유닛 테스트 실행,
  Then **자동으로 실패**한다. *(오늘의 결함이 6주간 잠복한 이유를 제거)*

## 기능 요구사항 (FR)

**신규 FR 없음.** 본 작업은 기존 관례(4-가드)에 미준수 라우트 1개를 맞추는 결함 수정이며
`docs/plan/fr-index.md` 카운트를 건드리지 않는다 → **FR 총수 129 불변**, `CLAUDE.md §명세/범위 변경 시
전수 동기화` 8종 대상 아님.

구현 요구사항.

- **R1.** `/admin/slack` 라우트의 `beforeLoad`를 **`requireSystemAdminFull`로 교체**한다.
  인라인 `composeGuards(...)` 4개를 새로 쓰지 않고 공유 상수를 재사용해 **가드 집합과 순서의 동일성을
  구조적으로 보장**한다(인자 오타로 다시 갈라질 여지 제거).
- **R2.** 검증 대상 admin 라우트 목록을 **`router.routesById`에서 파생 열거**한다. 하드코딩 배열 금지 —
  판정식은 `routesById` 키가 admin 라우트 경로를 가리키는지이며, 새 라우트는 **등록만으로 자동 편입**된다.
- **R3.** 파생된 전 admin 라우트 × **가드 4차원** 음성 케이스를 전수 검증한다. 각 케이스의 **판별자는
  redirect 목적지 문자열**이며 서로 겹치지 않는다.

  | 차원 | 세팅 (나머지는 전부 통과 조건) | 기대 목적지 |
  |---|---|---|
  | `requireAuth` | `accessToken: null` | `/login` |
  | `requireSystemAdmin` | 인증 + `isSystemAdmin: false` | `/dashboard` |
  | `requirePasswordChanged` | 인증 + admin + `mustChangePassword: true` | `/settings/password` |
  | `requireMfaEnrolled` | 인증 + admin + `mfaEnrollmentRequired: true` | `/settings/mfa` |

  "throw 되었다"만 확인하는 vacuous 어서션 금지 — 메모리
  `negative-guard-needs-body-discriminator`("여전히 401은 vacuous").
- **R4.** 양성 케이스 — 4조건 통과 관리자에서 전 라우트 `not.toThrow()`.
- **R5. vacuous 방지 가드.** 파생 열거 결과가 빈 배열이면 `it.each([])`가 **무음 통과**한다.
  발견된 라우트 수의 **하한**(현재 실측 11)을 별도 어서션으로 고정한다. 상한은 두지 않는다 —
  라우트 추가가 테스트를 깨서는 안 되고, **발견 실패**만 잡아야 한다.
- **R6.** 기존 `router.admin-guards.test.tsx`의 하드코딩 3라우트 목록을 R2 파생 열거로 대체한다.
  **커버리지 순손실 0** — 기존 2개 케이스(비-admin → `/dashboard`, admin → 통과)는 R3·R4에 흡수되며
  대상 라우트는 3 → 11로 늘어난다.

## 비기능 요구사항 (NFR)

- **N1. 시각 변화 0 · 라우트 트리 구조 변화 0.** 경로·컴포넌트·`validateSearch`·부모 관계 불변.
  변경은 `/admin/slack` 블록의 `beforeLoad` 한 줄에 한정.
- **N2. 가드를 hoist하지 않는다.** `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md:70`이
  "49개 `beforeLoad` 가드를 shell로 hoist"를 실패 시나리오로 기각하고 **"가드는 라우트에 그대로 둔다"**로
  명문화했다. 공통 `/admin` 레이아웃 라우트 신설로 중복을 없애는 방향은 **범위 밖이자 금지**다.
  중복은 제거하지 않고 **행렬 테스트로 감시**한다.
- **N3. 테스트는 런타임 등록 상태를 읽는다.** 소스 문자열 스캔이 아니라 `router.routesById`의 실제
  `options.beforeLoad`를 호출한다 — "가드 함수는 옳은데 라우트에 배선을 빼먹었다"를 잡는 것이 목적이고,
  기존 `router.admin-guards.test.tsx` 주석(9~12행)이 이미 같은 이유를 밝히고 있다.
  *(대비 — PR22의 `button-primitive-usage.test.ts`는 소스 스캔이 맞았다. 그건 "코드 모양" 규칙이고
  이건 "런타임 배선"이라 판정 대상이 다르다.)*
- **N4. 뮤테이션으로 실효 입증.** 기준선 green 확인 → 가드를 되돌려 red 확인 → 복원.
  **커밋 후에만 수행**한다 — 메모리 `mutation-test-requires-committed-baseline`(미커밋 상태
  `git checkout --` 원복으로 PR22에서 2파일 작업 소실).

## API 인터페이스 (REST)

변경 없음. 백엔드 엔드포인트·DTO·권한 로직 전부 무접촉.

**주의 — 이 PR은 프론트 UX 계층만 고친다.** `docs/decisions/2026-06-12-mfa-enforcement-policy.md` D4에
따르면 권위 출처는 백엔드(`MfaEnrollmentGateFilter`의 403)이고 프론트 가드는 **additive UX 편의
계층**이다(`routeGuard.ts:128~132` 주석). 따라서 본 결함의 실제 위험은 "관리 API를 호출할 수 있다"가
아니라 **"막혀야 할 화면이 열리고, 그 화면의 요청이 백엔드에서 403으로 깨진다"**는 UX 단락이다.
백엔드 게이트가 슬랙 관리 경로를 실제로 덮는지는 **별도 확인 항목**으로 남긴다(범위 밖).

## 데이터 모델 변경

없음. 마이그레이션 0 · 스키마 0 · 시드 0.

## 엣지 케이스

- **E1. 동적 세그먼트.** `/admin/workflow-schemes/$schemeKey`·`/admin/webhooks/$id/deliveries`는
  `$` 세그먼트를 실값으로 치환해야 한다. 4개 가드 중 pathname을 읽는 것은
  `requirePasswordChanged`(`=== '/settings/password'`)와 `requireMfaEnrolled`(`=== '/settings/mfa'`)의
  **동등 비교뿐**이므로 임의 값이어도 판정이 바뀌지 않는다(가드가 파라미터를 파싱하지 않음 — 실측 확인).
- **E2. 자기 경로 예외 미해당.** 위 두 가드는 현재 경로가 목적지와 같으면 무한 redirect를 피해 통과하는데,
  `/admin/*`은 `/settings/*`와 겹치지 않으므로 예외가 발동하지 않는다.
- **E3. `requireSystemAdmin`의 시그니처.** 이 가드만 `(): void`로 **ctx 인자를 받지 않는다**
  (`routeGuard.ts:169`). 파라미터가 적은 함수는 `Guard = (ctx) => void`에 할당 가능하므로
  `composeGuards`에 그대로 들어간다. 테스트가 가드를 개별 호출하지 않고 라우트의 합성 `beforeLoad`만
  호출하므로 영향 없다.
- **E4. 가드 순서 — 관리자 판정이 비밀번호·MFA보다 앞.** 기존 4-가드 순서는
  `requireAuth → requireSystemAdmin → requirePasswordChanged → requireMfaEnrolled`다. 따라서
  **비-관리자 + `mustChangePassword: true`는 `/settings/password`가 아니라 `/dashboard`로** 간다.
  이는 10개 라우트에 이미 확립된 관례이며 **본 PR은 순서를 바꾸지 않는다**. 행렬 케이스는 각 차원마다
  나머지 조건을 전부 통과 상태로 두어 판별자가 유일하게 특정되도록 구성한다.
- **E5. 빈 열거의 무음 통과.** R5에서 하한 어서션으로 차단.
- **E6. 접두사 오탐.** `routesById` 키에 `_shell` 접두사가 붙는다(pathless 레이아웃 재부모화, 실측상
  admin 11개 전부 `shellRoute` 자식). 키 판정은 이 형태를 전제로 하되, 판정에 걸린 항목이 실제
  `beforeLoad`를 가진 라우트인지 함께 확인한다.
- **E7. `/admin` 인덱스 라우트 포함.** 목록의 11번째는 하위 경로가 없는 `/admin` 자체다
  (현재 `requireSystemAdminFull`). 파생 열거가 이걸 빠뜨리면 안 된다.
- **E8. 픽스처 기본값 의존.** `makeWhoami()`의 기본은
  `mustChangePassword: false · isSystemAdmin: false · mfaEnrollmentRequired: false`(실측
  `mocks/auth-fixtures.ts:90~104`). admin 통과 케이스는 `isSystemAdmin: true`를 **명시 override**해야 한다.

## 제약 조건

- **C1.** BC 격리 — 변경은 `apps/web` 단일. 백엔드·마이그레이션 0.
- **C2.** ADR §70 — 가드 hoist 금지 (N2).
- **C3.** FR 129 불변 — 전수 동기화 8종 대상 아님. `bash scripts/verify-master-plan.sh` EXIT 0 유지.
  문서에 `FR-XX-NN` 형태 문구를 쓰지 않는다 — 메모리
  `verify-master-plan-header-scanner-false-match`(산문의 FR 코드를 헤더 선언으로 오인해 EXIT 1).
- **C4.** 범위 밖 (명시) — ATLAS-4 fixture version 불일치 · 백엔드 `WorkflowSchemeController` 읽기 경로
  권한 · `PublicDashboardController` 404 `instance` 토큰 노출 · 백엔드 MFA 게이트의 슬랙 경로 커버 여부.

## 측정 가능한 완료 기준

1. `/admin/slack`의 `beforeLoad`가 `requireSystemAdminFull`이다 (`git diff`로 1줄 변경 확인).
2. 파생 열거된 admin 라우트가 **11개 이상**이고, 그 전부 × 4차원 음성 케이스가 green이다.
3. 양성 케이스(4조건 통과 관리자)가 전 라우트에서 throw 0.
4. **뮤테이션 A** — `/admin/slack`을 2-가드로 되돌리면 그 라우트의 password·MFA 케이스 **2건이 red**.
5. **뮤테이션 B** — 파생 열거를 빈 배열로 만들면 R5 하한 어서션이 **red**(vacuous 차단 실증).
6. `pnpm typecheck` 0 · `pnpm lint` 0 error · 기존 유닛 전량 green(기준선 대조, 순손실 0).
7. `bash scripts/verify-master-plan.sh` EXIT 0 (129/129 불변).
