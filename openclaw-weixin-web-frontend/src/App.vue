<template>
  <div class="h-full flex flex-col bg-neutral-900 text-neutral-100">
    <header class="shrink-0 border-b border-neutral-800 bg-neutral-900/90 backdrop-blur">
      <div class="px-4 py-3 flex items-center gap-3">
        <h1 class="text-sm font-semibold tracking-wide text-neutral-100">
          LangChat OpenClaw Weixin Web Console
        </h1>
        <span class="text-xs text-neutral-400">No Auth · PTY Bridge</span>
        <span
          class="text-[11px] rounded-full border px-2 py-0.5"
          :class="connected
            ? 'border-emerald-800 bg-emerald-950/30 text-emerald-300'
            : 'border-neutral-700 bg-neutral-900 text-neutral-400'"
        >
          {{ connected ? 'CONNECTED' : 'DISCONNECTED' }}
        </span>
        <span class="ml-auto text-[11px] text-neutral-400 truncate">
          Active: {{ activeSessionId || 'none' }}
        </span>
      </div>
    </header>

    <main class="flex-1 min-h-0 p-3 lg:p-4">
      <div class="h-full rounded-xl border border-neutral-800 bg-neutral-900 overflow-hidden shadow-[0_10px_30px_rgba(0,0,0,0.25)]">
        <div class="h-full grid grid-cols-1 lg:grid-cols-[260px_minmax(0,1fr)] lg:gap-0">
          <div class="min-h-0 lg:border-r lg:border-neutral-800">
            <SessionSidebar
              :sessions="sessions"
              :active-session-id="activeSessionId"
              :creating="creating"
              @create="createSession('chat')"
              @create-login="createSession('login')"
              @refresh="refreshSessions(false)"
              @attach="attachSession"
              @close-active="closeActiveSession"
            />
          </div>

          <section class="h-full min-h-0 bg-neutral-900 grid grid-rows-[2.25rem_1.9rem_minmax(0,1fr)]">
            <div class="h-9 border-b border-neutral-800 px-3 flex items-center">
              <span class="text-xs font-semibold tracking-[0.08em] text-slate-300">TERMINAL</span>
              <span class="ml-3 text-[11px] text-neutral-400 truncate">
                {{ activeSessionId || 'select or create a session' }}
              </span>
              <span
                class="ml-auto text-[10px] rounded-full border px-2 py-0.5"
                :class="connected
                  ? 'border-emerald-800 bg-emerald-950/40 text-emerald-300'
                  : 'border-neutral-700 bg-neutral-900 text-neutral-500'"
              >
                {{ connected ? 'online' : 'offline' }}
              </span>
            </div>

            <div class="border-b border-neutral-800 px-3 flex items-center gap-1.5 overflow-x-auto no-scrollbar">
              <span class="inline-flex items-center h-5 px-2 rounded border border-neutral-700 bg-neutral-900 text-[10px] tracking-[0.08em] uppercase text-neutral-300 whitespace-nowrap">
                PTY Bridge
              </span>
              <span class="inline-flex items-center h-5 px-2 rounded border border-neutral-700 bg-neutral-900 text-[10px] tracking-[0.08em] uppercase text-neutral-300 whitespace-nowrap">
                JDK 17+
              </span>
              <span class="inline-flex items-center h-5 px-2 rounded border border-neutral-700 bg-neutral-900 text-[10px] tracking-[0.08em] uppercase text-neutral-300 whitespace-nowrap">
                websocket
              </span>
              <span
                class="inline-flex items-center h-5 px-2 rounded border text-[10px] tracking-[0.08em] uppercase whitespace-nowrap"
                :class="connected
                  ? 'border-emerald-900 bg-emerald-950/40 text-emerald-300'
                  : 'border-neutral-700 bg-neutral-900 text-neutral-400'"
              >
                {{ connected ? 'live' : 'offline' }}
              </span>
              <span class="inline-flex items-center h-5 px-2 rounded border border-neutral-700 bg-neutral-900 text-[10px] text-neutral-400 truncate">
                {{ activeSessionId || 'no-session' }}
              </span>
            </div>

            <div class="min-h-0 p-2.5 pb-2">
              <div class="h-full rounded border border-neutral-800 overflow-hidden relative bg-neutral-900">
                <div class="h-full p-2.5">
                  <TerminalViewport
                    :ws-path="activeWsPath"
                    @connected="onTerminalConnected"
                    @error="onTerminalError"
                    @output="onTerminalOutput"
                  />
                </div>
              </div>
            </div>
          </section>

        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import SessionSidebar from './components/SessionSidebar.vue'
import TerminalViewport from './components/TerminalViewport.vue'
import { closeTerminalSession, createTerminalSession, listTerminalSessions } from './api/terminalApi'
import type { TerminalSessionView } from './types/terminal'

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
const sessions = ref<TerminalSessionView[]>([])
const activeSessionId = ref<string | null>(null)
const activeWsPath = ref<string | null>(null)
const creating = ref(false)
const connected = ref(false)
let pollTimer: number | null = null

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
function pushEvent(level: 'info' | 'success' | 'warn' | 'error', message: string): void {
  const line = `[web:${level}] ${message}`
  if (level === 'error') {
    console.error(line)
    return
  }
  if (level === 'warn') {
    console.warn(line)
    return
  }
  console.info(line)
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
function buildWsPath(sessionId: string): string {
  return `/ws/terminal/${sessionId}`
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
function attachSession(sessionId: string): void {
  if (!sessionId) {
    return
  }
  activeSessionId.value = sessionId
  activeWsPath.value = buildWsPath(sessionId)
  pushEvent('info', `attached session ${sessionId}`)
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
async function refreshSessions(withHint = true): Promise<void> {
  const list = await listTerminalSessions()
  sessions.value = list

  if (activeSessionId.value) {
    const exists = list.some(item => item.sessionId === activeSessionId.value)
    if (!exists) {
      const oldId = activeSessionId.value
      activeSessionId.value = null
      activeWsPath.value = null
      connected.value = false
      pushEvent('warn', `session expired: ${oldId}`)
    }
  }

  if (withHint) {
    pushEvent('info', `loaded ${list.length} session(s)`)
  }
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
async function createSession(mode: 'chat' | 'login' = 'chat'): Promise<void> {
  if (creating.value) {
    return
  }
  creating.value = true
  try {
    const created = await createTerminalSession({ mode })
    await refreshSessions(false)
    activeSessionId.value = created.sessionId
    activeWsPath.value = created.wsPath || buildWsPath(created.sessionId)
    pushEvent('success', `created ${mode} session ${created.sessionId}`)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    pushEvent('error', message)
  } finally {
    creating.value = false
  }
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
async function closeActiveSession(): Promise<void> {
  const current = activeSessionId.value
  if (!current) {
    return
  }
  try {
    await closeTerminalSession(current)
    pushEvent('success', `closed session ${current}`)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    pushEvent('error', message)
  } finally {
    activeSessionId.value = null
    activeWsPath.value = null
    connected.value = false
    await refreshSessions(false)
  }
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
function onTerminalConnected(value: boolean): void {
  connected.value = value
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
function onTerminalError(message: string): void {
  pushEvent('error', message)
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
function onTerminalOutput(text: string): void {
  if (text.includes('[session detached]')) {
    pushEvent('warn', 'terminal detached')
  }
}

onMounted(async () => {
  try {
    await refreshSessions(true)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    pushEvent('error', message)
  }
  pollTimer = window.setInterval(() => {
    refreshSessions(false).catch(() => {
      // suppress timer noise
    })
  }, 8000)
})

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>
