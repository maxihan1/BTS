// 이슈 이동 마법사의 노드별 매핑 입력 섹션 컴포넌트
/* eslint-disable react-refresh/only-export-components -- 헬퍼/타입을 컴포넌트와 같은 파일에 배치(응집도 우선) */
import type { JSX } from 'react'
import React from 'react'
import { Input } from '@/components/ui/input'
import type { MovePreview, WorkflowStateView } from '@/api/issue-move'
import type { Component } from '@/api/components.types'
import type { Version } from '@/api/versions.types'
import type { CustomField } from '@/api/custom-fields.types'
import { issueMoveStrings as s } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 타입 — 노드별 매핑 상태
// ─────────────────────────────────────────────────────────────────────────────

/** 단일 노드(루트 또는 자식)의 사용자 매핑 선택 상태 */
export interface NodeMappingState {
  /** 사용자가 선택한 대상 상태 키. null이면 미선택(비호환 시 invalid) */
  selectedStateKey: string | null
  /** 컴포넌트 매핑. 원본 id → 대상 id | null(제거) */
  componentMapping: Record<string, string | null>
  /** affectsVersions 매핑 */
  affectsVersionMapping: Record<string, string | null>
  /** fixVersions 매핑 */
  fixVersionMapping: Record<string, string | null>
  /** 필수 커스텀필드 값 입력 */
  customFieldValues: Record<string, string>
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 초기 NodeMappingState 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * preview 응답의 각 섹션에서 초기 매핑 상태를 생성한다.
 * autoMapping 값을 기본값으로 설정하고, compatible이면 suggestedStateKey를 초기 선택한다.
 */
export function buildInitialNodeState(node: {
  workflow: MovePreview['workflow']
  components: MovePreview['components']
  affectsVersions: MovePreview['affectsVersions']
  fixVersions: MovePreview['fixVersions']
  customFields: MovePreview['customFields']
}): NodeMappingState {
  // 워크플로우 compatible이면 suggestedStateKey가 현재 상태 키 → 선택 가능
  const selectedStateKey = node.workflow.suggestedStateKey

  // autoMapping을 Record<string, string | null>로 변환
  const componentMapping: Record<string, string | null> = {}
  for (const [srcId, tgtId] of Object.entries(node.components.autoMapping)) {
    componentMapping[srcId] = tgtId ?? null
  }

  const affectsVersionMapping: Record<string, string | null> = {}
  for (const [srcId, tgtId] of Object.entries(node.affectsVersions.autoMapping)) {
    affectsVersionMapping[srcId] = tgtId ?? null
  }

  const fixVersionMapping: Record<string, string | null> = {}
  for (const [srcId, tgtId] of Object.entries(node.fixVersions.autoMapping)) {
    fixVersionMapping[srcId] = tgtId ?? null
  }

  return {
    selectedStateKey,
    componentMapping,
    affectsVersionMapping,
    fixVersionMapping,
    customFieldValues: {},
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 노드 유효성 검사
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 노드 매핑이 완전한지 검사한다.
 * - 비호환 상태(compatible=false)이면 selectedStateKey가 있어야 한다.
 * - 필수 커스텀필드(requiredMissing)가 있으면 모두 입력되어야 한다.
 */
export function isNodeMappingValid(
  nodeState: NodeMappingState,
  compatible: boolean,
  requiredMissing: CustomField[],
): boolean {
  if (!compatible && nodeState.selectedStateKey === null) return false
  for (const field of requiredMissing) {
    const val = nodeState.customFieldValues[field.key]
    if (val === undefined || val.trim() === '') return false
  }
  return true
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — NodeMappingSection
// ─────────────────────────────────────────────────────────────────────────────

interface NodeMappingSectionProps {
  /** 섹션 식별자 — 루트이면 "root", 자식이면 issueKey */
  readonly nodeId: string
  /** 노드 레이블 (루트 이슈 또는 이슈 키) */
  readonly label: string
  /** 대상 상태 목록 */
  readonly targetStates: WorkflowStateView[]
  /** 워크플로우 호환 여부 */
  readonly compatible: boolean
  /** 현재 컴포넌트 목록 */
  readonly currentComponents: Component[]
  /** 대상 컴포넌트 목록 */
  readonly targetComponents: Component[]
  /** 현재 affectsVersions 목록 */
  readonly currentAffectsVersions: Version[]
  /** 대상 affectsVersions 목록 */
  readonly targetAffectsVersions: Version[]
  /** 현재 fixVersions 목록 */
  readonly currentFixVersions: Version[]
  /** 대상 fixVersions 목록 */
  readonly targetFixVersions: Version[]
  /** 제거될 커스텀 필드 목록 */
  readonly removedFields: CustomField[]
  /** 필수이지만 값 없는 커스텀 필드 목록 */
  readonly requiredMissingFields: CustomField[]
  /** 현재 매핑 상태 */
  readonly mappingState: NodeMappingState
  /** 매핑 상태 변경 핸들러 */
  readonly onChange: (next: NodeMappingState) => void
}

/**
 * 단일 이슈 노드(루트 또는 자식)의 매핑 섹션.
 * 워크플로우 상태 select, 컴포넌트/버전 매핑 select, 필수 커스텀필드 입력을 렌더한다.
 */
export function NodeMappingSection({
  nodeId,
  label,
  targetStates,
  compatible,
  currentComponents,
  targetComponents,
  currentAffectsVersions,
  targetAffectsVersions,
  currentFixVersions,
  targetFixVersions,
  removedFields,
  requiredMissingFields,
  mappingState,
  onChange,
}: NodeMappingSectionProps): JSX.Element {
  function handleStateChange(e: React.ChangeEvent<HTMLSelectElement>) {
    onChange({ ...mappingState, selectedStateKey: e.target.value || null })
  }

  function handleComponentMappingChange(srcId: string, tgtId: string | null) {
    onChange({
      ...mappingState,
      componentMapping: { ...mappingState.componentMapping, [srcId]: tgtId },
    })
  }

  function handleAffectsVersionMappingChange(srcId: string, tgtId: string | null) {
    onChange({
      ...mappingState,
      affectsVersionMapping: { ...mappingState.affectsVersionMapping, [srcId]: tgtId },
    })
  }

  function handleFixVersionMappingChange(srcId: string, tgtId: string | null) {
    onChange({
      ...mappingState,
      fixVersionMapping: { ...mappingState.fixVersionMapping, [srcId]: tgtId },
    })
  }

  function handleCustomFieldChange(key: string, value: string) {
    onChange({
      ...mappingState,
      customFieldValues: { ...mappingState.customFieldValues, [key]: value },
    })
  }

  return (
    <div
      className="border rounded-lg p-4 space-y-4"
      data-testid={`node-section-${nodeId}`}
    >
      {/* 노드 레이블 */}
      <h3 className="font-medium text-sm">{label}</h3>

      {/* 워크플로우 상태 선택 */}
      {!compatible && (
        <div>
          <p className="text-xs text-warning-text mb-2">{s.workflowIncompatible}</p>
          <label
            htmlFor={`state-select-${nodeId}`}
            className="text-sm font-medium block mb-1"
          >
            {s.targetStateLabel}
          </label>
          <select
            id={`state-select-${nodeId}`}
            aria-label={s.targetStateLabel}
            role="combobox"
            value={mappingState.selectedStateKey ?? ''}
            onChange={handleStateChange}
            className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm"
          >
            <option value="">-- 선택 --</option>
            {targetStates.map((st) => (
              <option key={st.key} value={st.key}>
                {st.name}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* 호환일 때도 상태 변경 가능 */}
      {compatible && targetStates.length > 0 && (
        <div>
          <label
            htmlFor={`state-select-${nodeId}`}
            className="text-sm font-medium block mb-1"
          >
            {s.targetStateLabel}
          </label>
          <select
            id={`state-select-${nodeId}`}
            aria-label={s.targetStateLabel}
            role="combobox"
            value={mappingState.selectedStateKey ?? ''}
            onChange={handleStateChange}
            className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm"
          >
            {targetStates.map((st) => (
              <option key={st.key} value={st.key}>
                {st.name}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* 컴포넌트 매핑 */}
      {currentComponents.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-2">{s.componentMappingTitle}</p>
          {currentComponents.map((comp) => (
            <div key={comp.id} className="flex items-center gap-2 mb-1">
              <span className="text-sm text-muted-foreground">{comp.name}</span>
              <span className="text-xs">→</span>
              <select
                aria-label={`${comp.name} 컴포넌트 매핑`}
                value={mappingState.componentMapping[comp.id] ?? ''}
                onChange={(e) =>
                  handleComponentMappingChange(comp.id, e.target.value || null)
                }
                className="flex-1 rounded-md border border-input bg-transparent px-2 py-1 text-sm"
              >
                <option value="">{s.mappingRemoveOption}</option>
                {targetComponents.map((tc) => (
                  <option key={tc.id} value={tc.id}>
                    {tc.name}
                  </option>
                ))}
              </select>
            </div>
          ))}
        </div>
      )}

      {/* affectsVersions 매핑 */}
      {currentAffectsVersions.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-2">{s.affectsVersionMappingTitle}</p>
          {currentAffectsVersions.map((ver) => (
            <div key={ver.id} className="flex items-center gap-2 mb-1">
              <span className="text-sm text-muted-foreground">{ver.name}</span>
              <span className="text-xs">→</span>
              <select
                aria-label={`${ver.name} 영향 버전 매핑`}
                value={mappingState.affectsVersionMapping[ver.id] ?? ''}
                onChange={(e) =>
                  handleAffectsVersionMappingChange(ver.id, e.target.value || null)
                }
                className="flex-1 rounded-md border border-input bg-transparent px-2 py-1 text-sm"
              >
                <option value="">{s.mappingRemoveOption}</option>
                {targetAffectsVersions.map((tv) => (
                  <option key={tv.id} value={tv.id}>
                    {tv.name}
                  </option>
                ))}
              </select>
            </div>
          ))}
        </div>
      )}

      {/* fixVersions 매핑 */}
      {currentFixVersions.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-2">{s.fixVersionMappingTitle}</p>
          {currentFixVersions.map((ver) => (
            <div key={ver.id} className="flex items-center gap-2 mb-1">
              <span className="text-sm text-muted-foreground">{ver.name}</span>
              <span className="text-xs">→</span>
              <select
                aria-label={`${ver.name} 수정 버전 매핑`}
                value={mappingState.fixVersionMapping[ver.id] ?? ''}
                onChange={(e) =>
                  handleFixVersionMappingChange(ver.id, e.target.value || null)
                }
                className="flex-1 rounded-md border border-input bg-transparent px-2 py-1 text-sm"
              >
                <option value="">{s.mappingRemoveOption}</option>
                {targetFixVersions.map((tv) => (
                  <option key={tv.id} value={tv.id}>
                    {tv.name}
                  </option>
                ))}
              </select>
            </div>
          ))}
        </div>
      )}

      {/* 제거될 커스텀 필드 안내 */}
      {removedFields.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-1 text-destructive">{s.removedFieldsLabel}</p>
          <ul className="text-xs text-muted-foreground list-disc list-inside">
            {removedFields.map((f) => (
              <li key={f.key}>{f.name}</li>
            ))}
          </ul>
        </div>
      )}

      {/* 필수 커스텀 필드 입력 */}
      {requiredMissingFields.length > 0 && (
        <div>
          <p className="text-xs font-medium mb-2">{s.requiredMissingLabel}</p>
          {requiredMissingFields.map((f) => (
            <div key={f.key} className="mb-2">
              <label
                htmlFor={`cf-${nodeId}-${f.key}`}
                className="text-sm font-medium block mb-1"
              >
                {f.name}
              </label>
              <Input
                id={`cf-${nodeId}-${f.key}`}
                aria-label={f.name}
                value={mappingState.customFieldValues[f.key] ?? ''}
                onChange={(e) => handleCustomFieldChange(f.key, e.target.value)}
                placeholder={f.name}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
