<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import type { Channel } from '../stores/channels.js';
import StatusBadge from './StatusBadge.vue';
import CountdownTimer from './CountdownTimer.vue';

const props = defineProps<{
  channel: Channel;
}>();

const emit = defineEmits<{
  (e: 'showQr', channelId: string): void;
  (e: 'revoke', channelId: string): void;
  (e: 'delete', channelId: string): void;
}>();

const router = useRouter();

const formattedLastSeen = computed(() => {
  if (!props.channel.lastSeenAt) return 'Never';
  const diffSec = Math.floor((Date.now() - props.channel.lastSeenAt) / 1000);
  if (diffSec < 60) return 'Just now';
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHours = Math.floor(diffMin / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  return `${Math.floor(diffHours / 24)}d ago`;
});

function navigateToDetail() {
  if (props.channel.status === 'active') {
    router.push(`/channels/${props.channel.channelId}`);
  }
}
</script>

<template>
  <div
    :class="['card', 'channel-card', { 'card-clickable': channel.status === 'active' }]"
    @click="navigateToDetail"
  >
    <div class="card-top">
      <div class="channel-identity">
        <span
          class="color-dot"
          :style="{ backgroundColor: channel.color || 'var(--primary)' }"
        ></span>
        <h3 class="channel-name">{{ channel.name }}</h3>
      </div>
      <StatusBadge :status="channel.status" />
    </div>

    <!-- Active Channel Details -->
    <div v-if="channel.status === 'active'" class="card-body">
      <div class="detail-row">
        <span class="detail-label">Relay Phone:</span>
        <span class="detail-val">
          {{ channel.relayDeviceName || channel.relayDeviceModel || 'Connected' }}
        </span>
      </div>
      <div class="metrics-grid">
        <div class="metric">
          <span class="metric-val">{{ channel.subscriberCount }}</span>
          <span class="metric-label">Subscribers</span>
        </div>
        <div class="metric">
          <span class="metric-val">{{ channel.storedNotifications }}</span>
          <span class="metric-label">Stored Msgs</span>
        </div>
        <div class="metric">
          <span class="metric-val">{{ formattedLastSeen }}</span>
          <span class="metric-label">Last Seen</span>
        </div>
      </div>
    </div>

    <!-- Pending Channel Details -->
    <div v-else-if="channel.status === 'pending'" class="card-body pending-body" @click.stop>
      <div class="pending-notice">
        <span>Awaiting scan. QR expires in:</span>
        <CountdownTimer
          v-if="channel.pendingExpiresAt"
          :expires-at="channel.pendingExpiresAt"
        />
      </div>
      <div class="pending-actions">
        <button
          type="button"
          class="btn btn-secondary btn-sm"
          @click="emit('showQr', channel.channelId)"
        >
          Show QR Again
        </button>
        <button
          type="button"
          class="btn btn-ghost btn-sm"
          @click="emit('revoke', channel.channelId)"
        >
          Cancel
        </button>
      </div>
    </div>

    <!-- Revoked Channel Details -->
    <div v-else-if="channel.status === 'revoked'" class="card-body revoked-body" @click.stop>
      <div class="revoked-notice">
        <span>Channel revoked and disconnected.</span>
      </div>
      <div class="revoked-actions">
        <button
          type="button"
          class="btn btn-danger btn-sm"
          title="Remove revoked channel from dashboard"
          @click="emit('delete', channel.channelId)"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="btn-icon">
            <polyline points="3 6 5 6 21 6"></polyline>
            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
          </svg>
          Remove Channel
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.channel-card {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  transition: transform 0.15s ease-in-out, box-shadow 0.15s ease-in-out;
}

.card-clickable {
  cursor: pointer;
}
.card-clickable:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--border-strong);
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.channel-identity {
  display: flex;
  align-items: center;
  gap: 0.625rem;
}

.color-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  flex-shrink: 0;
}

.channel-name {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8125rem;
}

.detail-label {
  color: var(--text-muted);
}

.detail-val {
  font-weight: 500;
  color: var(--text-secondary);
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.5rem;
  background-color: var(--bg-surface-elevated);
  padding: 0.625rem;
  border-radius: var(--radius-md);
  margin-top: 0.25rem;
}

.metric {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.metric-val {
  font-size: 0.875rem;
  font-weight: 700;
  color: var(--text-primary);
}

.metric-label {
  font-size: 0.6875rem;
  color: var(--text-muted);
  text-transform: uppercase;
}

.pending-body {
  gap: 1rem;
}

.pending-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.8125rem;
  color: var(--text-secondary);
  background-color: var(--warning-light);
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-md);
}

.pending-actions {
  display: flex;
  gap: 0.5rem;
}

.btn-sm {
  padding: 0.375rem 0.75rem;
  font-size: 0.75rem;
}

.revoked-body {
  gap: 0.75rem;
}

.revoked-notice {
  font-size: 0.8125rem;
  color: var(--text-muted);
  background-color: var(--bg-surface-elevated);
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-md);
}

.revoked-actions {
  display: flex;
  justify-content: flex-end;
}

.btn-icon {
  width: 14px;
  height: 14px;
}
</style>
