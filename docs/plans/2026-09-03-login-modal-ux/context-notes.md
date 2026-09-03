# context-notes — 로그인 UX 개편 (전역 모달 + 단일 화면 폼)

작업 중 내린 결정과 근거. 계속 덧붙인다. 확정 설계는 `~/.claude/plans/bts-maxihan-com-wondrous-micali.md` D1~D8.

## C0. 문제 확정 — 추측이 아니라 배포 번들 실측

`https://bts.maxihan.com/assets/index-DOByFogC.js` 를 받아 `grep -c "T13 가드 추가 전 placeholder"` → **1**.
즉 미인증 진입 시 `routes/index.tsx` 의 placeholder 가 실제로 렌더된다. 원인은 `router.ts:85-90`
`indexRoute` 에만 `beforeLoad` 가 없다는 것. 60여 라우트 중 유일하다.

`last-modified: Mon, 24 Aug 2026` — 배포본이 8/24 자다. 머지해도 재배포 전까지 안 보인다(Maxi 가 배포 제외 선택).

## C1. Jira Cloud 실물과의 편차 2건은 Maxi 가 선택했다

리서치 결과 [id.atlassian.com/login](https://support.atlassian.com/atlassian-account/docs/log-in-to-your-atlassian-account/) 은
**이메일 선입력 2단계**("Enter your email address and select Continue")이고, 세션 만료 시 모달이 아니라
다음 요청에서 로그인 화면으로 보낸다. 요청 사항이 둘 다 실물과 반대라 AskUserQuestion 으로 확인했고
Maxi 가 "모달 하나로 통일" · "한 화면 + 백그라운드 조회" 를 선택했다.

따라서 새 스펙의 `## Jira 대조` 표에 **X 편차 2건**으로 기록한다. 근거 없는 이탈이 아니다.

Jira 실물 구성(J 항목 근거): 이메일+비밀번호, Remember me 체크박스, Google/Microsoft/Slack/Apple 서드파티,
"Can't log in?" 하단 링크, Passkey.

## C2. ★ e2e 가 타입체크·린트 밖 — 이 작업 최대의 함정

실측:
- `apps/web/tsconfig.app.json:29` → `"include": ["src"]`
- `apps/web/package.json:10` → `"lint": "eslint src"`
- `apps/web/tsconfig.json` 은 app/node 두 프로젝트만 참조. **e2e 는 어디에도 없다**

결과. `i18n/ko.ts` 에서 `continueButton` 을 지워도 **컴파일이 통과한다**. Playwright 런타임에
`getByRole('button', { name: undefined })` 가 되어 **아무 버튼이나 매칭**된다 — 조용한 가짜초록.

처방은 `src` 안에 e2e 를 전수 스캔하는 vitest 테스트를 두는 것
(`i18n/__tests__/login-strings-usage.test.ts`). 선례 `components/__tests__/skeleton-usage.test.ts`.
**개수 상한이 아니라 목록 전수 비교**로 쓴다 — 메모리 [[two-lists-never-check-each-other]] 의 지배 결함 양식.

## C3. e2e 사본이 17 파일 — 초기 목록(16개 인증 spec)이 틀렸다

`grep -rln "loginStrings.continueButton" apps/web/e2e` → 17 파일.
그중 **4개가 "인증 E2E" 목록 밖**이었다: `trusted-devices` `start-page` `saved-filters` `active-project`.
`fixtures/issue-fixtures.ts` 도 자체 사본(`loginAsBob`)을 갖고 있다.

원인은 픽스처가 alice 전용이라 변형이 필요할 때마다 복사한 것. 커밋 7에서 `loginAs(page, username)`
하나로 통합한다.

## C4. 식별자 라벨을 바꾸지 않는 것이 마이그레이션 비용을 결정한다

병합 화면의 첫 필드를 `login-username`(label `loginStrings.usernameLabel`) 하나로 유지한다.

- LDAP 식별자는 이메일이 아니라 `alice` 다(`login-ldap.spec.ts`). `이메일` 라벨은 **사실이 틀린다**
- `usernameLabel`·`passwordLabel`·`providerLabel`·`submitButton`·`providerLocal` 이 전부 생존 → 20+ locator 무편집

결과 각 e2e 파일 변경이 **2줄 삭제**로 붕괴한다. 카피를 바꾸고 싶으면 `i18n/ko.ts` 상수 값만
`'이메일 또는 사용자명'` 으로 바꾸면 spec 편집 0건으로 전파된다(`loginStrings` 문자열 동결 테스트는 없음을 확인).

## C5. eslint 가 radix Dialog 직접 import 를 차단한다

`apps/web/eslint.config.js:99-103` — `radix-ui` 의 `Dialog` importName 금지, 메시지는
"@/components/ui/dialog 래퍼를 사용하세요 (FR-UX-06 Phase 2)".

따라서 X 버튼 숨김·오버레이 blur 는 **래퍼에 가산 prop 을 다는 것 외의 선택지가 없다**.
`getByRole('dialog'` 가 e2e 215건이라 기본값을 반드시 현재 동작과 동일하게 유지한다.

## C6. 401 배선을 zustand 신규 스토어로 하는 이유

`authStore` 에 필드를 더하지 않는다 — `persist`(sessionStorage) 라 일시 UI 플래그가 영속되면
새로고침 후 `true` 로 되살아나 모달이 유령처럼 뜬다. `partialize` 수술보다 20줄 새 스토어가 싸다.

`clearSession()` 안에서 세팅하지도 않는다 — 로그아웃(`AccountMenu.tsx:61`)·whoami 롤백
(`useLoginMutation.ts:119`)·`MfaCodeInput` 에서도 불려서 전부 오탐이 된다.

`hadSession` 가드가 필요한 이유는 `dashboards.shared.$token` 이 미인증 공개 라우트이기 때문이다
(`router.ts:661-672`, `staticData:{requireAuth:false}`). 공개 페이지에 로그인 모달을 씌우면 안 된다.

## C7. 자동 SSO 리다이렉트를 폐기하는 이유

기존 2단계에서 `window.location.assign` 이 안전했던 것은 트리거가 **"계속" 클릭**, 즉 명시적 행위였기
때문이다. 단일 화면에서 트리거는 blur/디바운스로 **수동적**이라, 그 상태에서 풀 네비게이션을 걸면
타이핑 중이던 비밀번호와 함께 화면이 통째로 사라진다. 되돌릴 수 없다.

그래서 **SSO 버튼을 노출하되 자동 이동하지 않는다**. 로컬 제출 버튼은 `variant="outline"` 으로 강등하되
살려둔다 — FR-07 S4 "끊긴 라우트가 사용자를 막지 않는다" 를 그대로 지킨다.
매칭 도메인에 LOCAL/LDAP 계정이 공존할 수 있다는 점(FR-AU-06 명시 선택 보존)도 근거다.

## C8. 식별자 라벨을 '사용자명' 그대로 둔다 — 측정 후 뒤집은 결정

계획 D6 은 "카피를 바꾸고 싶으면 `i18n/ko.ts` 상수 값만 바꾸면 spec 편집 0건" 이라고 했다.
그 전제는 **e2e 에 대해서만** 참이었다. 실측:

| 소비처 | 참조 방식 | 건수 |
|---|---|---|
| e2e | `loginStrings.usernameLabel` 심볼 | 23 (무편집) |
| e2e | `'사용자명'` 리터럴 | 5 |
| src 유닛 테스트 | `'사용자명'` 리터럴 | 27 |

즉 라벨을 바꾸면 **32곳 리터럴 치환**이 필요하고, 그중 상당수가 `ChangePasswordForm` 등
로그인과 무관한 화면이다. Maxi 가 요청한 것은 "이메일 묻는 단계 삭제" 이지 라벨 변경이 아니다.

그리고 `사용자명` 은 **틀린 라벨이 아니다** — LDAP 로그인의 식별자는 실제로 `alice` 이고
`login-ldap.spec.ts` 가 그 경로를 계약으로 잡고 있다. Jira Cloud 가 `이메일` 을 쓰는 것과의
차이는 새 스펙의 `## Jira 대조` 표에 X 편차로 기록한다.

## C9. vitest 는 없는 모듈 import 를 "worker timeout" 으로 보고한다

`loginPromptStore` 부재 상태에서 `interceptor.test.ts` 를 돌리면
`[vitest-pool-runner]: Timeout waiting for worker to respond` 가 뜬다. 모듈 부재의 전형적
증상이 아니라 진단이 어렵다.

import 한 줄만 임시로 지워 돌려보니 기존 3건은 통과하고 신규 2건만
`useLoginPromptStore is not defined` 로 실패했다 — 원인이 확정됐고 기저 회귀가 아님도 같이 확인됐다.
**증상을 원인으로 착각하지 말 것.** 다음에 이 timeout 을 보면 먼저 신규 import 를 의심한다.

## C10. e2e 낡은 주석 sed 일괄 처리는 반드시 눈으로 확인한다

`// 2단계.` 접두를 일괄 제거했더니 `already-authed.spec.ts` 의 **시나리오 번호**까지 지워졌다.
그 파일의 "1단계/2단계/3단계" 는 로그인 단계가 아니라 테스트 진행 단계였다.
`2단계 인증`(MFA) 도 같은 문자열을 쓰므로 패턴이 겹친다 — 되돌려서 복구했다.

문법 검증은 `npx playwright test --list` 로 한다. e2e 는 타입체크 밖이라 이것이 유일한
파싱 게이트다 — 154 파일 742 테스트가 전부 열거되면 문법은 온전하다.
