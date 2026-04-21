<template>
  <aside class="h-full bg-neutral-900 flex flex-col overflow-hidden">
    <div class="px-4 py-3 border-b border-neutral-800 bg-neutral-900/95 backdrop-blur sticky top-0 z-10">
      <div class="text-sm font-semibold tracking-[0.08em] text-neutral-100">SESSION CONTROL</div>
      <div class="text-xs text-neutral-400 mt-1">Create, attach, and close terminal sessions.</div>
    </div>

    <div class="p-3 grid grid-cols-2 gap-2 border-b border-neutral-800">
      <button
        class="h-8 px-3 text-xs rounded border border-neutral-600 text-neutral-200 hover:bg-neutral-800 transition disabled:opacity-60"
        :disabled="creating"
        @click="$emit('create')"
      >
        {{ creating ? 'Starting...' : 'Chat Session' }}
      </button>
      <button
        class="h-8 px-3 text-xs rounded border border-sky-800/80 text-sky-200 hover:bg-sky-950/40 transition disabled:opacity-60"
        :disabled="creating"
        @click="$emit('create-login')"
      >
        Login (QR)
      </button>
      <button
        class="h-8 px-3 text-xs rounded border border-neutral-600 text-neutral-300 hover:bg-neutral-800 transition col-span-2"
        @click="$emit('refresh')"
      >
        Refresh
      </button>
    </div>

    <div class="flex-1 min-h-0 overflow-auto p-2 space-y-1.5">
      <button
        v-for="s in sessions"
        :key="s.sessionId"
        class="w-full text-left rounded-md border px-3 py-2 transition hover:translate-x-[1px]"
        :class="activeSessionId === s.sessionId
          ? 'border-slate-500 bg-slate-900/70 shadow-[inset_2px_0_0_0_rgba(148,163,184,0.8)]'
          : 'border-neutral-700 bg-neutral-900 hover:bg-neutral-800'"
        @click="$emit('attach', s.sessionId)"
      >
        <div class="flex items-center gap-2">
          <span class="font-mono text-[11px] text-neutral-200 truncate">{{ s.sessionId }}</span>
          <span class="ml-auto text-[10px]" :class="s.alive ? 'text-emerald-600' : 'text-red-600'">
            {{ s.alive ? 'alive' : 'closed' }}
          </span>
        </div>
        <div class="mt-1 text-[10px] text-neutral-500 truncate">{{ s.commandLine }}</div>
      </button>

      <div v-if="sessions.length === 0" class="text-xs text-neutral-500 px-2 py-4 border border-dashed border-neutral-700 rounded">
        No session yet. Click "New Session".
      </div>
    </div>

    <div class="p-3 border-t border-neutral-800 bg-neutral-900">
      <button
        class="w-full h-8 px-3 text-xs rounded border border-neutral-600 text-neutral-300 hover:bg-neutral-800 transition disabled:opacity-60"
        :disabled="!activeSessionId"
        @click="$emit('close-active')"
      >
        Close Active Session
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import type { TerminalSessionView } from '../types/terminal'

defineProps<{
  sessions: TerminalSessionView[]
  activeSessionId: string | null
  creating: boolean
}>()

defineEmits<{
  (e: 'create'): void
  (e: 'create-login'): void
  (e: 'refresh'): void
  (e: 'attach', sessionId: string): void
  (e: 'close-active'): void
}>()
</script>
