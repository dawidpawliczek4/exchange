<template>
  <div class="flex h-full flex-col">
    <AppNavbar />
    <div class="min-h-0 flex-1">
      <RouterView />
    </div>
  </div>
</template>

<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppNavbar from '@/layout/AppNavbar.vue'
</script>
