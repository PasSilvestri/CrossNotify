<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useChannelsStore } from '../stores/channels.js';
import { formatApiError } from '../api/client.js';
import QrCode from '../components/QrCode.vue';
import CountdownTimer from '../components/CountdownTimer.vue';

const router = useRouter();
const channelsStore = useChannelsStore();

const channelName = ref('');
const selectedColor = ref('#4F46E5');
const colorPresets = [
  '#4F46E5', // Indigo
  '#2563EB', // Blue
  '#059669', // Emerald
  '#D97706', // Amber
  '#DC2626', // Red
  '#7C3AED', // Purple
  '#DB2777', // Pink
  '#475569', // Slate
];

const isLoading = ref(false);
const error = ref<string | null>(null);

// QR & provisioning state
const createdChannelId = ref<string | null>(null);
const qrPayloadString = ref<string | null>(null);
const expiresAt = ref<number | null>(null);
const isExpired = ref(false);
const isSuccess = ref(false);
const connectedDeviceName = ref<string | null>(null);

let pollInterval: NodeJS.Timeout | null = null;

async function handleCreate() {
  error.value = null;
  if (!channelName.value.trim()) {
    error.value = 'Please enter a name for this relay channel.';
    return;
  }

  isLoading.value = true;
  try {
    const res = await channelsStore.createRelay({
      name: channelName.value.trim(),
      color: selectedColor.value,
    });

    createdChannelId.value = res.channelId;
    qrPayloadString.value = JSON.stringify(res.qrPayload);
    expiresAt.value = res.expiresAt;
    isExpired.value = false;

    startPolling();
  } catch (err: any) {
    error.value = formatApiError(err);
  } finally {
    isLoading.value = false;
  }
}

async function handleRegenerate() {
  if (!createdChannelId.value) return;
  isLoading.value = true;
  try {
    const res = await channelsStore.regenerateRelay(createdChannelId.value);
    qrPayloadString.value = JSON.stringify(res.qrPayload);
    expiresAt.value = res.expiresAt;
    isExpired.value = false;
    startPolling();
  } catch (err: any) {
    error.value = formatApiError(err);
  } finally {
    isLoading.value = false;
  }
}

function startPolling() {
  stopPolling();
  pollInterval = setInterval(async () => {
    if (document.hidden || !createdChannelId.value) return;
    try {
      const channel = await channelsStore.fetchChannel(createdChannelId.value);
      if (channel.status === 'active') {
        stopPolling();
        // Clear QR code payload from memory immediately
        qrPayloadString.value = null;
        isSuccess.value = true;
        connectedDeviceName.value = channel.relayDeviceName || channel.relayDeviceModel || 'Relay Device';

        // Auto navigate after 1.5 seconds
        setTimeout(() => {
          router.push(`/channels/${createdChannelId.value}`);
        }, 1500);
      }
    } catch {
      // ignore transient poll errors
    }
  }, 2000);
}

function stopPolling() {
  if (pollInterval) {
    clearInterval(pollInterval);
    pollInterval = null;
  }
}

function handleExpired() {
  isExpired.value = true;
  stopPolling();
}

onMounted(() => {
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && createdChannelId.value && !isExpired.value && !isSuccess.value) {
      startPolling();
    }
  });
});

onUnmounted(() => {
  stopPolling();
  // Ensure QR payload never leaks in memory
  qrPayloadString.value = null;
});
</script>

<template>
  <div class="add-relay-view">
    <div class="view-header">
      <router-link to="/" class="back-link">← Back to Channels</router-link>
      <h1 class="page-title">Add Relay Phone</h1>
      <p class="page-subtitle">
        Provision a new notification channel for your phone.
      </p>
    </div>

    <!-- Step 1: Channel Setup Form (hidden once QR created) -->
    <div v-if="!qrPayloadString && !isSuccess" class="card form-card">
      <form @submit.prevent="handleCreate" class="relay-form">
        <div v-if="error" class="alert-error">
          {{ error }}
        </div>

        <div class="form-group">
          <label for="channelName">Channel Name</label>
          <input
            id="channelName"
            v-model="channelName"
            type="text"
            required
            maxlength="40"
            placeholder="e.g. Work Phone, Pixel 8, Personal"
            autofocus
          />
        </div>

        <div class="form-group">
          <label>Badge Colour</label>
          <div class="colors-row">
            <button
              v-for="color in colorPresets"
              :key="color"
              type="button"
              :class="['color-choice', { 'color-choice-active': selectedColor === color }]"
              :style="{ backgroundColor: color }"
              @click="selectedColor = color"
              :aria-label="color"
            ></button>
          </div>
        </div>

        <div class="form-actions">
          <button type="submit" class="btn btn-primary" :disabled="isLoading">
            <span v-if="isLoading">Generating QR...</span>
            <span v-else>Generate Pairing QR</span>
          </button>
        </div>
      </form>
    </div>

    <!-- Step 2: Live Provisioning QR State -->
    <div v-else-if="qrPayloadString && !isSuccess" class="card qr-card">
      <div v-if="!isExpired" class="qr-active-state">
        <div class="qr-header">
          <span class="color-dot" :style="{ backgroundColor: selectedColor }"></span>
          <h2 class="qr-channel-name">{{ channelName }}</h2>
        </div>

        <QrCode :value="qrPayloadString" :size="260" aria-label="Relay Provisioning QR Code" />

        <div class="timer-row">
          <span>QR expires in:</span>
          <CountdownTimer v-if="expiresAt" :expires-at="expiresAt" @expired="handleExpired" />
        </div>

        <div class="instructions-box">
          <p>
            1. Open the <strong>CrossNotify</strong> app on your relay phone.
          </p>
          <p>
            2. Tap <strong>Relay</strong> → <strong>Add relay channel</strong>.
          </p>
          <p>
            3. Point your camera at this QR code.
          </p>
        </div>

        <div class="waiting-indicator">
          <span class="spinner"></span>
          <span>Waiting for phone to scan and register...</span>
        </div>
      </div>

      <!-- Expired state -->
      <div v-else class="qr-expired-state">
        <div class="expired-icon">⏰</div>
        <h3>QR Code Expired</h3>
        <p>For security, relay provisioning QR codes expire after 15 minutes.</p>
        <button type="button" class="btn btn-primary" :disabled="isLoading" @click="handleRegenerate">
          Generate New QR Code
        </button>
      </div>
    </div>

    <!-- Step 3: Success State -->
    <div v-else-if="isSuccess" class="card success-card">
      <div class="success-icon">✓</div>
      <h2>Relay Phone Connected!</h2>
      <p class="success-desc">
        <strong>{{ connectedDeviceName }}</strong> has registered successfully. Redirecting to channel...
      </p>
    </div>
  </div>
</template>

<style scoped>
.add-relay-view {
  max-width: 600px;
  margin: 0 auto;
  padding: 2rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.back-link {
  font-size: 0.875rem;
  color: var(--primary);
  text-decoration: none;
  font-weight: 500;
  display: inline-block;
  margin-bottom: 0.5rem;
}

.page-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary);
}

.page-subtitle {
  font-size: 0.9375rem;
  color: var(--text-muted);
}

.relay-form {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.colors-row {
  display: flex;
  gap: 0.625rem;
  flex-wrap: wrap;
}

.color-choice {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  transition: transform 0.1s, border-color 0.15s;
}

.color-choice:hover {
  transform: scale(1.1);
}

.color-choice-active {
  border-color: var(--text-primary);
  transform: scale(1.15);
}

.form-actions {
  margin-top: 0.5rem;
}

/* QR card */
.qr-card {
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.qr-active-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1.25rem;
  width: 100%;
}

.qr-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.color-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.qr-channel-name {
  font-size: 1.25rem;
  font-weight: 700;
}

.timer-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.instructions-box {
  background-color: var(--bg-surface-elevated);
  padding: 1rem 1.25rem;
  border-radius: var(--radius-md);
  font-size: 0.875rem;
  color: var(--text-secondary);
  text-align: left;
  line-height: 1.5;
  width: 100%;
  max-width: 400px;
}

.waiting-indicator {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  font-size: 0.875rem;
  color: var(--primary);
  font-weight: 500;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--primary-light);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.qr-expired-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 1.5rem;
}

.expired-icon {
  font-size: 2.5rem;
}

.success-card {
  text-align: center;
  padding: 3rem 2rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  border-color: var(--success);
  background-color: var(--success-light);
}

.success-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background-color: var(--success);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.5rem;
  font-weight: 700;
}

.success-desc {
  color: var(--text-secondary);
}

.alert-error {
  background-color: var(--danger-light);
  color: var(--danger);
  padding: 0.75rem 1rem;
  border-radius: var(--radius-md);
  font-size: 0.8125rem;
}
</style>
