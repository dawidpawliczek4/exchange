<template>
  <main class="flex h-full flex-col bg-[#101014] text-[#d1d4dc]">
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
      <span class="text-xs text-neutral-500">{{ received }} trades</span>
    </header>
    <section class="mt-6 ml-4 min-h-0 flex-1">
      <div ref="containerEl" class="h-1/2"></div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, shallowRef, useTemplateRef } from 'vue'
import { createChart, CandlestickSeries, type IChartApi, type ISeriesApi } from 'lightweight-charts'
import { http } from '@/shared/http'
import { safeRequest } from '@/shared/apiError'
import {
  applyTrade,
  isTrade,
  toBar,
  type Bar,
  type CandleHistoryItem,
  type MarketFrame,
} from './candles'

const WS_URL = `${import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080'}/marketdata`

const containerEl = useTemplateRef<HTMLDivElement>('containerEl')
const chart = shallowRef<IChartApi>()
const series = shallowRef<ISeriesApi<'Candlestick'>>()
const status = shallowRef<'connecting' | 'open' | 'closed'>('connecting')
const received = shallowRef(0)

let lastBar: Bar | undefined
let socket: WebSocket | undefined
let reconnectTimer: number | undefined
let disposed = false

async function loadHistory() {
  const result = await safeRequest(http.get<CandleHistoryItem[]>('/marketdata/candles'))
  if (!result.ok || disposed) return

  const bars = result.data.map(toBar)
  series.value?.setData(bars)
  lastBar = bars.at(-1)
}

function onFrame(frame: MarketFrame) {
  if (!isTrade(frame)) return

  lastBar = applyTrade(lastBar, frame)
  series.value?.update(lastBar)
  received.value += 1
}

async function connect() {
  status.value = 'connecting'
  await loadHistory()
  if (disposed) return

  socket = new WebSocket(WS_URL)
  socket.onopen = () => (status.value = 'open')
  socket.onmessage = (event) => onFrame(JSON.parse(event.data as string) as MarketFrame)
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
