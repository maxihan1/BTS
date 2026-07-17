<!-- 후속 기술부채 추적 — 리팩토링이 필요하나 현재 PR 범위 밖이라 보류한 항목 기록 -->

# TODOS

## identity-access — 컨트롤러 권한 게이트 DRY 부채 (D19, PR #277)

**결정 (Maxi 확정, plan-eng-review D19)**. `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` 상수 · `resolveActorId` ·
`requireSystemAdmin` 3종 복제는 **선재 부채**다. `global_permission_grants`(FR-PM-10, PR-1)가 신설한
`GlobalPermissionGrantController`가 기존 패턴을 한 벌 더 복제했다. **공통화는 지금 하지 않는다** — 공통화하면
PR-1이 N파일 리팩토링이 되어 글로벌 CLAUDE.md §3(surgical, 변경은 요청받은 것만)과 충돌하고 권한 PR의 리뷰
단위가 무너진다. 대신 복사 + 이 기록으로 후속 작업을 명시한다.

**중복 3종 (2026-07-17 grep 재확인 — PR #277 T4/T5/T6 반영 후 실측치, 추측 아님)**.

| 패턴 | 파일 수 (src/main) | 재확인 명령 |
|---|---|---|
| `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` 상수 | 14 | `grep -rlE "FORBIDDEN_RESPONSE\|UNAUTHORIZED_RESPONSE" backend/modules/identity-access/src/main --include="*.kt"` |
| `resolveActorId` 함수 | 7 | `grep -rl "resolveActorId" backend/modules/identity-access/src/main --include="*.kt"` |
| `requireSystemAdmin` 함수 | 3 | `grep -rl "fun requireSystemAdmin" backend/modules/identity-access/src/main --include="*.kt"` |

> ⚠️ **`requireSystemAdmin`은 plan D19가 인계한 "5파일"과 다르다.** plan-eng-review 원안(`docs/plans/2026-07-17-project-management-crud.md:1312`)은 pre-T6 기준 "4파일"로 적었고, T6이 `GlobalPermissionGrantController`에 1벌을 더 복제해 "5파일"이 될 것으로 예상했다(구두 인계). **grep 재확인 결과 실제로는 3파일**뿐이다 — `GlobalPermissionGrantController` · `IssueSecuritySchemeController` · `UserGroupController`. plan의 "4파일" 원안 자체가 애초 과다 계상이었던 것으로 보인다([[spec-stated-count-becomes-blindfold]] 재발 — 숫자를 그대로 물려받지 말 것). 후속 리팩토링 착수 시 이 grep 명령으로 다시 재검증할 것.
>
> `resolveActorId`는 검색 결과 8개 파일에서 매치되나, 1개(`GlobalPermissionGrantControllerTest.kt`)는 KDoc/주석에서 개념을 언급할 뿐 실제 중복 구현이 아니라 제외했다(`src/main`만 집계).

**후속 작업**. 공통 베이스 클래스 또는 shared-kernel 유틸로 추출 — 별도 PR로 분리해 리팩토링 리뷰 단위를 권한 변경과 섞지 않는다.
