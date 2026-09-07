// 가젯 카탈로그 MSW 픽스처 — 백엔드 GadgetType.catalog() 1:1 미러 12종
//
// 계약 drift 방지.
//   정본 파일: backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt
//   DTO 파일:  backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/dto/GadgetCatalogDtos.kt
//   @JsonInclude(NON_NULL) 정책 — null 필드는 응답 JSON에서 생략됨 → 픽스처에서도 생략.
//
// 보강 사항 (Task 3 — same-BC 백엔드 보강 병행).
//   ASSIGNED_TO_ME: projectKey 필드 추가 (recently_created 동일 패턴, Maxi 확정 2026-06-30).

import type { GadgetCatalogEntry } from '@/api/gadget-catalog'

/**
 * 가젯 카탈로그 전체 픽스처 (12종).
 *
 * 순서는 백엔드 DashboardController.kt의 compareBy({it.category.ordinal},{it.type}) 정렬과 동일:
 *   category ordinal: ISSUE(0) → STATIC(1) → CHART(2) → ACTIVITY(3)
 *   카테고리 내 type: 알파벳 오름차순
 * enabled=true 6종 (ISSUE 4 + STATIC 2) / enabled=false 6종 (CHART 4 + ACTIVITY 2).
 *
 * ⚠️ 계약 drift 경고.
 *   백엔드 GadgetType.kt 변경 시 이 파일도 반드시 동기화할 것.
 *   MSW가 가짜 데이터를 반환해도 실 API와 불일치하면 prod에서 Zod parse 실패로 깨진다.
 */
export const GADGET_CATALOG_FIXTURE: GadgetCatalogEntry[] = [
  // ── ISSUE 카테고리 (enabled=true) — type 알파벳 순 ──────────────────────────
  {
    type: 'assigned_to_me',
    category: 'ISSUE',
    label: 'Assigned To Me',
    enabled: true,
    configFields: [
      // projectKey: Task 3 백엔드 보강 결과물 (recently_created 동일 패턴)
      { key: 'projectKey', type: 'STRING', required: false, maxLength: 100 },
      { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
    ],
    requireAtLeastOne: [],
  },
  {
    type: 'filter_result',
    category: 'ISSUE',
    label: 'Filter Result',
    enabled: true,
    configFields: [
      { key: 'filterId', type: 'UUID', required: false },
      { key: 'aql', type: 'STRING', required: false, maxLength: 2000 },
      { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
    ],
    requireAtLeastOne: [['filterId', 'aql']],
  },
  {
    type: 'issue_count',
    category: 'ISSUE',
    label: 'Issue Count',
    enabled: true,
    configFields: [
      { key: 'filterId', type: 'UUID', required: false },
      { key: 'aql', type: 'STRING', required: false, maxLength: 2000 },
    ],
    requireAtLeastOne: [['filterId', 'aql']],
  },
  {
    type: 'recently_created',
    category: 'ISSUE',
    label: 'Recently Created',
    enabled: true,
    configFields: [
      { key: 'projectKey', type: 'STRING', required: false, maxLength: 100 },
      { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
    ],
    requireAtLeastOne: [],
  },

  // ── STATIC 카테고리 (enabled=true) — type 알파벳 순 ────────────────────────
  {
    type: 'link_list',
    category: 'STATIC',
    label: 'Link List',
    enabled: true,
    configFields: [
      {
        key: 'links',
        type: 'ARRAY',
        required: true,
        minItems: 1,
        maxItems: 20,
        itemSchema: [
          { key: 'label', type: 'STRING', required: true, maxLength: 100 },
          { key: 'url', type: 'URL', required: true, maxLength: 2000 },
        ],
      },
    ],
    requireAtLeastOne: [],
  },
  {
    type: 'text_widget',
    category: 'STATIC',
    label: 'Text Widget',
    enabled: true,
    configFields: [
      { key: 'markdown', type: 'STRING', required: true, minLength: 1, maxLength: 10000 },
    ],
    requireAtLeastOne: [],
  },

  // ── CHART 카테고리 — type 알파벳 순. created_vs_resolved 만 아직 false 다 ────
  {
    type: 'bar_chart',
    category: 'CHART',
    label: 'Bar Chart',
    enabled: true,
    configFields: [
      { key: 'projectKey', type: 'STRING', required: true, maxLength: 100 },
      {
        key: 'field',
        type: 'ENUM',
        required: true,
        enumValues: ['status', 'assignee', 'priority', 'issueType'],
      },
    ],
    requireAtLeastOne: [],
  },
  {
    type: 'created_vs_resolved',
    category: 'CHART',
    label: 'Created Vs Resolved',
    enabled: false,
    configFields: [
      { key: 'projectKey', type: 'STRING', required: false, maxLength: 100 },
      { key: 'days', type: 'INT', required: false, min: 7, max: 90 },
    ],
    requireAtLeastOne: [],
  },
  {
    type: 'pie_chart',
    category: 'CHART',
    label: 'Pie Chart',
    enabled: true,
    configFields: [
      { key: 'projectKey', type: 'STRING', required: true, maxLength: 100 },
      {
        key: 'field',
        type: 'ENUM',
        required: true,
        enumValues: ['status', 'assignee', 'priority', 'issueType'],
      },
    ],
    requireAtLeastOne: [],
  },
  {
    type: 'sprint_burndown',
    category: 'CHART',
    label: 'Sprint Burndown',
    enabled: true,
    configFields: [{ key: 'boardId', type: 'UUID', required: true }],
    requireAtLeastOne: [],
  },

  // ── ACTIVITY 카테고리 — type 알파벳 순. comments_recent 만 아직 false 다 ─────
  {
    type: 'activity_stream',
    category: 'ACTIVITY',
    label: 'Activity Stream',
    enabled: true,
    configFields: [
      { key: 'projectKey', type: 'STRING', required: false, maxLength: 100 },
      { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
    ],
    requireAtLeastOne: [],
  },
  {
    type: 'comments_recent',
    category: 'ACTIVITY',
    label: 'Comments Recent',
    enabled: false,
    configFields: [
      { key: 'projectKey', type: 'STRING', required: false, maxLength: 100 },
      { key: 'maxItems', type: 'INT', required: false, min: 1, max: 50 },
    ],
    requireAtLeastOne: [],
  },
]
