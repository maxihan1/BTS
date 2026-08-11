# checklist — pending mutation 누수 봉합 + 전역 가드 승격 (R2)

TODOS 「apps/web(테스트 인프라) — 지연 MSW 핸들러 34개 파일의 pending mutation 누수 미측정」 해소.

## 0. 측정 (완료)

- [x] 계측 프로브 작성 (`MutationCache.prototype.build` 래핑)
- [x] 양성 대조군 — `FavoriteButton.test.tsx` pending 3건 검출
- [x] 음성 대조군 — `AutomationYamlImportDialog.test.tsx` mutation 18개 · pending 0건
- [x] 전량 1회차 — 577/577 통과 · 누수 10파일 13건
- [x] 전량 2회차 — 파일::테스트 단위 **차이 0건** (flaky 아님)
- [x] 34-set·22-set 기준선 재현 (34 / 22 / 밖 20 — TODOS 수치 전건 일치)

## 1. RED (완료 · 커밋 `15f82201c`)

- [x] `src/test/pending-mutation-guard.ts` (추적 + 수집 + 정착 대기 헬퍼)
- [x] `src/test/setup.ts` 전역 `afterEach` 배선
- [x] 전량 RED → **16건 / 12파일**. 측정(13건/10파일)보다 많았고 차이를 전건 규명했다
  - [x] `WatchersSection` S9a·S9c **2건** — 측정이 놓친 진짜 누수 (형제 파일, 구조 동일)
  - [x] `msw-single-setupserver` 1건 — 내 **주석**이 그 판별식 문자열을 그대로 담아 걸렸다 (주석 수정)

## 2. GREEN — 누수 15건 봉합 (완료 · 커밋 `47937a6ac`)

- [x] `components/favorite/FavoriteButton.test.tsx` (3건 · S5a 무한 Promise → 게이트 교체)
- [x] `components/issue/WatchersSection.test.tsx` (2건 · 수동 게이트)
- [x] `components/settings/ProfileForm.test.tsx` (2건 · 지연 200ms)
- [x] `api/useUpdateIssueSummary.test.ts` (1건 · 지연 50ms)
- [x] `components/auth/ChangePasswordForm.test.tsx` (1건 · 지연 100/200ms)
- [x] `components/automation/GitWebhookSection.test.tsx` (1건 · 지연 50ms · 로컬 서버)
- [x] `components/automation/RuleExecutionTraceRow.test.tsx` (1건 · **수동 게이트**)
- [x] `components/issue/__tests__/CreateIssueDialog.test.tsx` (1건 · **수동 게이트**)
- [x] `components/issues/CloneIssueDialog.test.tsx` (1건 · 지연 100ms)
- [x] `components/settings/PreferencesForm.test.tsx` (1건 · 지연 50ms)
- [x] `routes/issues.$key.test.tsx` (1건 · **고정 10초 지연 → 게이트 교체**)

## 3. 비-공허 짝 (완료 · 커밋 `15f82201c`)

- [x] `src/test/pending-mutation-guard.test.ts` 4종
  - [x] ① 배선 — `setup.ts` 가 추적을 실제로 켰다
  - [x] ② 비-공허 — 새 QueryClient 의 mutation 이 레지스트리에 잡힌다
  - [x] ③ 양성 — 정착하지 않은 mutation 을 검출한다
  - [x] ④ 음성 — 정착한 mutation 은 검출하지 않는다
- [x] 계약 테스트 자신이 전역 가드에 걸리지 않는다 (테스트 안에서 정리)

## 4. 문서 동기화 (완료 · 커밋 `6ada7c069`)

- [x] `TODOS.md` 항목 해소 처리 + 실측 근거 7개 절
- [x] 34-set 양방향 오류 기록 (거짓양성 27/34 · 거짓음성 3/10)
- [x] 제3 관용구(수동 게이트) 기록 — 정적 grep 의 원리적 한계 실증
- [x] 미러 drift 36 → 34 정정 + 줄어든 진짜 이유 명시

## 5. 성능 회귀 봉합 (완료 · 커밋 대기)

첫 전량 GREEN 이 **226초 → 360초**였다. 초록이라 놓치기 쉬운 형태다.

- [x] 대조 실험 — `src/mocks` 49파일에서 `setup.ts` 만 원복 (setup 24.4s → 43.0s, **+76%**)
- [x] 원인 확정 — `setup.ts` 의 `@testing-library/react` static import 를 **전 파일**이 문다
- [x] 처방 ① `cleanup()` 제거 → **`expect.soft`** (실측으로 훅 체인 유지 확인)
- [x] 처방 ② `settlePendingMutations` 안의 RTL 을 **동적 import** 로 내림
- [ ] 전량 재측정 — GREEN 유지 + 소요 시간 확인

## 6. 최종 검증

- [x] `node_modules/.bin/tsc -p tsconfig.app.json --noEmit` (exit 0)
- [x] `node_modules/.bin/eslint src` (exit 0 · warning 8건은 선재)
- [ ] `node_modules/.bin/vitest run` 전량 green
- [ ] `bash scripts/verify-master-plan.sh` (exit 0 · FR 139/139)
- [ ] `node scripts/build-doc-index.mjs --check`
- [ ] **가드 뮤테이션 검증** — 봉합 1건을 되돌리면 red 가 나는가 (`git checkout --` 로 원복)
- [ ] PR 생성
