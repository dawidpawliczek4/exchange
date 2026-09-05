<template>
  <main class="flex h-dvh flex-col bg-[#101014] text-[#d1d4dc]">
    <header class="flex items-center gap-3 px-4 py-2 text-sm">
      <span class="font-semibold">BASE/QUOTE</span>
      <span
        class="rounded px-2 py-0.5 text-xs"
        :class="{
          'bg-emerald-900 text-emerald-300': status === 'open',
          'bg-amber-900 text-amber-300': status === 'connecting',
          'bg-red-900 text-red-300': status === 'closed',
        }"
      >
        {{ status }}
      </span>
      <span class="text-xs text-neutral-500">{{ received }} candles</span>
      <nav class="ml-auto flex items-center gap-3 text-xs">
        <button
          v-if="auth.isAuthenticated"
          class="text-neutral-400 hover:text-neutral-200"
          @click="auth.logout()"
        >
          Log out
        </button>
        <template v-else>
          <RouterLink to="/login" class="text-neutral-400 hover:text-neutral-200"
            >Sign in</RouterLink
          >
          <RouterLink to="/register" class="text-neutral-400 hover:text-neutral-200"
            >Register</RouterLink
          >
        </template>
      </nav>
    </header>
    <div ref="containerEl" class="min-h-0 flex-1"></div>
  </main>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, shallowRef, useTemplateRef } from 'vue'
import { createChart, CandlestickSeries, type IChartApi, type ISeriesApi } from 'lightweight-charts'
import { createBarStream, type CandleMessage } from './candles'
import { useAuthStore } from '@/auth/store'

const WS_URL = `${import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080'}/marketdata/candles`

const containerEl = useTemplateRef<HTMLDivElement>('containerEl')
const chart = shallowRef<IChartApi>()
const series = shallowRef<ISeriesApi<'Candlestick'>>()
const status = shallowRef<'connecting' | 'open' | 'closed'>('connecting')
const received = shallowRef(0)

const auth = useAuthStore()
const nextBar = createBarStream()

let socket: WebSocket | undefined
let reconnectTimer: number | undefined
let disposed = false

function onCandle(message: CandleMessage) {
  const bar = nextBar(message)
  if (bar === null) return

  series.value?.update(bar)
  received.value += 1
}

function connect() {
  status.value = 'connecting'
  socket = new WebSocket(WS_URL)

  socket.onopen = () => (status.value = 'open')
  socket.onmessage = (event) => onCandle(JSON.parse(event.data as string) as CandleMessage)
  socket.onclose = () => {
    status.value = 'closed'
    if (!disposed) reconnectTimer = window.setTimeout(connect, 2000)
  }
}

onMounted(() => {
  chart.value = createChart(containerEl.value!, {
    autoSize: true,
    layout: { background: { color: '#101014' }, textColor: '#d1d4dc' },
    grid: { vertLines: { color: '#1c1c22' }, horzLines: { color: '#1c1c22' } },
    timeScale: { timeVisible: true, secondsVisible: true, rightOffset: 5 },
  })

  series.value = chart.value.addSeries(CandlestickSeries, {
    upColor: '#26a69a',
    downColor: '#ef5350',
    wickUpColor: '#26a69a',
    wickDownColor: '#ef5350',
    borderVisible: false,
    priceFormat: { type: 'price', precision: 0, minMove: 1 },
  })

  connect()
})

onUnmounted(() => {
  disposed = true
  if (reconnectTimer) clearTimeout(reconnectTimer)
  socket?.close()
  chart.value?.remove()
})
</script>
