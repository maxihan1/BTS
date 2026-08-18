# ADR — 워크플로우 정의의 정본을 YAML 에서 DB 로 옮긴다

> 날짜. 2026-08-18
> 상태. **채택 (Active)**
> 관련 FR. FR-WF-04 (워크플로우 CRUD + 전역 상태 카탈로그)
> 관련 문서. `docs/plan/product/project-workflow.md §2.4` · `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`
> 대체 관계. **`docs/adr/2026-05-21-workflow-yaml-vs-db-storage.md` 를 대체한다 (supersedes).** 그 ADR 의 「YAML = source of truth」 결정을 뒤집는다.

## 맥락

`2026-05-21-workflow-yaml-vs-db-storage.md` 는 표준 4종 워크플로우의 정본을 YAML 파일에 두고
DB 는 런타임 캐시로 쓰기로 결정했다. 근거는 세 가지였다 — 표준 워크플로우의 안정성, 1인 운영에서
git log 로 변경을 추적하는 이점, 그리고 커스텀 워크플로우 CRUD 는 FR-WF-02 로 미룬다는 전제.

**그 전제 중 마지막이 실현되지 않았다.** FR-WF-02 는 실제로는 「워크플로우 스킴」 — 이미 존재하는
워크플로우를 프로젝트와 이슈 타입에 배정하는 매핑 — 으로 구현됐다. 워크플로우 자체의 상태·전이·
검증기를 만들거나 고치는 API 는 끝내 만들어지지 않았다. 결과적으로 **워크플로우 정의를 사람이
고치는 유일한 방법은 YAML 파일을 편집하고 재배포하는 것**이다.

### 구 ADR 은 구현과 이미 어긋나 있었다

이 ADR 을 쓰면서 구 ADR 의 서술을 실제 스키마와 대조했다. **일치하지 않는다.**

| 구 ADR 이 적은 것 | 실제 (V200) |
|---|---|
| `workflow_definitions` (YAML SHA-256 해시 컬럼 포함) | `workflows` — `yaml_hash` 컬럼 **없음** |
| `workflow_statuses` | `workflow_states` |
| `workflow_role_constraints` | 없음 — `workflow_validators` 가 그 역할 |
| `project_workflow_assignments` | 없음 — V201 의 `project_workflow_scheme_assignments` |
| 시드 멱등성 = YAML 바이트의 SHA-256 해시 비교 | `YamlSeedService.isDirty()` 의 필드 단위 비교 |

즉 구 ADR 이 규정한 5테이블 중 이름이 맞는 것은 하나도 없고, 멱등성 메커니즘도 다르다.
**문서가 몇 달째 구현하지 않은 설계를 서술하고 있었다.** 이 ADR 은 그 드리프트도 함께 닫는다.

### 지금 구조가 편집을 구조적으로 막는다

`YamlSeedService` 는 `ApplicationReadyEvent` 마다 돈다. YAML 과 DB 를 `isDirty()` 로 비교해
다르면 `deleteWorkflow`(CASCADE) 후 통재삽입한다. 그래서 **사람이 DB 를 고쳐도 재기동하면
YAML 기준으로 되돌아간다.** 편집 API 를 얹기 전에 이 되돌림을 먼저 없애지 않으면, 편집 기능은
「저장은 되는데 다음 배포에 사라지는」 기능이 된다.

## 결정

### D1. DB 가 워크플로우 정의의 정본이다

`workflows` · `statuses` · `workflow_statuses` · `workflow_transitions` · `workflow_validators` ·
`workflow_post_actions` 가 정본이다. 런타임과 관리 화면이 모두 이것을 읽고 쓴다.

### D2. YAML 은 빈 DB 를 채우는 최초 1회 부트스트랩 전용이다

`YamlSeedService` 는 **해당 key 의 workflow 행이 없을 때만** 삽입한다. `isDirty()` 비교와
`deleteWorkflow` → 재삽입 경로를 제거한다. 이미 행이 있으면 아무것도 하지 않는다.

YAML 파일 자체는 **삭제하지 않는다.** 새 환경의 부트스트랩 소스이자 아래 D4 의 복원 소스다.

### D3. 표준 4종도 편집을 허용한다

`software-default` · `bug-tracking` · `simple` · `kanban-basic` 을 잠그지 않는다. Jira Cloud 가
기본 워크플로우를 편집 가능하게 두는 것과 같다. 대신 `workflows.origin` 컬럼에 `SEED` 를 표기해
「이 워크플로우는 기본값이 존재한다」를 식별한다 (사용자 생성분은 `CUSTOM`).

### D4. 「기본값으로 복원」으로 되돌림을 대체한다

자동 되돌림을 없앤 자리를 **명시적 사용자 행동**으로 채운다. `origin='SEED'` 인 워크플로우는
YAML 원본을 초안으로 다시 불러오는 복원 기능을 제공한다. 되돌리는 주체가 부팅 이벤트에서
사람으로 바뀌는 것이 이 결정의 핵심이다.

## 근거

1. **요구가 바뀌었다.** 구 ADR 의 「표준 워크플로우는 자주 바뀌지 않는다」는 표준을 못 고치게
   하려는 것이 아니라 **재배포 비용이 감당된다**는 판단이었다. 사내 1,000명이 자기 팀 상태
   이름을 「진행 중」으로 바꾸려고 배포 파이프라인을 기다리는 것은 그 판단의 범위 밖이다.
2. **git 추적의 이점은 발행 이력으로 대체된다.** `workflow_publications` 를 append-only 로 두면
   누가 언제 무엇을 발행했는지가 남는다. PR 리뷰만큼 강하진 않지만, 편집 자체가 불가능한 것보다
   추적 가능한 편집이 낫다.
3. **안정성은 초안/발행과 이관 마법사가 진다.** 구 ADR 이 YAML 로 막으려던 「의도치 않은 변경」은
   FR-WF-07 의 초안·발행 게이트와 상태 이관 마법사가 더 정확하게 막는다 — 발행 전에는 런타임에
   새어 나가지 않고, 이슈가 남은 상태를 지우려 하면 어디로 옮길지 먼저 묻는다.
4. **문서 드리프트를 방치할 수 없다.** 구 ADR 은 존재하지 않는 테이블 4개와 존재하지 않는 컬럼을
   규정하고 있었다. 살려 두면 다음 사람이 그 이름으로 코드를 찾는다.

## 기각한 대안

**표준은 잠그고 복제해서 편집** — 표준 4종을 읽기 전용으로 두고 사용자는 「복제」로 새 워크플로우를
만들어 고치게 하는 안. 시드 로직 변경이 가장 작고 기준이 항상 안전하게 남는다는 장점이 있다.

기각 사유는 두 가지다. ① 「진행 중」처럼 **표준의 이름만 우리 말로 바꾸고 싶은** 흔한 요구가
복제본을 강제한다 — 그러면 표준 4종은 아무도 안 쓰는 장식이 되고 스킴 매핑을 전부 갈아야 한다.
② 잠금을 유지하려면 `YamlSeedService` 의 되돌림도 유지해야 하는데, 그러면 「표준은 되돌리고
커스텀은 안 되돌린다」는 분기가 생겨 시드가 더 복잡해진다. D2 의 「없을 때만 삽입」이 훨씬 단순하다.

## 영향

### 긍정

- 워크플로우 편집이 재배포 없이 가능해진다 — FR-WF-04~07 전체의 선행 조건이 풀린다.
- 시드 로직이 단순해진다. `isDirty()` 필드 비교와 delete→reinsert, 그리고 그 과정에서 스킴 매핑을
  detach 했다가 reinsert 하는 우회 로직이 통째로 사라진다.
- 구 ADR 의 테이블명 드리프트가 닫힌다.

### 부정 / 위험

- **표준 워크플로우 변경이 PR 리뷰를 거치지 않는다.** 완화책은 발행 이력(`workflow_publications`)과
  기본값 복원. 그래도 git log 만큼의 추적성은 아니다 — 의도적으로 받아들이는 비용이다.
- **YAML 과 DB 가 갈라진다.** 부트스트랩 이후 둘은 독립적으로 움직인다. YAML 은 「신규 환경의
  초기값 + 복원 기준」이라는 좁은 의미만 갖는다. 이 의미 축소를
  `backend/modules/project-workflow/src/main/resources/workflows/README.md` 에 명시한다.
- **배포된 DB 의 안전성.** 되돌림을 없애는 변경 자체는 파괴적이지 않다(INSERT 를 안 할 뿐). 다만
  `V202` 주석이 경고한 대로 실데이터가 있는 환경에서 destructive 패턴을 재사용하지 않는다 —
  백필은 INSERT 만 하고 `workflow_states` DROP 은 마지막 단계로 미룬다.

## 대안 채택 조건

- 표준 워크플로우가 조직 규정상 변경 금지 자산이 되면 → `workflows.is_locked` 를 켜는 운영 정책으로
  전환한다. 컬럼은 이미 둔다(D3 의 `origin` 과 별개). 스키마 변경 없이 정책만 바꾸면 된다.
- 발행 이력만으로 감사 요구를 못 채우면 → 발행 시 YAML 을 재생성해 git 에 커밋하는 역방향 동기화를
  별도 ADR 로 검토한다. 이번 범위에서는 하지 않는다.

## 관련

- `docs/adr/2026-05-21-workflow-yaml-vs-db-storage.md` — **이 ADR 이 대체한다**
- `docs/adr/2026-08-18-workflow-global-status-catalog.md` — 상태 모델 재구성
- `docs/adr/2026-08-18-workflow-transition-id-identity.md` — 전환 identity 재정의
- `DATA.md §4` — 마이그레이션 3단 분할 규칙
- `docs/sdd/07-workflow-engine.md` — 워크플로우 엔진 설계
