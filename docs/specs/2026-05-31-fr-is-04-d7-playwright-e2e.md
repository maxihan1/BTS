# FR-IS-04 D7 — Playwright E2E (PR 3/3) — 스펙

> slug: fr-is-04-d7-playwright-e2e
> type: qa / qa-engineer / BC=issue-tracking
> 작성: 2026-05-31
> D6(PR #46) UI를 브라우저 E2E로 검증. 새 도메인/계약 0.

## 목적

D6에서 구현한 본문(Write/Preview) + 우선순위/영향도/환경/라벨 UI를 Playwright(브라우저 자동화)로 happy path 검증. D6 spec 시나리오 S2/S4/S5/S6/S7/S8을 E2E로 옮긴다. 단위/통합은 D6에서 504 통과했으므로 E2E는 **실제 브라우저에서 사용자 흐름이 끊김 없이 동작**하는지 + MSW stateful refetch 후 화면 반영을 검증.

## 전제 (실행 환경)

- 앱 내장 MSW(목 서버) 위에서 동작. `page.route` 가로채기 안 씀(기존 E2E 패턴). PATCH stateful 핸들러는 D6에서 `issue-handlers.ts`에 5필드 영속 구현됨.
- fixture: `loginAsAlice(page)` + `ATLAS-1` 이슈. `e2e/fixtures/issue-fixtures` 헬퍼 재사용.
- **메모리 가드**: worktree node_modules 정상성 확인(Vite dev 부팅 전제, 깨졌으면 main dist cp 복구) / serviceWorkers:'block' 금지(MSW 부팅) / 5173 orphan Vite / getByRole strict mode(본문·메타 "저장" 버튼 중복 → 컨테이너 한정).

## E2E 시나리오 (Given-When-Then) — happy path 중심 6건

### E1 — 본문 작성 → 저장 → Preview 렌더 (D6 S2)
- **Given** ATLAS-1 상세, 본문 없음("본문 없음" placeholder)
- **When** 편집 진입 → Write 탭 textarea에 Markdown 입력 → 저장
- **Then** PATCH 후 refetch → Preview에 descriptionHtml(렌더된 본문) 표시. placeholder 사라짐.

### E2 — 우선순위 변경 → 갱신 (D6 S4)
- **Given** 메타패널 우선순위 셀렉터(기본 Medium)
- **When** 다른 우선순위(예 Highest) 선택
- **Then** 즉시 PATCH → refetch 후 셀렉터 현재값이 변경값으로 표시.

### E3 — 영향도 설정 + 미지정 disabled (D6 S5, 제약 검증)
- **Given** 메타패널 영향도 셀렉터(초기 "미지정")
- **When** 영향도(예 High) 선택
- **Then** PATCH → 갱신. **그리고 설정 후 "미지정" 옵션이 disabled**(백엔드 클리어 sentinel 부재 정합).

### E4 — 라벨 추가 → 저장 → 칩 표시 (D6 S6)
- **Given** 메타패널 라벨 입력
- **When** 라벨 입력 후 추가(Enter) → 저장
- **Then** PATCH {labels} → refetch 후 칩 목록에 표시.

### E5 — 환경 입력 → 저장 (D6 S7)
- **Given** 메타패널 환경 텍스트
- **When** 입력 후 저장
- **Then** PATCH {environment} → refetch 후 환경값 표시.

### E6 — OCC 409 충돌 toast (D6 S8)
- **Given** stale version 상태(MSW가 409 VERSION_CONFLICT 반환하도록 유도)
- **When** 메타필드 저장
- **Then** 충돌 toast 노출 + 재조회. (선례 issue-edit-conflict.spec.ts 패턴)

## 비기능 / 제약

- 기존 E2E 9건(issue baseline) 회귀 0.
- E2E는 happy path + 제약(E3 disabled) + 에러(E6) 커버. 전수 조합은 단위테스트(D6 504)에 위임.
- 코드 변경은 `e2e/*.spec.ts` 신규 파일 + (필요 시) `e2e/fixtures` 보강 + (필요 시) MSW 409 유도 핸들러. **구현 코드(src/) 수정 금지**(qa-engineer 책임 경계).

## 측정 가능한 완료 기준

- [ ] `e2e/issue-body-meta.spec.ts`(또는 분리) 6 시나리오 작성·통과.
- [ ] 기존 E2E 전부 통과(회귀 0).
- [ ] MSW stateful refetch 후 화면 반영 확인(E1/E4/E5 — placeholder→본문, 칩 표시).
- [ ] getByRole strict mode 위반 0(본문·메타 "저장" 버튼 컨테이너 한정).

## Brainstorming Check

✅ 통과 (직접 sanity review). gap 1건 — E6 OCC 409 유도 방법 모호 → 기존 `issue-edit-conflict.spec.ts`(summary OCC 409 검증)의 핸들러/패턴 재사용으로 해소(메타필드도 같은 updateIssue→같은 409 로직). 나머지(본문 null fixture=ATLAS-1, "저장" 버튼 strict mode 컨테이너 한정, 미지정 disabled)는 spec NFR/E3에 명시. Maxi 결정 필요 항목 없음. 핵심 위험은 E2E 실행 전제(worktree node_modules / 5173 orphan / serviceWorkers block) — 전부 전제 섹션에 메모리 가드 명시.
