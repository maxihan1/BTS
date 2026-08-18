# FR-PM-02 D6/D7 — 이슈 권한 기반 액션 버튼 비활성화 UI + E2E — 스펙

> slug: fr-pm-02-issue-permission-ui
> 작성: 2026-06-02 (office-hours 스킵, 직접 기술 스펙)
> 도메인 결정: Jira mypermissions식 권한 조회 API (ADR 2026-06-02-issue-permission-query-api.md)
> 게이트 범위: 상세 화면 수정+삭제만 (Maxi 결정 2026-06-02). 목록 "새 이슈"(CREATE)는 후속.

## 배경 사실 (조사 결과)

- 백엔드 PR #53은 권한 **강제(enforcement)** 만 구현. 권한 없으면 403 + `ACCESS_DENIED`(RFC 7807).
- 권한 매트릭스(기본 스킴, V008):
  - PROJECT_ADMIN → CREATE_ISSUE, EDIT_ISSUE, DELETE_ISSUE
  - MEMBER → CREATE_ISSUE, EDIT_ISSUE (DELETE 제외)
  - 비멤버 → 전부 거부 (VIEW도 거부 → 이슈 조회 자체 불가)
- permission_code(명부 어휘) ↔ IssuePermission(포트 어휘) 통역: CREATE_ISSUE↔CREATE, EDIT_ISSUE↔UPDATE, DELETE_ISSUE↔SOFT_DELETE.
- 프론트 현황: 이슈 수정/삭제 버튼이 모두 권한 체크 없이 항상 노출. whoami에 역할 없음. 권한 조회 API 없음.
- 상세 화면은 issueKey 보유. "저장" 버튼 텍스트 4중복(제목/본문/환경/라벨).

### 게이트 범위 결정 근거 (sanity check)

현재 매트릭스에선 비멤버가 VIEW도 막혀 상세 화면에 도달 못 하므로, 상세 화면 도달자는 이미 멤버 →
EDIT/CREATE는 항상 O. 멤버끼리 갈리는 유일한 권한이 DELETE(ADMIN만)다. 따라서 **현재 실효는
"MEMBER의 삭제 버튼 비활성"** 하나. 수정 버튼 게이트는 같은 1콜로 받으니 추가비용 0 + 미래 매트릭스
대비로 포함. 목록 "새 이슈"(CREATE, 프로젝트 스코프, 별도 콜 + 현재 no-op)는 후속으로 분리.

## 사용자 시나리오 (Given-When-Then)

### S1 — ADMIN: 수정·삭제 모두 활성
- Given: PROJECT_ADMIN 사용자가 로그인
- When: 이슈 상세(예: ATLAS-1) 화면을 연다
- Then: 수정(제목/본문/메타) 버튼과 "이슈 삭제" 버튼이 모두 활성

### S2 — MEMBER: 삭제만 비활성
- Given: MEMBER 사용자가 로그인
- When: 이슈 상세 화면을 연다
- Then: 수정 버튼은 활성, **"이슈 삭제" 버튼은 비활성(disabled)**. 비활성 버튼 hover/focus 시 "삭제 권한이 없습니다" 안내(tooltip 또는 aria 라벨)

### S3 — 권한 로딩 중
- Given: 권한 조회 API 응답 전
- When: 화면이 막 렌더됨
- Then: 권한 액션 버튼(수정/삭제)은 **로딩 중 비활성**(안전 기본값 = 막음). 응답 도착 후 권한대로 전환

### S4 — 권한 조회 실패
- Given: 권한 조회 API가 네트워크/5xx 실패
- When: 화면 렌더
- Then: 권한 액션 버튼은 비활성 유지(fail-closed). 백엔드가 최종 403 차단하므로 보안 영향 없음. 콘솔 로깅은 NEVER-15 준수(민감정보 비노출)

### S5 — 비활성 우회 강행
- Given: 비활성 버튼을 우회한 권한 없는 요청이 서버 도달
- When: PATCH/DELETE 요청
- Then: 백엔드 403 ACCESS_DENIED. 프론트는 기존 mutation onError 경로로 안내(이미 존재)

## 기능 요구사항 (FR)

- **FR-1 (백엔드)**: 이슈 스코프 권한 조회 엔드포인트 신설(**identity-access BC**). 현재 인증 사용자가
  특정 이슈에 대해 가진 IssuePermission(UPDATE/SOFT_DELETE [+TRANSITION 참고])을 boolean 맵으로 반환.
  WhoamiController의 JWT/PAT actor 추출 패턴 재사용 + IdentityAccessIssuePermissionResolver 직접 호출.
- **FR-2 (프론트)**: 권한 조회 API client + Zod 스키마 + TanStack Query 훅(`useIssuePermissions(issueKey)`).
- **FR-3 (프론트)**: 이슈 상세 화면의 수정(UPDATE) 버튼들과 삭제(SOFT_DELETE) 버튼을 권한 응답으로 disabled 분기.
- **FR-4 (프론트)**: 비활성 버튼에 사유 안내(tooltip/aria-disabled + 접근성 라벨).
- **FR-5 (E2E)**: Playwright — S1(ADMIN 수정·삭제 활성) + S2(MEMBER 삭제 비활성) 최소 2종.

### 범위 밖 (후속)
- 목록 "새 이슈"(CREATE, 프로젝트 스코프) 게이트 — 현재 no-op, 별도 프로젝트 스코프 조회 필요.
- 생성 폼(issues.new.tsx)의 동적 projectKey 권한 체크.
- 상태 전환(TRANSITION) 버튼 게이트 — 매트릭스 밖(멤버 게이트). 응답엔 포함하되 UI 미사용.

## 비기능 요구사항 (NFR)

- **NFR-1 성능**: 상세 화면당 권한 조회 1콜. resolver N회 호출이면 엔드포인트 내부에서 처리(프론트 왕복 1회). TanStack Query staleTime으로 화면 내 중복 방지.
- **NFR-2 보안**: JWT 인증 필수, **본인 권한만**(actorId = 인증 사용자, 바디로 actor 안 받음). PAT 정책은 기존 이슈 API와 동일.
- **NFR-3 안전 기본값**: 권한 미확정(로딩/실패) 시 액션 버튼 비활성(fail-closed).
- **NFR-4 일관성**: 프론트는 권한 매트릭스를 하드코딩하지 않음. 응답 boolean만 소비.

## API 인터페이스 (REST)

identity-access BC, whoami와 같은 "me" 네임스페이스:

```
GET /api/v1/users/me/issue-permissions?issueKey={issueKey}
Authorization: Bearer <JWT 또는 PAT>

200 OK
{
  "issueKey": "ATLAS-1",
  "permissions": {
    "UPDATE": true,
    "SOFT_DELETE": false,
    "TRANSITION": true        // 참고용, 본 UI 미사용
  }
}
```

- 이슈 단위(scope=Issue) 권한만 반환. CREATE(프로젝트 스코프)는 후속 엔드포인트.
- actor = 인증 사용자(JWT `jwt.subject` UUID 또는 PAT `pat.userId`), WhoamiController 패턴 재사용.
- 비멤버(VIEW 없음)는 어차피 상세 화면 진입 불가. 이 엔드포인트는 인증된 사용자에 대해 200 + 권한 boolean 반환(조회는 본인 권한 확인용, 403 아님).
- 권한 평가: IdentityAccessIssuePermissionResolver.hasPermission(userId, UPDATE/SOFT_DELETE/TRANSITION, IssueScope.Issue(issueKey)) 3회 호출 → boolean 맵.

## 데이터 모델 변경

- **없음.** 기존 permission_schemes / role_permissions / project_permission_scheme(V008) 조회만. 마이그레이션 0건.

## 엣지 케이스

- EC-1: 존재하지 않는 issueKey → IssueController 기존 404 패턴 정합(plan 확정). 본 UI는 존재 이슈에서만 호출.
- EC-2: "저장" 버튼 4중복(제목/본문/환경/라벨) → E2E 셀렉터 strict mode 충돌. 컨테이너 한정 또는 data-testid 필수(메모리 교훈).
- EC-3: 권한 응답 전 빠른 클릭 → 로딩 중 비활성이라 클릭 불가(NFR-3).
- EC-4: 생성 폼/목록 CREATE 게이트는 범위 밖(후속). 비멤버는 목록 진입 불가 + 백엔드 403 최종 차단.
- EC-5: 권한/whoami fixture userId 불일치 → ADMIN 판정 실패(메모리 [[e2e-fixture-whoami-userid-alignment]]). E2E fixture userId 정합 필수.
- EC-6: MSW 권한 mock — 역할별 다른 응답을 stateful하게 다뤄야 fixture 전환 테스트가 가짜 그린 안 됨(메모리 [[msw-mutation-stateful-refetch]] 유형).

## 제약 조건

- BC: 권한 조회 엔드포인트는 identity-access(인증 사용자 추출 + 권한 평가 응집). resolver는 issueKey prefix로 projectKey 해석 → 이슈 데이터 미접근, BC 격리 유지.
- 프론트 권한 표시는 UX 힌트. 보안 경계는 백엔드 enforcement(변경 없음).
- 새 외부 의존성 없음(기존 TanStack Query/Zod/shadcn).
- UI PR이지만 권한 조회 엔드포인트(identity-access 백엔드) 포함 — same-FR 보조 슬라이스. PR 제목 [ui] 유지하되 백엔드 슬라이스 포함 사유 plan 명시.

## 측정 가능한 완료 기준

- [ ] 권한 조회 엔드포인트 통합테스트(Testcontainers): ADMIN→UPDATE/SOFT_DELETE true, MEMBER→SOFT_DELETE false/UPDATE true, 비멤버→전부 false.
- [ ] 프론트 단위테스트: useIssuePermissions 훅 + 버튼 disabled 분기(true/false/로딩/실패 4상태).
- [ ] Playwright E2E: S1(ADMIN) + S2(MEMBER 삭제 비활성) 통과.
- [ ] 기존 이슈 E2E 회귀 0(셀렉터 충돌 없음) — UI PR이 기존 이슈 E2E 함께 실행(메모리 교훈).
- [ ] tsc --noEmit + vitest + ktlint(신규 파일, 모듈 ktlintFormat 금지) + detekt 통과.

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check, office-hours 스킵 — 정의된 FR + 도메인 grill 완료).
발견·반영: (1) 현재 매트릭스에서 실효는 삭제 게이트뿐 → 게이트 범위 Maxi 결정(상세 수정+삭제만, CREATE 후속).
(2) fail-closed 기본값(S3/S4) 명시. (3) "저장" 4중복 셀렉터 위험(EC-2) + fixture userId 정합(EC-5) +
MSW stateful(EC-6) 메모리 교훈 선반영. (4) BC 격리 — 포트 경유 + same-FR view layer 사유 명시.
