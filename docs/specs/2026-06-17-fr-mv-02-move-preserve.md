# FR-MV-02 — 이동 시 히스토리 보존 + 링크 유지 (스펙)

> slug: fr-mv-02-move-preserve · BC: issue-tracking · type: backend(+frontend/E2E)
> 선행: FR-MV-01(이동) · FR-HS-01(이력) · FR-LK-01(링크) · FR-WT-01(워처) · FR-AC-01(첨부) 모두 완료
> 관련 ADR: `2026-06-16-issue-move-semantics`(§18 구조적 충족 · §128 이력 기록 위임)
> 범위 확정(Maxi 2026-06-17): **검증 + 이동 이벤트 이력 기록**(신규 DB 컬럼 0, 기존 FR-HS-01 인프라 재사용)

## 배경 — 무엇이 이미 되어 있고 무엇이 갭인가

FR-MV-01의 이동은 `issues` row를 **in-place UPDATE** 한다(`IssueRepository.moveIssue()` `IssueRepository.kt:1159-1191`).
`id`(UUID PK)는 불변이고 `project_id`/`key`/`current_state_key`/`resolution_id`/`custom_fields`/`parent_id`/`version`만 바뀐다.

그 결과 이슈 `id`를 참조하는 부속 데이터는 **자동 보존**된다(이동이 건드리지 않음).

| 부속 데이터 | 참조 방식 | 이동 시 |
|---|---|---|
| 히스토리 `issue_change_group`/`issue_change_item` (V018) | `issue_id` 컬럼(FK 없음, append-only) + `issue_key` 박제 | 자동 보존 |
| 링크 `issue_links` (V021) | `source_id`/`target_id` UUID FK | 자동 보존 |
| 워처 `issue_watchers` (V024) | `issue_id` UUID FK | 자동 보존 |
| 첨부 `issue_attachments` (V023) | `issue_id` UUID FK | 자동 보존 |
| 컴포넌트/버전 (V012/V017) | `issue_id` UUID FK | **의도적 매핑 교체**(FR-MV-01 설계, 프로젝트 종속) — 본 FR 범위 밖 |

**갭(D)**: 이동 자체가 **이력에 기록되지 않는다**. `IssueChangeDetector.SCALAR_FIELD_EXTRACTORS`(`IssueChangeDetector.kt:46-58`)에 `key`/`project`가 없어서, 이동 시 호출되는 `historyRecorder.record(before, after)` (단건 `IssueMoveService.kt:307`, 서브태스크 루트 `:458`·자식 `:504`)의 `detect()`가 키 변경을 포착하지 못한다. `after` 스냅샷에는 이미 `key=newKey`·`projectId=targetProjectId`가 들어 있다(`:310-312`). 즉 **detect 가 key 변경만 잡으면 이미 흐르는 record 호출이 이동을 기록**한다.

특히 비호환 항목이 전혀 없는 "순수 이동"(대상에 같은 state/component/version 존재, 커스텀필드 무변경)은 `detect()`가 빈 리스트 → `record()`가 no-op(`IssueHistoryRecorder.kt:61-64`) → **이동 흔적이 이력에 0건**으로 남는다. 이것이 본 FR이 닫는 핵심 갭이다.

## 사용자 시나리오 (Given-When-Then)

### S1. 이동해도 이력이 보존된다
- **Given** 사용자 A가 `BTS-1` 이슈를 만들고 제목/담당자/컴포넌트를 여러 번 변경해 이력이 N건 쌓였다.
- **When** A가 `BTS-1`을 프로젝트 `PROJ`로 이동한다(→ `PROJ-42`).
- **Then** 이동 후 `PROJ-42`의 변경 이력 조회(`GET /api/v1/issues/PROJ-42/changelog`)에 **기존 N건이 그대로 보이고**, 거기에 **이동을 나타내는 `key` 변경 항목 1건이 추가**된다(`key: BTS-1 → PROJ-42`). 이동이 status/resolution/customFields 등도 함께 바꾸면 그 항목들은 **같은 변경 그룹에 함께** 기록된다(이동은 1 그룹 N 항목). 기존 이력의 당시 키(`BTS-1`)는 박제되어 그대로 보존된다.

### S2. 이동해도 링크가 유지된다
- **Given** `BTS-1`이 `BTS-9`를 *blocks* 링크로 가리키고, `OTHER-3`이 `BTS-1`을 *relates* 링크로 가리킨다.
- **When** `BTS-1`을 `PROJ`로 이동한다(→ `PROJ-42`).
- **Then** `GET /api/v1/issues/PROJ-42/links`가 **두 링크를 모두 그대로 반환**한다(방향·타입 보존). 링크는 이슈 `id`로 연결되므로 cross-project 링크가 되어도 끊기지 않는다.

### S3. 이동해도 워처가 유지된다
- **Given** 사용자 B, C가 `BTS-1`을 지켜보고 있다(`issue_watchers` 2건).
- **When** `BTS-1`을 `PROJ`로 이동한다.
- **Then** `PROJ-42`의 워처 목록에 B, C가 **그대로 남아 있다**. (대상 프로젝트 VIEW 권한 유무와 무관하게 보존 — 권한 필터링은 알림 발송 시점 책임, 본 FR 범위 밖.)

### S4. 이동해도 첨부가 유지된다
- **Given** `BTS-1`에 파일 2개가 첨부되어 있다(`issue_attachments` 2건, MinIO 객체 보존).
- **When** `BTS-1`을 `PROJ`로 이동한다.
- **Then** `PROJ-42`의 첨부 목록에 두 파일이 **그대로 남아 있고 다운로드 가능**하다.

### S5. 서브태스크 동반 이동도 노드별로 보존·기록된다
- **Given** 부모 `BTS-1` + 자식 `BTS-2`(서브태스크), 각자 이력/링크/워처/첨부 보유.
- **When** 부모를 자식과 함께 `PROJ`로 이동한다(→ `PROJ-42` + `PROJ-43`).
- **Then** 부모·각 자식 모두 위 S1~S4의 보존이 성립하고, **각 노드의 이력에 이동 이벤트가 각각 1건씩** 기록된다.

### S6. 이동 후 페이지가 갱신되고 이동 이력이 보인다
- **Given** 사용자가 이동 마법사로 `BTS-1`을 이동한다.
- **When** 이동이 성공한다.
- **Then** SPA가 새 키(`PROJ-42`)로 자동 이동(navigate)하고(FR-MV-01 기존 동작), 변경 이력 패널에 **"이동" 항목**이 표시된다(새 i18n 라벨).

## 기능 요구사항 (FR)

- **FR1 (이동 이벤트 기록)**. `IssueChangeDetector`가 `before.key ≠ after.key`를 변경 항목으로 감지해 이력에 기록한다. 필드명 `key`, fromValue=옛 키, toValue=새 키. 이동 시에만 발생(키는 이동 외 경로에서 불변).
  - 라벨: `key`는 값이 곧 표시이므로 resolver passthrough(label=null). `status`/`summary`와 동일 정책. **resolver 변경·신규 의존성 없음**.
  - `project`(UUID)는 **별도 기록하지 않는다**(신규 ProjectRepository 의존성 회피, 단순성 우선 — Maxi 확정). 키 prefix(BTS-1→PROJ-42)가 출발/도착 프로젝트 키를 드러내 1차 추적성은 확보되나, 프로젝트 *이름*(키≠이름인 경우)은 key 항목만으로는 안 보인다 — 이는 수용된 트레이드오프. 프론트 라벨을 "프로젝트 이동"으로 둬 가독성 보완(리뷰 C4).
- **FR2 (보존 보장)**. 이동(단건·서브태스크 동반)은 이슈 `id`를 보존하여 히스토리·링크·워처·첨부 행을 **삭제·변경하지 않는다**. (현재 구현이 이미 충족 — 본 FR은 회귀 가드로 고정.)
- **FR3 (순수 이동도 기록)**. 비호환 매핑이 0건이어도 이동은 최소 `key` 변경 1건으로 이력에 남는다(no-op 회피).
- **FR4 (프론트 이력 표시)**. 변경 이력 패널이 `key` 변경 항목을 "이동"으로 표시한다(i18n 라벨 추가). 이동 후 SPA가 새 키로 자동 navigate(FR-MV-01 기존 동작 확인).

## 비기능 요구사항 (NFR)

- **NFR1**. 이동 트랜잭션 시간 영향 무시 가능(detect에 비교 1건 추가, DB 왕복 0 추가). 이슈 이동 p95 < 1s 임계(BC 게이트) 유지.
- **NFR2 (회귀 안전)**. detector에 `key` 추가가 기존 8개 진입점(create/update/transition/assignee/components/affects·fixVersions/softDelete)의 이력에 영향 0임을 보장(키는 그 경로들에서 불변). 기존 detector 테스트 전부 보존.
- **NFR3 (append-only 불변)**. 이동 이력도 FR-HS-01의 append-only·박제 정책을 따른다.

## API 인터페이스 (REST)

**신규 엔드포인트 없음.** 기존 재사용.
- `POST /api/v1/issues/{key}/move/preview`, `POST /api/v1/issues/{key}/move` — FR-MV-01, 변경 없음.
- `GET /api/v1/issues/{key}/changelog` — FR-HS-02, 이동 이력 항목이 자연히 포함됨(필드 `key`).
- `GET /api/v1/issues/{key}/links`, `.../watchers`, `.../attachments` — 보존 검증 대상, 변경 없음.

## 데이터 모델 변경

**없음.** D3은 이미 [x]. `issue_change_item.field`에 새 값 `key`가 들어갈 뿐, 스키마/마이그레이션 변경 0. (범위 결정 옵션1 — project_id 컬럼 추가 안 함.)

## 엣지 케이스

- **EC1 (순수 이동)**. 비호환 매핑 0건 → 기존엔 이력 0건(detect no-op). 본 FR로 최소 `key` 변경 1건 기록. (FR3) — **실제 recorder 통합 테스트로 검증**(mock 뒤 vacuous 통과 차단, 리뷰 B1).
- **EC2 (체인 이동)**. `BTS-1`→`PROJ-42`→`X-7` 두 번 이동 → 이력에 이동 이벤트 2건(`BTS-1→PROJ-42`, `PROJ-42→X-7`). 각 그룹의 박제 키는 당시 키.
- **EC3 (cross-project 링크)**. 이동 후 링크가 다른 프로젝트 이슈를 가리키게 됨 → 끊지 않음(Jira식, id 기반). 링크 조회/그래프가 정상 반환.
- **EC4 (워처 권한 부재)**. 워처가 대상 프로젝트 VIEW 권한이 없어도 워처 행 보존. 알림 발송 시점 권한 체크는 FR-NT 책임.
- **EC5 (서브태스크 동반)**. 부모·각 자식 모두 이동 이벤트 각각 기록 + 부속 데이터 노드별 보존.
- **EC6 (이동 외 키 불변 확인)**. update/transition 등에서 key는 절대 안 바뀌므로 이동 이벤트 오기록 0(회귀 테스트로 고정).

## 제약 조건

- **단일 BC**(issue-tracking). cross-BC 호출 추가 없음.
- **TDD 강제**(절대 규칙). detector 단위 테스트(red) → 구현(green).
- **detekt baseline 동결**(메모리: 신규 위반은 헬퍼 추출, baseline 재생성 금지).
- **i18n 라벨 콜론 종결 금지**(메모리: ko.test 자동 검증).

## 측정 가능한 완료 기준

1. `IssueChangeDetector`가 key 변경을 감지하는 단위 테스트 통과(이동만 발생, 비이동 경로 0건).
2. **invariant 통합 테스트(D5)**: 이력+링크+워처+첨부를 가진 이슈를 이동 → 이동 전후 각 부속 데이터 행 집합이 **동일**(+이동 이력 1건 추가)함을 단언. 단건·서브태스크 동반 둘 다.
3. 기존 이력/이동 테스트 전부 회귀 통과(detector 8진입점, FR-MV-01 이동 EC 통합).
4. 프론트 변경 이력 패널에 "이동" 항목 표시 + 이동 후 새 키 navigate E2E(D7).
5. `./gradlew :backend:modules:issue-tracking:test` + `pnpm verify` green.

## Brainstorming Check (Phase B) ✅ 통과 (1회, Maxi 결정 갭 0)

적대적 자가검토로 발견한 갭 — 모두 구현 단계서 처리(스펙 재작성·Maxi 결정 불요).

- **G1 (프론트 changelog가 새 `key` 필드를 렌더링하는가)** — **해소**. `changelog-labels.ts`의 `resolveFieldLabel`이 `issueDetailStrings.changelogFieldLabels[field] ?? field`로 **폴백 렌더**, `resolveValueLabel`이 `key`를 raw(키 문자열) 그대로 반환. 화이트리스트 드롭 없음. → 프론트는 i18n 라벨 `changelogFieldLabels.key`(예: "이동") 1줄 추가만으로 "이동: BTS-1 → PROJ-42" 표시. (impl 시 `IssueChangelog.tsx`가 전 항목 매핑·필드 필터 없음 재확인.)
- **G2 (detector에 `key` 추가가 기존 테스트 깰 위험)** — 비이동 경로(create/update/transition/assignee/components/versions/softDelete)에서 key는 불변이므로 영향 0. **단, fixture가 우연히 다른 key로 `after`를 구성하면 표면화** → impl 시 기존 detector+history+move 테스트 전수 실행으로 고정(NFR2). EC6 회귀 테스트 명시.
- **G3 (첨부 invariant 테스트에 MinIO 왕복 불요)** — 이동은 MinIO 객체를 건드리지 않으므로, 첨부 보존은 **`issue_attachments` DB 행 보존**으로 검증(객체 다운로드 왕복 생략). 테스트 경량화.
- **(범위 재확인) `project` 미기록** — 키 prefix가 프로젝트 이동을 드러내고 resolver에 ProjectRepository 신규 의존성을 피하므로 key-only 유지. plan-review에서 reviewer 재확인 항목.
- **(범위 밖 확인) 루트 parent_id=null 미기록** — 부모 detach는 FR-MV-01 의도 동작이고 detector가 parent를 추적하지 않음. 본 FR 범위 밖(이력/링크/워처/첨부 보존이 대상).
