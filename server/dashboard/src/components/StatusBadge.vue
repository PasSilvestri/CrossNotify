<script setup lang="ts">
defineProps<{
  status: 'pending' | 'active' | 'revoked';
}>();
</script>

<template>
  <span :class="['status-badge', `status-${status}`]">
    <span class="status-dot"></span>
    <span class="status-label">{{ status }}</span>
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.25rem 0.625rem;
  border-radius: var(--radius-full);
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: capitalize;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-active {
  background-color: var(--success-light);
  color: var(--success);
}
.status-active .status-dot {
  background-color: var(--success);
}

.status-pending {
  background-color: var(--warning-light);
  color: var(--warning);
}
.status-pending .status-dot {
  background-color: var(--warning);
  animation: pulse 1.5s infinite;
}

.status-revoked {
  background-color: var(--danger-light);
  color: var(--danger);
}
.status-revoked .status-dot {
  background-color: var(--danger);
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.85); }
}
</style>
