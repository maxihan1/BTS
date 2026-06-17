# FR-MV-02 — 이동 시 히스토리 보존 + 링크 유지

> slug: fr-mv-02-move-preserve
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-17

## Brief

프로젝트 간 이슈 이동(FR-MV-01, 완료)이 이슈 `id`(UUID)를 보존하고 key만 변경하는 구조 위에서,
이슈에 연결된 모든 부속 데이터(히스토리·링크·Watcher·첨부 등 FK가 issue_id를 참조하는 것)가
이동 후에도 빠짐없이 보존됨을 보장·검증한다.

- 선행 완료: FR-MV-01(§6.1.1, 단건+서브태스크 동반 이동), FR-HS-01(§5.1.1, 이력), FR-LK-01(§5.3.1, 링크)
- D3(데이터 모델)은 이미 [x] — id 보존 + key만 변경 구조 기존재
- 남은 D단계: D1(도메인), D2(명세), D4(백엔드 FK 보존 검증), D5(invariant 비교 테스트), D6(프론트 갱신), D7(E2E)

원문(classify): backend / backend-engineer / issue-tracking BC

## 도메인 정리

- **BC**: issue-tracking (단일). cross-BC 직접 호출 없음 — 이동 자체는 FR-MV-01이 이미 구현, 본 FR은 그 위의 보존 검증 + 이력 기록.
- **영향 엔티티**: Issue(이동 대상), IssueHistory(`issue_change_group`/`issue_change_item`), Link(`issue_links`), Watcher(`issue_watchers`), Attachment(`issue_attachments`). **신규 엔티티 0**.
- **신규 용어**: 없음 (모두 glossary 기존재 — 이슈/이슈 키/IssueKeyRedirect/링크/워처/어테처).
- **기존 결정 충돌**: 없음. 오히려 ADR `2026-06-16-issue-move-semantics`가 본 FR을 직접 사전 범위 지정.

### 현재 이동 구현 실측 (Explore 조사, 코드 라인 인용)

- **(A) in-place UPDATE** — `IssueRepository.moveIssue()` (`backend/modules/issue-tracking/.../repository/IssueRepository.kt:1159-1191`)가 `project_id`/`key`/`current_state_key`/`resolution_id`/`custom_fields`/`parent_id`/`version`/`updated_at`만 UPDATE. **`id`(UUID PK) 불변, delete+insert 없음**.
- **(B) id 참조 부속 테이블 보존**:
  - `issue_links`(source/target UUID FK, V021)·`issue_attachments`(V023)·`issue_watchers`(V024) → id 불변이라 **자동 보존** (이동 영향 0).
  - `issue_change_group`/`issue_change_item`(V018) → issues에 **FK 없음**(append-only, 이력 보존 우선) + `issue_id` 컬럼 보존 + `issue_key` 컬럼에 **기록 시점 키 박제** → **자동 보존**.
  - `issue_components`/`issue_affects_versions`/`issue_fix_versions` → 이동 서비스가 **의도적 매핑 교체**(프로젝트 종속, FR-MV-01 설계). 미매핑은 제거.
- **(C) stale 위험**: 이력의 `issue_key`는 박제라 안전. project_id는 이력에 미저장이나, issue_key 박제 + (도입 예정) 이동 이벤트로 추적성 확보 가능. **신규 컬럼 불요 판단**.
- **(D) 갭**: 이동 자체가 이력에 **명시 기록 안 됨**. `IssueChangeDetector`의 SCALAR_FIELD_EXTRACTORS에 project/key 없음 → changelog에 "프로젝트 이동"이 안 보임(컴포넌트/상태 변경만 보임). 이동은 현재 앱 로그(`IssueMoveService.kt:323`)에만 존재.

### ADR가 FR-MV-02로 위임한 책임 (2026-06-16-issue-move-semantics)

- §18: "id 보존 → 히스토리·링크·워처·첨부 자동 보존. 이 점이 FR-MV-02를 **구조적으로 충족**."
- §128: "미매핑 컴포넌트/버전은 제거됨. **이력에 '이동 + 제거된 연결' 기록으로 추적성 확보 (FR-MV-02 / 히스토리).**"

→ FR-MV-02 = (1) 보존 invariant 명시 검증(D5) + (2) 이동 이벤트를 이력에 기록(D2/D4). "제거된 연결"은 detector가 이미 컴포넌트/버전 필드변경으로 포착 중 — **이동(project/key) 추적만 추가** 필요.

- **관련 ADR**: [docs/adr/2026-06-16-issue-move-semantics.md](../adr/2026-06-16-issue-move-semantics.md) (FR-MV-01, 본 FR 사전 범위 지정). 신규 ADR 후보 — 이동 이력 기록 방식(아래 범위 결정에 따라).

## 스펙

전체 스펙. [docs/specs/2026-06-17-fr-mv-02-move-preserve.md](../specs/2026-06-17-fr-mv-02-move-preserve.md)

핵심 3줄 요약.
- 이동은 id 보존 in-place UPDATE라 히스토리/링크/워처/첨부가 자동 보존 — invariant 통합 테스트로 명시 고정(D5).
- 갭은 단 하나: 이동 자체가 이력에 미기록(detector에 key 없음, 순수 이동은 no-op). → `IssueChangeDetector`에 `key` 추적 추가 → 이미 흐르는 `record(before, after)`가 이동을 기록.
- 프론트는 changelog i18n 라벨 1줄 + 이동 후 새 키 navigate(FR-MV-01 기존) + E2E.

범위(Maxi 확정): 검증 + 이동 이벤트 기록. 신규 DB 컬럼 0, 신규 엔드포인트 0, cross-BC 0.

## Brainstorming Check

✅ 통과 (1회, Maxi 결정 갭 0). G1(changelog 폴백 렌더 확인—라벨 1줄 추가)·G2(기존 detector 테스트 전수 실행)·G3(첨부는 DB행 보존으로 검증, MinIO 왕복 생략) 모두 구현 단계 처리.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
