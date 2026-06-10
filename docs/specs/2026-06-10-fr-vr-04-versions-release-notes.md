# FR-VR-04 — 버전 릴리즈 노트 자동 생성 — 스펙

> slug: fr-vr-04-versions-release-notes · BC: issue-tracking · 우선순위: 중간
> 선행: FR-VR-01(버전 생성) · FR-VR-03(Affects/Fix Version 연결) — 완료
> 작성: 2026-06-10. office-hours 대체 — 완성된 BC의 후속 FR이라 직접 기술 스펙 작성.

## 1. 개요

특정 버전을 **Fix Version**으로 연결한 활성 이슈들을 모아, 타입별로 그룹핑한 **Markdown 릴리즈 노트**를 요청 시 자동 생성한다. 영속화하지 않는다(조회 전용).

## 2. 사용자 시나리오 (Given-When-Then)

- **S1 (해피패스)**. Given 프로젝트 `ATLAS`에 버전 `1.2.0`이 있고 `ATLAS-1`(Bug)·`ATLAS-3`(Story)가 fix version으로 연결돼 있을 때, When 사용자가 릴리즈 노트를 요청하면, Then 타입별로 그룹핑된 Markdown 문서와 메타데이터(이슈 수 2)를 받는다.
- **S2 (빈 노트)**. Given 버전에 연결된 fix version 이슈가 0건일 때, When 요청하면, Then `issueCount=0` + "포함된 이슈가 없습니다." 안내가 담긴 Markdown을 받는다(200).
- **S3 (resolution 표시)**. Given 연결 이슈 중 일부에 resolution(예: Fixed)이 설정돼 있을 때, When 요청하면, Then 각 항목 끝에 `(Fixed)`처럼 resolution 표시명이 붙는다. resolution 미설정 이슈는 표시 없이 나열된다.
- **S4 (버전 미존재)**. Given 존재하지 않는 versionId, When 요청, Then 404 `VERSION_NOT_FOUND`.
- **S5 (프로젝트 미존재)**. Given 존재하지 않는 projectIdOrKey, When 요청, Then 404 `VERSION_PROJECT_NOT_FOUND`.
- **S6 (미인증)**. Given 인증 없는 요청, When 요청, Then 401 (SecurityFilterChain 보장).
- **S7 (ARCHIVED 버전)**. Given ARCHIVED 상태 버전, When 요청, Then 정상 생성(200). 릴리즈 노트는 읽기 동작이므로 상태 무관 허용. 단 소프트 삭제된(deletedAt) 버전은 404.

## 3. 기능 요구사항 (FR)

- **FR1**. `GET /api/v1/projects/{projectIdOrKey}/versions/{id}/release-notes` 엔드포인트를 제공한다. VersionController의 `@RequestMapping` 하위에 정렬한다.
- **FR2**. 응답은 기존 `DataResponse<T>` 래핑 — `{ data: ReleaseNotesResponse }`.
- **FR3**. 포함 이슈 = 해당 버전을 fix version으로 가진 **활성(soft-delete 안 된) 이슈 전부**. 워크플로우 상태/resolution 유무로 필터링하지 않는다(전부 포함, cross-BC 회피).
- **FR4**. 이슈를 **타입별로 그룹핑**한다. 그룹 순서는 IssueType 표준 순서(`hierarchy_level` 오름차순, 동률은 typeName), 그룹 내 이슈는 **이슈 키 순**.
- **FR5**. Markdown 템플릿(§5)에 따라 본문을 생성한다.
- **FR6**. 권한은 기존 버전 조회와 동일한 READ 게이트(`VersionPermissionResolver`)를 적용한다. actorId는 `SYSTEM_ACTOR_UUID` placeholder(FR-PM-03 이연).
- **FR7 (D6 프론트)**. 버전 관리 화면(`/projects/$projectKey/settings/versions`)의 각 버전 행에 "릴리즈 노트" 액션을 추가, 클릭 시 미리보기 다이얼로그에 Markdown 렌더 + **클립보드 복사** 버튼을 제공한다.

## 4. API 인터페이스 (REST)

```
GET /api/v1/projects/{projectIdOrKey}/versions/{id}/release-notes
→ 200 OK
{
  "data": {
    "versionId": "uuid",
    "projectKey": "ATLAS",
    "versionName": "1.2.0",
    "versionStatus": "RELEASED",
    "releaseDate": "2026-06-10",        // versions.release_date, null 가능
    "issueCount": 7,
    "generatedAt": "2026-06-10T12:00:00Z",
    "markdown": "# ATLAS 1.2.0 릴리즈 노트\n\n..."
  }
}
→ 404 VERSION_NOT_FOUND / VERSION_PROJECT_NOT_FOUND
→ 401 (미인증)
```

- `generatedAt`은 서버 시각. Clock은 서비스 레이어 주입(도메인은 파라미터 수신) — 기존 FR-VR-02 Clock 패턴 준수.
- 새 에러코드 없음(기존 `VersionErrorCodes` 재사용).

## 5. Markdown 템플릿 (D2)

```markdown
# {projectKey} {versionName} 릴리즈 노트

- 상태: {versionStatus}
- 릴리즈일: {releaseDate 또는 "미지정"}
- 포함 이슈: {issueCount}건

## {typeName} ({타입별 건수})
- {issueKey} {summary}{ resolution 있으면 " (" + resolution.name + ")" }
- ...

## {다음 타입} (...)
- ...
```

- 이슈 0건이면 그룹 섹션 없이 `포함된 이슈가 없습니다.` 한 줄.
- **이스케이프**: `summary`에 포함된 줄바꿈(`\r\n`/`\n`)은 공백으로 치환해 한 줄 항목 깨짐을 방지한다. 그 외 Markdown 특수문자는 원문 보존(릴리즈 노트는 사람이 읽는 문서, 의도적 변형 금지).

## 6. 데이터 모델 변경

- **없음**(D3 = 활용). `versions` · `issues` · `issue_fix_versions` · `issue_types` 기존 테이블 읽기.
- **신규 repository 메서드**: `IssueRepository`에 "버전 ID → 그 버전을 fix version으로 가진 활성 이슈(타입 정보 포함) 목록" 역방향 조회 추가. 기존 `findFixVersionIdsByIssue`(issue→version)의 역방향. cartesian product 회피(메모리: 다중 LEFT JOIN+count 위험) — 단일 조인 후 애플리케이션에서 그룹핑.

## 7. 비기능 요구사항 (NFR)

- **NFR1 (성능)**. 한 버전의 fix version 이슈는 통상 수십~수백 건. 단일 쿼리(issue_fix_versions JOIN issues JOIN issue_types, deleted_at IS NULL)로 N+1 회피.
- **NFR2 (격리)**. issue-tracking BC 내부 완결. 워크플로우 상태(project-workflow) 조회 안 함.
- **NFR3 (일관성)**. 응답/에러/권한/Clock 패턴은 기존 Version 엔드포인트와 동일.

## 8. 엣지 케이스

- 연결 이슈 0건 → 200, 빈 안내 Markdown.
- soft-delete된 이슈는 제외(deleted_at IS NULL).
- soft-delete된 버전 → 404. ARCHIVED 버전 → 정상(읽기 허용).
- summary 내 줄바꿈 → 공백 치환.
- 동일 타입 다수 → 한 그룹에 모음.
- releaseDate null → "미지정".

## 9. 측정 가능한 완료 기준

- [ ] `GET .../release-notes` 200 + ReleaseNotesResponse(메타 + markdown) 반환(통합테스트 S1).
- [ ] 타입별 그룹핑 + 키 순 정렬 검증(단위/통합).
- [ ] 빈 노트(0건) 200 + 안내 문구(S2).
- [ ] resolution 표시(S3).
- [ ] 404 버전/프로젝트 미존재(S4/S5), 401 미인증(S6).
- [ ] ARCHIVED 200 / soft-delete 404(S7).
- [ ] 프론트 미리보기 다이얼로그 + 클립보드 복사 동작(D6) + E2E happy path(D7).

## Brainstorming Check

✅ 통과 (1회 자가 점검). gap 1건 발견 후 보강 — 헤더/응답에 `projectKey` 추가(동명 버전·0건 이슈 시 프로젝트 식별 가능). 구현 주의점으로 식별: (a) resolution은 다건 경로라 ApplicationService가 별도 주입 필요(IssueResponse 단건 패턴 line 246 참조), (b) IssueType 표준 순서는 IssueTypeRepository.findAll() 활용, (c) projectKey는 ProjectLookup 조회, (d) 클립보드 복사는 secure-context/E2E 권한 처리. 모두 plan/impl에서 배선.
