<template>
  <section class="h-full bg-neutral-900 flex flex-col overflow-hidden">
    <div class="px-4 py-2 border-b border-neutral-800 flex items-center gap-3 bg-neutral-900/95 backdrop-blur sticky top-0 z-10">
      <div class="text-xs font-semibold tracking-[0.08em] text-neutral-100">EVENTS</div>
      <div class="text-[11px]" :class="connected ? 'text-emerald-400' : 'text-neutral-500'">
        {{ connected ? 'CONNECTED' : 'DISCONNECTED' }}
      </div>
      <div class="text-[11px] text-neutral-500 truncate">{{ activeSessionId || 'no active session' }}</div>
    </div>

    <div class="flex-1 min-h-0 overflow-auto p-2 space-y-1">
      <div
        v-for="event in events"
        :key="event.id"
        class="px-2 py-1 rounded text-xs font-mono border flex items-center gap-2"
        :class="eventClass(event.level)"
      >
        <span class="text-neutral-500 shrink-0 px-1.5 py-0.5 border border-current/20 rounded">{{ event.at }}</span>
        <span class="min-w-0 break-all">{{ event.message }}</span>
      </div>

      <div v-if="events.length === 0" class="text-xs text-neutral-500 px-2 py-2 border border-dashed border-neutral-700 rounded">
        No events.
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { TerminalUiEvent } from '../types/terminal'

defineProps<{
  events: TerminalUiEvent[]
  connected: boolean
  activeSessionId: string | null
}>()

function eventClass(level: TerminalUiEvent['level']): string {
  switch (level) {
    case 'success':
      return 'border-emerald-900 bg-emerald-950/40 text-emerald-300'
    case 'warn':
      return 'border-amber-900 bg-amber-950/40 text-amber-300'
    case 'error':
      return 'border-red-900 bg-red-950/40 text-red-300'
    default:
      return 'border-neutral-700 bg-neutral-800/80 text-neutral-300'
  }
}
</script>
