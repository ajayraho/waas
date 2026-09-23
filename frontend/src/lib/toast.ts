import { useSyncExternalStore } from 'react'

export type ToastKind = 'info' | 'success' | 'warn' | 'error' | 'turn'
export interface Toast {
  id: number
  kind: ToastKind
  title: string
  body?: string
  ms: number
}

let items: Toast[] = []
let nextId = 1
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())

export const toast = {
  push(kind: ToastKind, title: string, body?: string, ms = 4200) {
    const t = { id: nextId++, kind, title, body, ms }
    items = [...items, t].slice(-5)
    emit()
    setTimeout(() => toast.dismiss(t.id), ms)
  },
  dismiss(id: number) {
    items = items.filter((t) => t.id !== id)
    emit()
  },
}

export function useToasts() {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => items,
  )
}
