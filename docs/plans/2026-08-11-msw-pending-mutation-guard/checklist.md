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

## 5. 성능 — ★회귀는 없었다 (내 대조군이 틀렸다)

- [x] `src/mocks` 49파일 통제 실험 — `setup.ts` 만 원복 (setup 24.4s → 43.0s, **+76%**)
- [x] 처방 ① `cleanup()` 제거 → **`expect.soft`** (실측으로 훅 체인 유지 확인)
- [x] 처방 ② `settlePendingMutations` 안의 RTL 을 **동적 import** 로 내림
- [x] **main baseline 실측 — 340.03s (577/577 · 워커 실패 0)**
- [x] **가드 적용 — 332.32s (578/578).** 차이 없음. 「226초 → 360초」는 **main 이 아니라
      프로브 켜진 상태**와 비교한 것이었고, 두 값은 세션 초·후반의 머신 상태 차이였다
- [x] 처방 2가지는 유지 — 통제 실험에서 비용이 실재했고, `expect.soft` 는 성능과 무관하게도
      D7 함정을 더 싸게 푼다

## 6. 최종 검증

- [x] `node_modules/.bin/tsc -p tsconfig.app.json --noEmit` (exit 0)
- [x] `node_modules/.bin/eslint src` (exit 0 · warning 8건은 선재)
- [x] `node_modules/.bin/vitest run` 전량 green (578/578 · 9443 테스트)
- [x] `bash scripts/verify-master-plan.sh` (exit 0 · FR 139/139)
- [x] `node scripts/build-doc-index.mjs --check` (exit 0 · drift 0)
- [x] **가드 뮤테이션 검증** — 봉합 2건(지연 · 수동 게이트)을 되돌려 각각 red 확인,
      나머지 33건은 통과 (무차별 red 가 아니다)
- [ ] PR 생성
