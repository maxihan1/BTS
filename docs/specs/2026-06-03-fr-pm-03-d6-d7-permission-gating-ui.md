# FR-PM-03 D6/D7 — 버전/컴포넌트 권한 게이팅 UI + E2E — 스펙

> slug: fr-pm-03-d6-d7-permission-gating-ui · type: feature(혼합)
> ADR: docs/decisions/2026-06-03-version-component-permission-query-and-gating.md
> 선행: FR-PM-03 D1~D5(PR #70), FR-PM-02 D6(PR #55), FR-CM-01(PR #64)/FR-VR-01(PR #68)

## 배경 / 범위

FR-PM-03 backend(D1~D5)가 prod 권한 리졸버 + MANAGE_COMPONENTS/MANAGE_VERSIONS 매트릭스(PROJECT_ADMIN 전용)를
채웠다. D6/D7은 그 권한을 프론트에서 게이팅한다. 새 화면 없음(기존 버전/컴포넌트 관리 UI의 버튼 비활성화).

**범위(backend 확장 + frontend 게이팅 + E2E)**
- backend(identity-access). 권한 질의 API에 MANAGE_* 노출 + non-prod fallback 빈 2개.
- frontend. 응답 스키마 확장 + 관리 버튼 fail-closed 게이팅.
- E2E. PROJECT_ADMIN 관리 가능 / 권한 없는 사용자 버튼 disabled.

**범위 밖**. 스킴 편집 UI(별도 FR). 새 컴포넌트/버전 관리 화면(이미 존재).

## 사용자 시나리오 (Given-When-Then)

- S1. **PROJECT_ADMIN** — Given MANAGE_COMPONENTS/MANAGE_VERSIONS 보유,
  When 버전/컴포넌트 설정 페이지, Then "추가"·각 행 "편집/삭제" 버튼 **활성**.
- S2. **권한 없는 사용자** — Given MANAGE_* false,
  When 같은 페이지, Then 관리 버튼 전부 **disabled**(+사유 표시). 조회는 정상.
- S3. **권한 로딩 중/에러** — Given useProjectPermissions isLoading/isError,
  When 렌더, Then 버튼 disabled(fail-closed — 권한 미확정 시 차단).
- S4. **백엔드 2중 방어** — Given 권한 없는 사용자가 UI 우회(직접 API),
  Then 백엔드 403(D1~D5 prod 리졸버). UI disabled는 1차, 서버 가드는 최종.
- S5. **non-prod 동작 불변** — Given dev/test, When 임의 사용자,
  Then DevAllow fallback이 MANAGE_* true 반환 → 버튼 활성(기존 개발 흐름 유지).

## 기능 요구사항 (FR)

### 백엔드 (identity-access)
- FR-B1. `MyProjectPermissionController`가 `ComponentPermissionResolver`/`VersionPermissionResolver`를 추가 주입하고,
  응답 `permissions` 맵에 `MANAGE_COMPONENTS`/`MANAGE_VERSIONS` 키를 추가한다.
- FR-B2. 컨트롤러가 `ProjectDirectory.resolveKeyToId(projectKey)`로 projectId 해석.
  null(미존재/소프트삭제) → MANAGE_* 둘 다 false(기존 "미존재 projectKey → 200 + false" 정책 일관).
  CREATE 권한은 기존대로 IssuePermissionResolver + IssueScope.Project(key).
- FR-B3. `DevAllowComponentPermissionResolver`/`DevAllowVersionPermissionResolver`(`@Component @Profile("!prod")`,
  항상 true) 신규 — DevAllowIssuePermissionResolver 1:1. 부팅 가드(컨트롤러가 @Profile prod 포트 소비).
- FR-B4. actorId는 인증 토큰(JWT/PAT)에서만 추출(기존 패턴). 요청 파라미터로 actor 미수신.

### 프론트 (apps/web)
- FR-F1. `projectPermissionsSchema.permissions`에 `MANAGE_COMPONENTS: z.boolean()`, `MANAGE_VERSIONS: z.boolean()`
  required 추가. project-permission **fixture/handler/mock 전수 갱신**(z.parse 실패 방지 —
  메모리 zod-schema-strengthen-inline-mock-fanout). grep로 누락 0 확인 + `pnpm --filter @bts/web typecheck`(tsconfig.app).
- FR-F2. ComponentList/VersionList에서 `useProjectPermissions(projectKey)`로 `permissions.MANAGE_COMPONENTS`
  /`MANAGE_VERSIONS` 게이팅. "추가" 버튼 + Row 편집/삭제 버튼 `disabled={!canManage}`.
- FR-F3. fail-closed. `canManage = !isLoading && !isError && permissions?.MANAGE_* === true`. 로딩/에러/false → disabled.
  FR-PM-02 IssueMetaPanel 동형(`canDelete`/`canEdit` 패턴).

## 비기능 요구사항 (NFR)
- NFR1. 권한 질의 1회 round-trip(CREATE + MANAGE_* 한 호출). useProjectPermissions staleTime 30s 캐시 재사용.
- NFR2. fail-closed 보안 — 권한 미확정(로딩/에러)에 버튼 노출 금지.
- NFR3. WCAG — disabled 버튼에 사유/aria 처리(FR-PM-02 선례 44px 터치 타깃 유지).
- NFR4. CI typecheck는 tsconfig.app(테스트 포함) — 로컬도 `pnpm --filter @bts/web typecheck`로 검증(메모리 ci-typecheck-tsconfig-app-vs-local).

## API 인터페이스 (REST)
변경. `GET /api/v1/users/me/project-permissions?projectKey={key}` 응답 확장(additive).
```
200 { "projectKey": "ATLAS", "permissions": { "CREATE": bool, "MANAGE_COMPONENTS": bool, "MANAGE_VERSIONS": bool } }
```
신규 엔드포인트 없음. 기존 CREATE 소비자 무영향.

## 데이터 모델 변경
없음. 마이그레이션 없음.

## 엣지 케이스
- EC1. **Zod strip 함정** — z.object는 모르는 키 조용히 버림 → 스키마 확장 안 하면 프론트가 MANAGE_* 못 봄. 스키마 확장 필수.
- EC2. **mock fan-out** — 스키마 required 키 추가 → 기존 project-permission fixture/inline mock z.parse 실패. 전수 갱신(issue-permission 계열은 별개, 무영향).
- EC3. **non-prod 부팅** — fallback 빈 누락 시 identity-access 컨텍스트 UnsatisfiedDependency. 모듈 전체 test로만 표면화(메모리 profile-scoped-bean-boot-failure) → 머지 전 :modules:identity-access 전체 test.
- EC4. **E2E 권한 토글** — 같은 사용자로 admin/non-admin 응답 토글: project-permission-handler가 localStorage 플래그 읽기 + addInitScript(메모리 e2e-msw-scenario-toggle-localstorage-flag). window.fetch monkeypatch 금지.
- EC5. **UI PR E2E 잠복** — 새 disabled 속성/셀렉터가 기존 E2E 깨지 않는지 기존 version/component E2E 함께 실행(메모리 ui-pr-defer-e2e-regression-latent).
- EC6. **projectKey 미존재** — resolveKeyToId null → MANAGE_* false(403 아님, 200+false). 프론트는 disabled.
- EC7. **기존 List/Row 단위테스트 회귀** — 게이팅 추가 시 권한 mock 없는 기존 ComponentList/VersionList 테스트는
  fail-closed로 버튼 disabled → 버튼 클릭 테스트 실패. 기존 테스트에 `useProjectPermissions` admin mock(MANAGE_* true)
  주입 필수(메모리 e2e-fixture-whoami-userid-alignment 유형 — 게이팅 도입이 기존 그린을 깸). Row가 canManage prop
  수령 구조면 prop 주입.
- EC8. **canManage prop 스레딩** — 편집/삭제는 Row에 있으므로 List가 useProjectPermissions로 canManage 계산 →
  Row에 prop 전달, 자기 "추가" 버튼도 게이팅. Row 자체가 훅 호출하지 않음(List 단일 호출 + prop, 중복 호출 방지).

## 제약 조건
- fail-closed 게이팅. TDD red→green. 임시 코드 금지.
- non-prod 동작/테스트 그린 유지(DevAllow 경로 + 기존 fixture).

## 측정 가능한 완료 기준
- [ ] 컨트롤러 확장 + DTO 맵 MANAGE_* 키 + DevAllow fallback 2 + 백엔드 테스트(권한 질의 200 응답에 MANAGE_* 포함, key→id null→false, non-prod 부팅).
- [ ] 프론트 스키마 확장 + fixture/mock 전수 갱신(grep 누락 0 + typecheck).
- [ ] ComponentList/VersionList/Row 게이팅 + 단위테스트(admin 활성 / non-admin·로딩·에러 disabled).
- [ ] E2E — PROJECT_ADMIN 관리 가능 / 권한 없는 사용자 disabled(localStorage 토글). 기존 version/component E2E 함께 그린.
- [ ] :modules:identity-access 전체 test + ktlint + detekt 그린, apps/web vitest + typecheck(tsconfig.app) + 해당 E2E 그린.

## Brainstorming Check

✅ 통과 (적대적 1-pass 자체 검토 — office-hours 스킵, 정의된 FR+선례 명확).

검토 보강 반영.
- EC7 추가 — 게이팅 도입이 기존 List/Row 단위테스트 그린을 깸(권한 mock 없으면 fail-closed disabled). admin mock 주입 필수.
- EC8 추가 — canManage는 List가 단일 호출 후 Row에 prop 전달(중복 훅 호출 방지).
- Zod strip(EC1)/mock fan-out(EC2)/non-prod 부팅(EC3)/E2E 토글(EC4)/기존 E2E 회귀(EC5) 명시.

미결정 없음(전부 선례 결정). 게이트 1에서 Maxi 최종 승인.
