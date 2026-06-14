// 이슈 링크 그래프 시각화 컴포넌트 — mermaid flowchart + 5상태 + depth + 노드 클릭 내비게이션 (FR-LK-02 D6)
import type { JSX, ChangeEvent } from 'react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useIssueGraph, extractGraphErrorCode, ISSUE_GRAPH_ERROR_CODES } from '@/api/issue-graph'
import type { IssueGraphResponse } from '@/api/issue-graph'
import { generateGraphMermaidCode } from '@/components/issue/link-graph-mermaid'
import { linkGraphStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 엣지 유형(대문자) → 한국어 표시 라벨 맵.
 * linkGraphStrings에서 도출해 generateGraphMermaidCode에 주입한다.
 */
const EDGE_LABELS: Record<string, string> = {
  BLOCKS: linkGraphStrings.edgeBlocks,
  RELATES: linkGraphStrings.edgeRelates,
  DUPLICATES: linkGraphStrings.edgeDuplicates,
  CLONES: linkGraphStrings.edgeClones,
  PARENT: linkGraphStrings.edgeParent,
}

/** 지원 depth 옵션 */
const DEPTH_OPTIONS = [
  { value: 1, label: linkGraphStrings.depthOption1 },
  { value: 2, label: linkGraphStrings.depthOption2 },
  { value: 3, label: linkGraphStrings.depthOption3 },
] as const

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — 상태 표시 영역
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 로딩 상태 표시 컴포넌트.
 */
function LoadingState(): JSX.Element {
  return (
    <p className="text-sm text-muted-foreground py-4 text-center">
      {linkGraphStrings.loadingState}
    </p>
  )
}

/**
 * 에러 상태 표시 컴포넌트 (role=alert).
 *
 * @param message 표시할 에러 메시지
 */
function ErrorState({ message }: { message: string }): JSX.Element {
  return (
    <p role="alert" className="text-sm text-destructive py-4 text-center">
      {message}
    </p>
  )
}

/**
 * 빈 그래프 상태 표시 컴포넌트.
 */
function EmptyState(): JSX.Element {
  return (
    <p className="text-sm text-muted-foreground py-4 text-center">
      {linkGraphStrings.emptyState}
    </p>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — GraphRenderer (mermaid SVG 렌더 + 노드 클릭 바인딩)
// ─────────────────────────────────────────────────────────────────────────────

interface GraphRendererProps {
  /** mermaid flowchart LR 코드 */
  code: string
  /** sanitizedId → 이슈 키 역매핑 (클릭 내비게이션용) */
  idToKey: Record<string, string>
  /** 중심 이슈 키 (클릭 no-op 대상) */
  centerKey: string
  /** 렌더 실패 시 호출 콜백 */
  onRenderError: () => void
  /** 렌더 성공 시 호출 콜백 (renderError 상태 리셋) */
  onRenderSuccess: () => void
}

/**
 * mermaid SVG를 렌더링하고 노드에 클릭/키보드 핸들러를 바인딩하는 서브컴포넌트.
 *
 * CONCERN C1 (노드 클릭 내비게이션).
 * - mermaid flowchart는 노드를 `<g class="node">` 로 렌더하고,
 *   원래 노드 id를 `data-id` 속성 또는 `id="flowchart-<nodeId>-<n>"` 에 담는다.
 * - `data-id` 를 우선 사용, 없으면 id 속성에서 sanitizedId(node_N) 패턴 추출.
 * - idToKey로 sanitizedId → 이슈 키 복원 후 navigate.
 *
 * CONCERN C2 (키보드 a11y).
 * - 비 center 노드 <g>에 tabindex=0, role=link, aria-label 부여.
 * - Enter keydown → navigate 호출.
 */
function GraphRenderer({
  code,
  idToKey,
  centerKey,
  onRenderError,
  onRenderSuccess,
}: GraphRendererProps): JSX.Element {
  const containerRef = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()

  useEffect(() => {
    if (!containerRef.current) {
      return
    }

    let cancelled = false

    async function renderDiagram(): Promise<void> {
      try {
        // 동적 import — Vite chunk 분리로 초기 번들 크기 절약
        const mermaid = (await import('mermaid')).default

        mermaid.initialize({
          startOnLoad: false,
          theme: 'base',
          // securityLevel 기본값(strict) 유지 — 보안 표면 차단 (CONCERN C1)
        })

        if (cancelled || !containerRef.current) {
          return
        }

        const id = `link-graph-${Date.now()}`
        const { svg } = await mermaid.render(id, code)

        if (cancelled || !containerRef.current) {
          return
        }

        // SVG 주입
        containerRef.current.innerHTML = svg

        // 노드 클릭/a11y 바인딩
        bindNodeHandlers(containerRef.current, idToKey, centerKey, navigate)

        onRenderSuccess()
      } catch (err) {
        if (!cancelled) {
          console.error('[LinkGraph] mermaid 렌더 실패:', err)
          onRenderError()
        }
      }
    }

    void renderDiagram()

    return () => {
      cancelled = true
    }
  }, [code, idToKey, centerKey, navigate, onRenderError, onRenderSuccess])

  return (
    <div
      ref={containerRef}
      aria-label={linkGraphStrings.sectionTitle}
      className="overflow-x-auto"
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 helper — SVG 노드 핸들러 바인딩
// ─────────────────────────────────────────────────────────────────────────────

/**
 * mermaid SVG 내 노드 <g> 요소에 클릭/키보드 핸들러 + a11y 속성을 바인딩한다.
 *
 * CONCERN C1 — 노드 id 구조.
 * - mermaid flowchart 노드: `<g class="node" data-id="node_0" id="flowchart-node_0-1">`
 * - `data-id` 를 우선 사용.
 * - 없으면 `id` 속성에서 `flowchart-<sanitizedId>-<n>` 패턴으로 sanitizedId 추출.
 *
 * @param container SVG가 주입된 컨테이너 div
 * @param idToKey sanitizedId → 이슈 키 역매핑
 * @param centerKey 현재 이슈 키 (클릭 no-op)
 * @param navigate TanStack Router navigate 함수
 */
function bindNodeHandlers(
  container: HTMLDivElement,
  idToKey: Record<string, string>,
  centerKey: string,
  navigate: ReturnType<typeof useNavigate>,
): void {
  const nodeEls = container.querySelectorAll<SVGGElement>('g.node')

  for (const nodeEl of nodeEls) {
    // sanitizedId 추출: data-id 우선, 없으면 id에서 패턴 매칭
    const sanitizedId = resolveSanitizedId(nodeEl)
    if (sanitizedId === null) {
      continue
    }

    const issueKey = idToKey[sanitizedId]
    if (issueKey === undefined) {
      continue
    }

    const isCenter = issueKey === centerKey

    if (!isCenter) {
      // a11y 속성 부여 (CONCERN C2)
      nodeEl.setAttribute('tabindex', '0')
      nodeEl.setAttribute('role', 'link')
      nodeEl.setAttribute('aria-label', linkGraphStrings.nodeAriaLabel(issueKey))
      nodeEl.style.cursor = 'pointer'

      // 클릭 핸들러
      nodeEl.addEventListener('click', () => {
        void navigate({ to: '/issues/$key', params: { key: issueKey } })
      })

      // Enter 키다운 핸들러 (CONCERN C2)
      nodeEl.addEventListener('keydown', (e: KeyboardEvent) => {
        if (e.key === 'Enter') {
          void navigate({ to: '/issues/$key', params: { key: issueKey } })
        }
      })
    }
  }
}

/**
 * SVG `<g class="node">` 요소에서 sanitizedId를 추출한다.
 *
 * - `data-id` 속성 존재 시 그 값을 반환 (mermaid v11 표준 경로).
 * - 없으면 `id` 속성에서 `flowchart-<sanitizedId>-<n>` 패턴으로 추출 (fallback).
 *
 * mermaid는 노드 id를 `data-id` 에 원본 값으로 보존하므로 이를 우선한다.
 * 구버전 또는 일부 렌더 출력에서 `data-id` 가 없을 때 `id` 속성 fallback이 작동한다.
 *
 * @param el SVG `<g class="node">` 요소
 * @returns sanitizedId 문자열 (node_N 형식) 또는 null
 */
function resolveSanitizedId(el: SVGGElement): string | null {
  // data-id 우선 사용
  const dataId = el.getAttribute('data-id')
  if (dataId !== null && dataId.length > 0) {
    return dataId
  }

  // id 속성 fallback: "flowchart-node_N-숫자" 패턴에서 node_N 부분 추출
  const idAttr = el.getAttribute('id')
  if (idAttr === null) {
    return null
  }

  const match = /^flowchart-(node_\d+)-\d+$/.exec(idAttr)
  if (match === null) {
    return null
  }

  return match[1] ?? null
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — LinkGraph
// ─────────────────────────────────────────────────────────────────────────────

interface LinkGraphProps {
  /** 이슈 키 (예: "ATLAS-1") */
  issueKey: string
}

/**
 * 이슈 링크 그래프 섹션 컴포넌트.
 *
 * 기본 접힘 상태에서 시작하고, 토글 버튼으로 펼칠 때 useIssueGraph를 활성화해
 * 데이터를 조회한다. mermaid flowchart LR 다이어그램으로 시각화하고,
 * 노드 클릭 시 해당 이슈 상세 페이지로 이동한다.
 *
 * 5가지 상태.
 * 1. loading — 데이터 조회 중
 * 2. notFound — 이슈를 찾을 수 없음 (404)
 * 3. loadError — 그 외 데이터 로드 실패
 * 4. renderError — mermaid 렌더 실패
 * 5. empty — 연결된 이슈 없음 (엣지 0개)
 * 6. truncated — 노드 수 초과 안내 + 그래프 렌더
 *
 * @param issueKey 이슈 키
 */
export function LinkGraph({ issueKey }: LinkGraphProps): JSX.Element {
  const [isOpen, setIsOpen] = useState(false)
  const [depth, setDepth] = useState(2)
  const [renderError, setRenderError] = useState(false)

  const { data, isLoading, error } = useIssueGraph(issueKey, depth, isOpen)

  function handleToggle(): void {
    setIsOpen((prev) => !prev)
    setRenderError(false)
  }

  function handleDepthChange(e: ChangeEvent<HTMLSelectElement>): void {
    const newDepth = parseInt(e.target.value, 10)
    if (!isNaN(newDepth)) {
      setDepth(newDepth)
      setRenderError(false)
    }
  }

  return (
    <div className="flex flex-col gap-0">
      {/* ── 헤더 — 제목 + 토글 버튼 ─────────────────────────────────── */}
      <div className="flex items-center justify-between px-3.5 py-3 border-b border-border">
        <p className="text-xs font-medium text-muted-foreground">
          {linkGraphStrings.sectionTitle}
        </p>
        <button
          type="button"
          onClick={handleToggle}
          aria-label={isOpen ? linkGraphStrings.collapseLabel : linkGraphStrings.expandLabel}
          className="text-xs text-primary hover:underline focus:outline-none focus:ring-1 focus:ring-ring"
        >
          {isOpen ? linkGraphStrings.collapseLabel : linkGraphStrings.expandLabel}
        </button>
      </div>

      {/* ── 패널 — 접힌 상태에서는 렌더하지 않음 ───────────────────── */}
      {isOpen && (
        <div className="px-3.5 py-3 flex flex-col gap-3">
          {/* depth 컨트롤 */}
          <div className="flex items-center gap-2">
            <label
              htmlFor="link-graph-depth"
              className="text-xs text-muted-foreground shrink-0"
            >
              {linkGraphStrings.depthLabel}
            </label>
            <select
              id="link-graph-depth"
              aria-label={linkGraphStrings.depthLabel}
              value={depth}
              onChange={handleDepthChange}
              className="rounded-md border border-input bg-background px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {DEPTH_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>

          {/* 그래프 본문 영역 */}
          <GraphContent
            issueKey={issueKey}
            data={data}
            isLoading={isLoading}
            error={error}
            renderError={renderError}
            onRenderError={() => setRenderError(true)}
            onRenderSuccess={() => setRenderError(false)}
          />
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — GraphContent (상태 분기)
// ─────────────────────────────────────────────────────────────────────────────

interface GraphContentProps {
  /** 이슈 키 */
  issueKey: string
  /** 조회 데이터 */
  data: IssueGraphResponse | undefined
  /** 로딩 여부 */
  isLoading: boolean
  /** 에러 */
  error: unknown
  /** mermaid 렌더 실패 여부 */
  renderError: boolean
  /** 렌더 실패 콜백 */
  onRenderError: () => void
  /** 렌더 성공 콜백 */
  onRenderSuccess: () => void
}

/**
 * 그래프 콘텐츠 영역 — 5상태(로딩/에러/빈/truncated/정상) 분기 컴포넌트.
 */
function GraphContent({
  issueKey,
  data,
  isLoading,
  error,
  renderError,
  onRenderError,
  onRenderSuccess,
}: GraphContentProps): JSX.Element {
  // 1. 로딩
  if (isLoading) {
    return <LoadingState />
  }

  // 2. 에러 — 404 vs 기타
  if (error !== null && error !== undefined) {
    const errorCode = extractGraphErrorCode(error)
    if (errorCode === ISSUE_GRAPH_ERROR_CODES.ISSUE_NOT_FOUND) {
      return <ErrorState message={linkGraphStrings.notFound} />
    }
    return <ErrorState message={linkGraphStrings.loadError} />
  }

  // 3. mermaid 렌더 실패
  if (renderError) {
    return <ErrorState message={linkGraphStrings.renderError} />
  }

  // 데이터 없음 (enabled=false 또는 아직 조회 전)
  if (data === undefined) {
    return <LoadingState />
  }

  // 4. helper로 mermaid 코드 생성 — null이면 빈 그래프
  const result = generateGraphMermaidCode(data, EDGE_LABELS)

  if (result === null) {
    return <EmptyState />
  }

  return (
    <>
      {/* truncated 안내 — 그래프도 함께 렌더 */}
      {data.truncated && (
        <p className="text-xs text-muted-foreground">
          {linkGraphStrings.truncatedNotice}
        </p>
      )}
      <GraphRenderer
        code={result.code}
        idToKey={result.idToKey}
        centerKey={issueKey}
        onRenderError={onRenderError}
        onRenderSuccess={onRenderSuccess}
      />
    </>
  )
}
