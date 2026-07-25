// 가젯 추가 카탈로그 모달 — 2단계(목록→설정 폼) + TanStack Query 카탈로그 조회 (FR-DB-02 Task 6)
import type { JSX } from 'react'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import { fetchGadgetCatalog, type GadgetCatalogEntry } from '@/api/gadget-catalog'
import { GadgetConfigForm } from './GadgetConfigForm'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 카탈로그 category 표시 순서 */
const CATEGORY_ORDER = ['ISSUE', 'STATIC', 'CHART', 'ACTIVITY'] as const

type GadgetCategory = (typeof CATEGORY_ORDER)[number]

// ─────────────────────────────────────────────────────────────────────────────
// 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

/** GadgetCatalogModal 컴포넌트 props */
export interface GadgetCatalogModalProps {
  /** 모달 열림 여부 */
  open: boolean
  /**
   * 추가 콜백 — 설정 검증 통과 후 호출.
   * 위치/크기 결정은 부모(Task 7 상세 페이지)가 담당한다.
   */
  onAdd: (partial: { gadgetType: string; config: Record<string, unknown> }) => void
  /** 모달 닫기 콜백 */
  onClose: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 추가 카탈로그 모달.
 *
 * Step 1 (카탈로그).
 *   - GET /api/v1/dashboards/gadget-catalog를 TanStack Query로 조회한다.
 *   - 12종을 category(ISSUE/STATIC/CHART/ACTIVITY) 그룹으로 표시한다.
 *   - enabled=false 가젯은 "준비 중" 텍스트와 함께 비활성(disabled) 처리한다 (S6).
 *
 * Step 2 (설정 폼).
 *   - enabled=true 가젯 선택 시 GadgetConfigForm을 표시한다.
 *   - GadgetConfigForm에 key={entry.type}을 부여해 가젯 종류 변경 시 재마운트한다
 *     (memory react-usestate-stale-key-prop).
 *
 * 추가.
 *   - GadgetConfigForm이 validateGadgetConfig 통과 후 onAdd(config)를 호출한다.
 *   - 이 모달은 onAdd({ gadgetType, config })를 부모로 전달하고 위치/크기는 결정하지 않는다.
 */
export function GadgetCatalogModal({ open, onAdd, onClose }: GadgetCatalogModalProps): JSX.Element {
  const [selectedEntry, setSelectedEntry] = useState<GadgetCatalogEntry | null>(null)

  const { data: entries = [], isLoading } = useQuery({
    queryKey: ['gadget-catalog'],
    queryFn: fetchGadgetCatalog,
    staleTime: 5 * 60_000,
  })

  // category별 그룹화
  const grouped: Record<GadgetCategory, GadgetCatalogEntry[]> = {
    ISSUE: [],
    STATIC: [],
    CHART: [],
    ACTIVITY: [],
  }
  for (const entry of entries) {
    if (entry.category in grouped) {
      grouped[entry.category as GadgetCategory].push(entry)
    }
  }

  function handleOpenChange(nextOpen: boolean): void {
    if (!nextOpen) {
      onClose()
      setSelectedEntry(null)
    }
  }

  function handleSelectGadget(entry: GadgetCatalogEntry): void {
    setSelectedEntry(entry)
  }

  function handleBack(): void {
    setSelectedEntry(null)
  }

  function handleAdd(config: Record<string, unknown>): void {
    if (selectedEntry === null) return
    onAdd({ gadgetType: selectedEntry.type, config })
  }

  const isStep2 = selectedEntry !== null

  return (
    // aria-describedby={undefined} — DialogDescription 불필요 경고 억제. 폼 필드 자체가 설명 역할을 한다.
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-2xl" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>
            {isStep2 ? `가젯 설정 — ${selectedEntry.label}` : '가젯 추가'}
          </DialogTitle>
        </DialogHeader>

        {/* Step 1: 카탈로그 목록 */}
        {!isStep2 && (
          <div>
            {isLoading ? (
              <p className="text-sm text-muted-foreground">카탈로그를 불러오는 중...</p>
            ) : (
              <div className="max-h-[60vh] space-y-4 overflow-y-auto pr-1">
                {CATEGORY_ORDER.map((cat) => {
                  const items = grouped[cat]
                  if (items.length === 0) return null
                  return (
                    <section key={cat}>
                      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                        {cat}
                      </h3>
                      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                        {items.map((entry) => (
                          // PR22 OUT — P5 옵션 행: 가젯 카탈로그 카드로 flex flex-col + text-left 본문 정렬이 필요하고, Button의 justify-center와 충돌한다
                          <button
                            key={entry.type}
                            type="button"
                            disabled={!entry.enabled}
                            onClick={() => handleSelectGadget(entry)}
                            aria-label={entry.label}
                            className={cn(
                              'flex flex-col gap-1 rounded-lg border border-border p-3 text-left text-sm transition-colors',
                              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                              entry.enabled
                                ? 'cursor-pointer hover:bg-muted'
                                : 'cursor-not-allowed opacity-50',
                            )}
                          >
                            <span className="font-medium">{entry.label}</span>
                            {!entry.enabled && (
                              <span className="text-xs text-muted-foreground">준비 중</span>
                            )}
                          </button>
                        ))}
                      </div>
                    </section>
                  )
                })}
              </div>
            )}
          </div>
        )}

        {/* Step 2: 설정 폼 — key={entry.type}으로 가젯 변경 시 재마운트 */}
        {isStep2 && (
          <GadgetConfigForm
            key={selectedEntry.type}
            entry={selectedEntry}
            onAdd={handleAdd}
            onBack={handleBack}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}
