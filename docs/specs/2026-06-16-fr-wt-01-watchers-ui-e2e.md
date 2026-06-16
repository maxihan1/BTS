# FR-WT-01 Watcher 프론트 D6/D7 — 스펙

> slug: fr-wt-01-watchers-ui-e2e · type: ui · agent: frontend-engineer · 2026-06-16
> 백엔드 D1~D5 PR #151(머지 완료)이 제공하는 API를 소비하는 프론트 UI + E2E.
> **D6 UI 범위(Maxi 확정 — Option A)**: self watch/unwatch 토글 + 감시자 카운트 + 감시자 명단(읽기 전용). **타인 수동 추가 UI 제외**(자동 watch가 담당자/보고자를 이미 등록하므로 불필요, 백엔드 타인추가 기능은 후속 확장 여지로 보존).

## 배경 — 자동 watcher 동작 (백엔드, 이미 구현됨)

| 트리거 | 자동 watcher 추가 |
|---|---|
| 이슈 생성 | 보고자 + 담당자 |
| 담당자 변경 | 새 담당자 |
| 컴포넌트 변경(리드 자동배정) | 해당 리드 |
| 멘션(@username) | ❌ 추가 안 함 (Jira와 동일 — 멘션은 알림만) |

프론트는 위 결과(감시자 목록/카운트/isWatching)를 GET으로 읽어 표시만 한다. 자동 추가 로직은 백엔드 소관.

## 사용자 시나리오 (Given-When-Then)

1. **감시 시작(self watch)**
   - Given 로그인 사용자가 자신이 감시하지 않는 이슈 상세를 본다 (`isWatching=false`)
   - When "보기"(watch) 버튼을 클릭한다
   - Then `POST /api/v1/issues/{key}/watchers`(본문 없음, self) 호출 → 버튼이 "보기 취소" 상태로, 카운트 +1, 명단에 본인 표시

2. **감시 중단(self unwatch)**
   - Given 로그인 사용자가 감시 중인 이슈 상세를 본다 (`isWatching=true`)
   - When "보기 취소"(unwatch) 버튼을 클릭한다
   - Then `DELETE /api/v1/issues/{key}/watchers/{myUserId}` 호출 → 버튼이 "보기" 상태로, 카운트 -1, 명단에서 본인 제거

3. **감시자 명단/카운트 표시**
   - Given 이슈에 감시자 N명(자동 추가된 보고자/담당자 포함)
   - When 이슈 상세 진입
   - Then 메타패널 감시자 섹션에 카운트 "N명"과 감시자 명단(displayName 목록)이 표시됨

4. **멱등 동작(중복 클릭/이중 상태)**
   - Given 사용자가 이미 감시 중
   - When (네트워크 지연 등으로) watch가 다시 호출돼도
   - Then 백엔드 201 멱등 → 화면은 invalidate 후 정합 상태로 수렴(중복 카운트 없음)

5. **권한 — VIEW만 있으면 self watch 가능**
   - 이슈 상세를 볼 수 있다(=VIEW)는 것은 self watch 권한 충족. 별도 권한 게이팅 불필요.
   - 단, 403(권한 박탈 등) 응답 시 토스트로 알리고 상태 롤백(invalidate).

## 기능 요구사항 (FR)

- **FR-1** 이슈 상세 메타패널에 감시자 섹션(레이블 "감시자" + 카운트 "N명")을 표시한다.
- **FR-2** self watch/unwatch 토글 버튼. `isWatching` 값으로 라벨/동작 분기. **라벨(Maxi 확정)**: 미감시="지켜보기", 감시중="지켜보는 중".
- **FR-3** 감시자 명단(displayName)을 읽기 전용으로 표시한다. 0명이면 "감시자가 없습니다." 표시.
- **FR-4** watch = `POST /watchers`(본문 없음). unwatch = `DELETE /watchers/{현재userId}`(`useAuthUser().userId`).
- **FR-5** mutation 성공/실패 모두 `onSettled`에서 감시자 쿼리 invalidate(낙관적 setQueryData 금지 — 부분응답 플리커 회피).
- **FR-6** 에러 처리: 422(USER_NOT_FOUND, self엔 사실상 미발생) / 403 / 404 시 토스트. errorCode 추출 헬퍼 사용.
- **FR-7** (Brainstorming 발견) 같은 상세 화면의 **담당자 변경 / 컴포넌트 변경** mutation은 백엔드가 해당 인물을 자동 watcher로 등록하므로, 성공 시 `issueWatchersKey(key)`도 함께 invalidate해 카운트/명단이 라이브 갱신되게 한다. (기존 `issues.ts` 담당자/컴포넌트 mutation 훅의 onSettled/onSuccess에 invalidate 1줄 추가.)

### 디자인 검토 반영 (plan-design-review)

- **FR-8 (상태 표현)** 감시자 섹션은 4가지 상태를 모두 표현한다. 로딩(조회 중 — 간단한 스켈레톤/문구), 에러(조회 실패 — 차분한 안내 문구, --destructive 남발 금지), 빈 상태("감시자가 없습니다."), 진행 중(watch/unwatch in-flight 동안 버튼 disabled+로딩 표시로 더블클릭 방지).
- **FR-9 (긴 목록 — 1,000명 조직)** 카운트는 항상 표시. 감시자 명단은 길어질 수 있으므로 기본 N명(예: 8명)까지 표시하고 초과분은 "+N명 더" 또는 max-height 스크롤로 접는다. 본인은 명단에서 "(나)" 표기로 식별 가능하게 한다.
- **FR-10 (접근성 WCAG AA)** 토글 버튼은 shadcn `<Button variant="outline" 또는 secondary>`(페이지 핵심 CTA 아님 — 그건 상태/전이). `aria-pressed`로 watch 상태 노출, 키보드 조작(Enter/Space), 모바일 터치 타깃 44px, 색 대비 AA. 아이콘 사용 시 텍스트 라벨 병기.
- **버튼 라벨 (Maxi 확정 — 게이트1)** "지켜보기 / 지켜보는 중"(Jira "Watch/Watching" 대응). 미감시→"지켜보기", 감시중→"지켜보는 중". ko.ts에 정의.

## 비기능 요구사항 (NFR)

- **NFR-1** API 모듈은 issue-tracking BC 관례(`issue-links.ts`)를 따른다. 신규 `issue-watchers.ts`.
- **NFR-2** Zod 스키마는 백엔드 DTO와 1:1 미러(`watchers[].{userId,displayName}`, `count:int>=0`, `isWatching:bool`). invent 금지.
- **NFR-3** 모든 mutation에 CSRF 헤더(X-XSRF-TOKEN) 주입 — 단, apiFetch가 자동 처리하면 그대로. issue-links 선례 따름.
- **NFR-4** i18n: 모든 사용자 노출 문자열은 `ko.ts` `issueDetailStrings`에 정의(콜론 종결 금지, ko.test 통과). 하드코딩 금지.
- **NFR-5** 단위테스트(vitest) + E2E(Playwright) 모두 통과. CI typecheck는 tsconfig.app 기준(pnpm typecheck로 동일 검증).

## API 인터페이스 (백엔드 정본, 변경 없음)

| 메서드 | 경로 | 요청 | 성공 | 에러 |
|---|---|---|---|---|
| GET | `/api/v1/issues/{key}/watchers` | — | 200 `{data:{watchers,count,isWatching}}` | 404·403 |
| POST | `/api/v1/issues/{key}/watchers` | `{userId?}`(없으면 self) | 201 멱등·본문없음 | 404·403·422 |
| DELETE | `/api/v1/issues/{key}/watchers/{userId}` | — | 204 멱등·본문없음 | 404·403 |

- GET만 `DataResponse` 래퍼(`{data}`). POST/DELETE는 본문 없음.
- `WatcherListResponse`는 `@JsonInclude(NON_NULL)` — 단 모든 필드 non-null이라 직렬화 영향 없음.

## 데이터 모델 변경

- 없음. 프론트 전용. 신규 테이블/마이그레이션 없음.

## 엣지 케이스

- **본인 userId 부재**: 미인증이면 상세 진입 자체가 막힘(라우트 가드). 방어적으로 user 없으면 버튼 비활성.
- **카운트와 명단 정합**: count는 명단 length와 항상 일치(백엔드 보장). 프론트는 응답값 그대로 사용, 자체 계산 금지.
- **자동 추가 watcher**: 보고자/담당자가 자동 포함될 수 있음 → 명단에 본인 외 사람이 나타나는 게 정상. unwatch는 본인만 가능(self).
- **빠른 토글 연타**: invalidate-only라 최종 GET이 정답. 낙관적 업데이트로 카운트 흔들지 않음.
- **403(권한 변경)**: watch 시도 중 403 → 토스트 + invalidate로 실제 상태 복원.

## 제약 조건

- 타인 수동 추가/제거 UI는 이번 범위 외(Maxi 확정). 백엔드 엔드포인트는 그대로 두되 프론트에서 호출 안 함.
- 사용자 검색 API 부재 → 어차피 타인 추가는 보류가 합리적.
- 자동 watch 로직은 백엔드 소관, 프론트에서 재현/추측 금지.

## 측정 가능한 완료 기준

- [ ] 이슈 상세 메타패널에 감시자 섹션(카운트+명단+토글 버튼) 렌더
- [ ] watch 클릭 → POST 호출 + 버튼/카운트/명단 갱신 (vitest)
- [ ] unwatch 클릭 → DELETE 호출(내 userId) + 갱신 (vitest)
- [ ] 명단 0명/N명 표시 분기 (vitest)
- [ ] E2E: watch→unwatch 토글 + 카운트 변화 + 명단 본인 표시/제거 (Playwright, MSW stateful)
- [ ] ko.test 통과(콜론 종결 0), pnpm verify(lint+typecheck+test+build) + E2E green
- [ ] 기존 이슈 상세 E2E 회귀 없음(텍스트 중복 버튼 컨테이너 한정 점검)

## Brainstorming Check

✅ 통과 (1회 iteration). gap 1건 발견·보강 — 담당자/컴포넌트 변경 시 자동 watcher 라이브 갱신(FR-7). 나머지(권한 게이팅 단순성·멱등·플리커 방지·CSRF·i18n 콜론)는 스펙에 이미 반영.
