# FR-AU-10 D6/D7 — Context Notes (작업 중 결정 누적)

> plan: 2026-06-10-fr-au-10-d6-d7-audit-admin.md

## 결정 로그

- **2026-06-10 — 조회 API는 본 PR 범위**. #108 완료 노트대로 관리자 조회 GET 엔드포인트 부재. self-service `findRecent`는 user-scoped라 admin 전역조회 불가 → 신규 read view layer.
- **2026-06-10 — 주체 표시(Maxi)**. 백엔드 LEFT JOIN users(동일 BC). username/displayName 응답 포함, null fallback "(알 수 없음)". 삭제/미상 사용자도 행 보존(append-only 감사 목적).
- **2026-06-10 — 필터(Maxi)**. eventType + 기간(from/to) + userId. V021 인덱스 3종 정합.
- **2026-06-10 — 진입경로(Maxi)**. Header "관리 메뉴" nav 전체를 isSystemAdmin 게이팅 + 감사 로그 링크. 기존 워크플로우 스킴 링크도 함께 admin-only로 변경됨(의도적). 라우트 가드(workflow-schemes는 requireAuth)는 미변경 — surgical(다른 FR 범위). nav 가시성만 admin-only.
- **2026-06-10 — read model 분리**. 기존 AuthAuditLogService 인터페이스/InMemory 헬퍼 오염 회피 위해 별도 AuthAuditLogAdminQueryRepository. 2빈 충돌(EC-6) 회피.
- **2026-06-10 — eventType 전방호환**. entry.eventType은 Zod z.string()(백엔드 enum 추가 무파손). 필터 드롭다운·라벨은 AUTH_EVENT_TYPES 12종 const(백엔드 미러). T4 카운트 정합 테스트가 라벨 누락 1차 가드.
- **2026-06-10 — deviceFingerprint 제외**. V021 컬럼 존재하나 미사용(FR-MF-05 대비). admin 응답 lean.

## 회귀 사전확인

- 워크플로우 스킴 E2E(crud/mappings)는 `page.goto('/admin/workflow-schemes...')` 직접 URL → Header nav 게이팅 무영향. ✅
- Header.test.tsx만 갱신 필요(비관리자 alice 링크 노출 단언 → admin 기준 변경 + 비관리자 미노출 신설).
- E2E admin 로그인 = `loginAsSystemAdmin`(`__bts_e2e_is_system_admin` 플래그, fr-au-05-signup 선례) 재사용.

## 미해결/후속

- workflow-schemes 라우트 가드 requireAuth→requireSystemAdmin 정합은 본 PR 범위 외(별도 FR/하드닝). nav만 admin-only.
