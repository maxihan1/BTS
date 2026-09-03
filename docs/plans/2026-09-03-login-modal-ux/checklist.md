# checklist — 로그인 UX 개편 (전역 모달 + 단일 화면 폼)

`bts.maxihan.com` 미인증 진입 시 로그인 미표시 해소. FR-AU-07 identifier-first 폐기 · FR-AU-09 S5 편차.
계획 정본 `~/.claude/plans/bts-maxihan-com-wondrous-micali.md` · 결정 근거 `context-notes.md`.

## 0. 착수

- [x] worktree `login-modal-ux` 생성
- [ ] `pnpm install` 완료 · `node_modules/.bin` 확인
- [x] checklist · context-notes 생성

## 1. RED — `test:` 커밋 (D8 9건)

- [ ] #1 `router.test.tsx` 미인증 `/` → `role="dialog"` name `BTS 로그인`
- [ ] #2 `routeGuard.test.tsx` `redirectToStartPage` describe 신규
- [ ] #3 `LoginForm.test.tsx` 초기 렌더에 username+password+로그인 동시 가시 · `계속` 부재
- [ ] #4 `LoginForm.test.tsx` blur → `fetchRoute` → SSO 버튼 노출 · `location.assign` 미호출
- [ ] #5 `api/interceptor.test.ts` refresh 401 → `sessionExpired === true`
- [ ] #6 `api/interceptor.test.ts` 세션 없던 401 → 플래그 `false` 유지
- [ ] #7 `auth/LoginDialog.test.tsx` 신규 — open 조건 · ESC 불변 · 오버레이 불변 · X 부재 · 만료 시 라우트 유지
- [ ] #8 `auth/LoginDialog.test.tsx` — `routes/login.test.tsx:56-100` returnTo/startPage 5건 이관
- [ ] #9 `i18n/__tests__/login-strings-usage.test.ts` e2e 전수 스캔 → 0건
- [ ] **red 확인 (비-공허)** — 9건이 전부 실제로 빨간지 눈으로 본다

## 2. GREEN — 커밋 2 · dialog 프리미티브 가산

- [ ] `DialogContent` 에 `overlayClassName?: string` · `showCloseButton?: boolean = true`
- [ ] `dialog.test.tsx` 단독 초록 확인 (기본값 = 현재 동작)

## 3. GREEN — 커밋 3 · 401 프롬프트

- [ ] `auth/loginPromptStore.ts` 신규 (비영속 zustand)
- [ ] `api/client.ts:71-74` `hadSession` 가드 + `promptSessionExpired()`
- [ ] #5 #6 초록

## 4. GREEN — 커밋 4 · 모달 + 배경

- [ ] `auth/AuthBackdrop.tsx` 신규 (props 0 · hook 0 · fetch 0)
- [ ] `auth/LoginDialog.tsx` 신규 · 3닫기경로 봉인
- [ ] `routes/__root.tsx` 마운트
- [ ] `routes/login.tsx` 축소 (`<main>` 유지 필수)
- [ ] `routes/login.test.tsx` 축소
- [ ] #7 #8 초록

## 5. GREEN — 커밋 5 · 인덱스 라우트 가드

- [ ] `routeGuard.ts` `redirectToStartPage` 신설
- [ ] `router.ts` `indexRoute` 가드 + `component`·import 삭제
- [ ] `routes/index.tsx` 삭제
- [ ] #1 #2 초록

## 6. GREEN — 커밋 6 · 이메일 단계 제거 (★ e2e 동반)

- [ ] `LoginForm.tsx` — `LoginStep1`·`emailSchema`·`handleEmailContinue` 삭제 · `step` 2값화
- [ ] `LoginCredentialsForm` 개명 + blur/디바운스 훅 + `matchedRoute` SSO 버튼
- [ ] `i18n/ko.ts` — `emailLabel`·`continueButton`·`emailStepDescription` 삭제 · 신규 2건 추가
- [ ] `LoginForm.test.tsx` · `.saml.test.tsx` · `.oidc.test.tsx` 조정
- [ ] **e2e 17 파일** 각 2줄 삭제
- [ ] `e2e/login-modal.spec.ts` 신규 S1~S4
- [ ] #3 #4 #9 초록
- [ ] `grep -rn "loginStrings.continueButton\|loginStrings.emailLabel" apps/web/e2e` → 0건

## 7. 커밋 7 · e2e 헬퍼 통합

- [ ] `loginAs(page, username)` 로 사본 통합 (동작 무변경)

## 8. 커밋 8 · 문서 전수 동기화

- [ ] `docs/specs/2026-06-09-fr-au-07-provider.md` deviation
- [ ] `docs/specs/2026-05-21-fr-au-09-login-form-ui-d6.md` S5 deviation
- [ ] `docs/plan/product/identity-access.md` · `docs/sdd/` · `CHANGELOG.md`
- [ ] `docs/decisions/` ADR 1건 (닫기 봉인 + 자동 리다이렉트 폐기)
- [ ] 새 스펙 `## Jira 대조` 표 (J 항목 + X 편차 2건)
- [ ] `node scripts/build-doc-index.mjs`
- [ ] `bash scripts/verify-master-plan.sh` EXIT 0

## 9. 최종 검증

- [ ] `pnpm --filter @bts/web test` 전량
- [ ] `pnpm --filter @bts/web typecheck` · `lint`
- [ ] `pnpm --filter web test:e2e` 152 spec
- [ ] `pnpm verify`
- [ ] 눈확인 라이트·다크 (계획 §검증 9항목)
- [ ] PR 생성
