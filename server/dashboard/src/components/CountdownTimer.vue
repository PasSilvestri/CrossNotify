<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';

const props = defineProps<{
  expiresAt: number;
}>();

const emit = defineEmits<{
  (e: 'expired'): void;
}>();

const remainingMs = ref(Math.max(0, props.expiresAt - Date.now()));
let timer: NodeJS.Timeout | null = null;

const formattedTime = computed(() => {
  if (remainingMs.value <= 0) return 'Expired';
  const totalSeconds = Math.floor(remainingMs.value / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
});

function updateTimer() {
  remainingMs.value = Math.max(0, props.expiresAt - Date.now());
  if (remainingMs.value <= 0) {
    if (timer) clearInterval(timer);
    emit('expired');
  }
}

watch(
  () => props.expiresAt,
  () => {
    updateTimer();
    if (timer) clearInterval(timer);
    timer = setInterval(updateTimer, 1000);
  }
);

onMounted(() => {
  updateTimer();
  timer = setInterval(updateTimer, 1000);
});

onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <span :class="['countdown-timer', { 'text-danger': remainingMs <= 60000 }]">
    {{ formattedTime }}
  </span>
</template>

<style scoped>
.countdown-timer {
  font-family: var(--font-mono);
  font-weight: 600;
  font-size: 0.875rem;
}

.text-danger {
  color: var(--danger);
  animation: blink 1s infinite;
}

@keyframes blink {
  50% { opacity: 0.5; }
}
</style>
