# FR-VR-01 버전 관리 프론트엔드 (D6/D7) — 스펙

> slug: fr-vr-01-version-frontend · BC: issue-tracking · type: ui
> 백엔드: PR #67 머지 완료. 본 스펙은 프론트 UI(D6) + E2E(D7) 한정.
> 디자인 접근: 컴포넌트 설정 페이지(`projects.$projectKey.settings.components`, FR-CM-01 PR #64) 패턴 재사용.
> 선례 동형, 핵심 차이: 컴포넌트 리드(ComponentLeadSelect/useUsersByIds) 자리에 **날짜 입력 2개**(startDate/releaseDate).

## 사용자 시나리오 (Given-When-Then)

**S1. 목록 조회**
- Given 프로젝트 PROJ 설정에 접근하는 사용자가
- When `/projects/PROJ/settings/versions`에 진입하면
- Then 활성 버전이 name 오름차순 목록 표시된다. 각 행은 이름 · 설명 · 시작일 · 릴리즈 예정일 · 액션(수정/삭제). 날짜 미지정은 "—" 표시.

**S2. 생성**
- Given 버전 목록 화면에서
- When "버전 추가"를 눌러 이름(필수)·설명(선택)·시작일(선택)·릴리즈 예정일(선택)을 입력하고 저장하면
- Then 새 버전이 목록에 추가되고 Dialog가 닫힌다. (invalidate 후 refetch, 낙관적 아님)

**S3. 이름 중복**
- Given 이미 "v1.0" 버전이 있는 프로젝트에서
- When 같은 이름 "v1.0"으로 생성/수정(rename)하면
- Then 409 `VERSION_NAME_DUPLICATE` → "이미 같은 이름의 버전이 있습니다" 폼/토스트 에러. Dialog 유지. (백엔드 PR #67 P2-1 수정으로 rename 중복도 409)

**S4. 수정 (name/description + 날짜)**
- Given 버전 행에서
- When 수정 Dialog로 이름·설명·시작일·릴리즈일을 바꿔 저장하면
- Then **변경 감지 후 변경된 그룹만 호출**: name/description 변경 시 `PATCH /{id}`(문자열 sentinel), 시작일/릴리즈일 변경 시 `PATCH /{id}/dates`(두 키 항상 명시, null=해제). 둘 다 변경 시 둘 다 호출. 목록 갱신.

**S5. 날짜 해제**
- Given 시작일/릴리즈일이 지정된 버전에서
- When 수정 Dialog에서 날짜를 비우고 저장하면
- Then `PATCH /{id}/dates`에 해당 키 null 전송 → 해제되고 행에 "—" 표시.

**S6. 삭제**
- Given 버전 행에서
- When 삭제를 확인하면
- Then 소프트 삭제(204)되고 목록에서 사라진다.

**S7. 역순 날짜 허용**
- Given 생성/수정 Dialog에서
- When 시작일 > 릴리즈일로 입력해도
- Then 프론트는 순서를 강제하지 않는다(백엔드 미강제 정합). 정상 저장. (경고 표시는 범위 밖 — 향후)

**S8. 프로젝트 미존재/접근불가(404)**
- Given 미존재 projectKey 또는 접근불가
- When 페이지 진입 시 GET 목록이 404 `PROJECT_NOT_FOUND`
- Then 컴포넌트 설정 페이지와 동일한 접근 불가 안내 화면(members/components 선례 동형).

## 기능 요구사항 (FR)

- **FR1** 버전 목록 조회 — 4분기(로딩 스켈레톤/에러/빈/목록), name 오름차순. 행에 날짜 2열.
- **FR2** 버전 생성 — 이름(필수,≤255)·설명(선택,≤1000)·시작일(선택)·릴리즈일(선택) Dialog.
- **FR3** 버전 수정 — 한 Dialog에서 name/description + 날짜 편집. **변경 그룹별 분리 호출**: name/description→`PATCH /{id}`, 날짜→`PATCH /{id}/dates`. 변경 없는 그룹은 미호출.
- **FR4** 날짜 입력 — 시작일·릴리즈 예정일 date 입력(비우면 해제). 순서 미강제. 사용자 참조 없음(useUsers 불필요).
- **FR5** 버전 삭제 — 확인 후 소프트 삭제.
- **FR6** 에러 매핑 — 409/404/400 errorCode → i18n 메시지(폼 또는 toast).

## 비기능 요구사항 (NFR)

- **NFR1** TanStack Query 캐시 — 목록 queryKey `['versions', projectKey]`. mutation은 invalidate-only(메모리 mutation-setquerydata-partial-response-flicker 회피).
- **NFR2** Zod 스키마는 백엔드 `VersionResponse` DTO와 1:1(`{data:...}` 래퍼). 날짜는 `@JsonFormat(yyyy-MM-dd)` 직렬화라 `z.string().nullable()`(date 문자열). issues.ts/components.ts 관례(공유 ApiError + wrapped.data + body.errorCode 헬퍼).
- **NFR3** CSRF — mutation에 `X-XSRF-TOKEN`(readXsrfToken).
- **NFR4** 접근성 — 목록 `role`, 액션 버튼 aria-label, Dialog 포커스 트랩(shadcn).
- **NFR5** i18n — 라벨/에러 메시지 `version-labels.ts`로 분리(component-labels 선례).
- **NFR6** TypeScript strict — `tsc -p tsconfig.app.json` 통과(메모리 ci-typecheck-tsconfig-app-vs-local).

## API 인터페이스 (REST) — 백엔드 PR #67 확정

Base `/api/v1/projects/{projectIdOrKey}/versions`: POST(201) · GET(200 배열) · GET/{id}(200) · PATCH/{id}(200, name/description null=무변경) · **PATCH/{id}/dates(200, {startDate, releaseDate} 두 키 항상 명시, null=해제)** · DELETE/{id}(204). 응답 `{data: VersionResponse}`. **VersionResponse = `{id, projectId, name, description?, startDate?, releaseDate?}` 정확히 6필드**(ground-truth VersionResponse.kt — createdAt/updatedAt 없음, 추가 금지. 날짜는 @JsonFormat yyyy-MM-dd 문자열). errorCode: VALIDATION_FAILED/PROJECT_NOT_FOUND/VERSION_NOT_FOUND/VERSION_NAME_DUPLICATE/VERSION_ACCESS_DENIED/INTERNAL_ERROR. **LEAD_NOT_FOUND 없음**.

## 데이터 모델 변경

없음(프론트 전용). 신규 프론트 타입 `Version` + Zod `versionResponseSchema`.

## 엣지 케이스

- **날짜 변경 그룹 분리(EC-1)** — name/description은 `/`(결합 PATCH, sentinel), 날짜는 `/dates`(2-state each). 수정 Dialog는 두 그룹을 각각 변경 감지해 필요한 것만 호출. **주의: 이는 선례 동형이 아닌 신규 설계**(컴포넌트는 리드를 Row 인라인 셀렉터에서 별도 처리, Dialog는 단일 mutation). 버전 Dialog가 onSubmit에서 2개 mutation을 선택 분기. date input 빈 문자열은 `null`로 정규화 후 initial과 비교(변경 감지). 단위테스트로 0호출/날짜만 1회/둘 다 2회 강제.
- **날짜 직렬화 형식(EC-2)** — 백엔드 yyyy-MM-dd 문자열. date input value 동일 형식이라 변환 불필요. Zod는 string nullable.
- **null=무변경 vs 빈 문자열(EC-3)** — name/description sentinel은 컴포넌트 동형(변경 안 한 필드 미전송). 날짜는 /dates라 sentinel 무관(항상 명시).
- **빈 목록(EC-4)** — "아직 버전이 없습니다" 안내 + 추가 유도.
- **동일 텍스트 액션 버튼 다수(EC-5)** — 행마다 "수정"/"삭제" 반복 → E2E는 행 컨테이너 한정 셀렉터(메모리 ui-pr-defer-e2e-regression-latent, playwright-getbyrole-exact-strict-mode).
- **fixture UUID(EC-6)** — v4 형식(메모리 zod-v4-uuid-fixture-strictness).

## 제약 조건

- 사용자 조회 없음(버전은 리드/담당자 참조 0) → useUsers/useUsersByIds 불필요. 컴포넌트 대비 단순.
- 권한 UI 게이팅 — non-prod AlwaysAllow라 403 미발생, 백엔드가 actor 추출을 FR-PM-03로 이연 → **UI는 권한 분기 최소화**(라우트 requireAuth + 404 처리로 충분, 과한 권한 UI 금지). 컴포넌트 선례 동형.
- 새 외부 의존성 없음. 날짜 입력은 기존 ui/input(type=date) 또는 shadcn 기존 컴포넌트 재사용.

## 측정 가능한 완료 기준

- [ ] 단위 테스트(vitest) — 목록 4분기, 생성/수정/날짜변경/삭제 mutation, 변경 그룹 분리 호출, 에러 매핑, Zod 파싱. 전체 스위트 그린.
- [ ] `tsc -p tsconfig.app.json` 통과 + `pnpm --filter @bts/web lint` 통과.
- [ ] MSW 핸들러(version-handlers.ts) — stateful CRUD + /dates + errorCode 토글. 분기순서 백엔드 일치. mutation 결과 stateful 영속(메모리 msw-mutation-stateful-refetch).
- [ ] E2E(Playwright) — S1 목록, S2 생성, S4 수정, S5 날짜해제, S6 삭제 happy path. 기존 E2E 회귀 0(UI PR은 기존 E2E 함께 실행).
- [ ] 라우트 `/projects/$projectKey/settings/versions` 등록 + requireAuth.

## Brainstorming Check

✅ 통과 (직접 적대적 sanity check, office-hours 스킵 — 정의된 UI FR + 컴포넌트 프론트 1:1 선례).

발견·반영:
1. **날짜 수정 UI(EC-1, FR3, S4/S5)** — 컴포넌트는 리드를 행 인라인 셀렉터로 분리했으나 날짜 2개는 행 인라인이 복잡 → 수정 Dialog에 통합하되 저장 시 변경 그룹별 분리 호출(name/desc→PATCH, 날짜→/dates). Maxi 게이트1 검토 대상.
2. **사용자 참조 부재** — 컴포넌트 대비 리드/useUsers 전면 제거. 422 LEAD_NOT_FOUND 없음. UI 단순화.
3. **날짜 직렬화(EC-2)** — yyyy-MM-dd 문자열, date input과 동형이라 변환 불필요.
4. **순서 미강제(S7)** — 백엔드 정합, 프론트도 강제/경고 없음(향후).
5. **MSW stateful(완료기준)** + **E2E 행 컨테이너 셀렉터** + **fixture v4 UUID** + **tsconfig.app typecheck** 메모리 교훈 선반영.

미해소 결정 없음 — 날짜 수정 UI 방식만 게이트1에서 Maxi 확정.
