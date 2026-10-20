<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useChannelsStore, type ChannelDetail } from '../stores/channels.js';
import StatusBadge from '../components/StatusBadge.vue';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const route = useRoute();
const router = useRouter();
const channelsStore = useChannelsStore();

const channelId = String(route.params.id);
const channel = ref<ChannelDetail | null>(null);
const isLoading = ref(true);

// Edit name/color
const isEditing = ref(false);
const editName = ref('');
const editColor = ref('#0077b6');
const isSaving = ref(false);

// Revocation dialogs
const showRevokeChannelDialog = ref(false);
const showDeleteChannelDialog = ref(false);
const showRevokeSubscriberDialog = ref(false);
const subscriberToRevoke = ref<string | null>(null);

const activeSubscribers = computed(() => {
  return channel.value?.subscribers?.filter((s) => s.status === 'active') || [];
});

async function loadChannel() {
  isLoading.value = true;
  try {
    const data = await channelsStore.fetchChannel(channelId);
    channel.value = data;
    editName.value = data.name;
    editColor.value = data.color || '#0077b6';
  } catch (err) {
    console.error('Failed to load channel:', err);
    router.push('/');
  } finally {
    isLoading.value = false;
  }
}

async function handleSaveEdit() {
  if (!editName.value.trim()) return;
  isSaving.value = true;
  try {
    await channelsStore.updateChannel(channelId, {
      name: editName.value.trim(),
      color: editColor.value,
    });
    if (channel.value) {
      channel.value.name = editName.value.trim();
      channel.value.color = editColor.value;
    }
    isEditing.value = false;
  } finally {
    isSaving.value = false;
  }
}

function promptRevokeSubscriber(subId: string) {
  subscriberToRevoke.value = subId;
  showRevokeSubscriberDialog.value = true;
}

async function confirmRevokeSubscriber() {
  if (subscriberToRevoke.value) {
    await channelsStore.revokeSubscriber(channelId, subscriberToRevoke.value);
    showRevokeSubscriberDialog.value = false;
    subscriberToRevoke.value = null;
    await loadChannel();
  }
}

async function confirmRevokeChannel() {
  showRevokeChannelDialog.value = false;
  await channelsStore.revokeChannel(channelId);
  router.push('/');
}

async function confirmDeleteChannel() {
  showDeleteChannelDialog.value = false;
  await channelsStore.deleteChannel(channelId);
  router.push('/');
}

function formatDate(ts: number | null): string {
  if (!ts) return 'Never';
  return new Date(ts).toLocaleString();
}

onMounted(loadChannel);
</script>

<template>
  <div v-if="channel" class="detail-view">
    <div class="view-header">
      <router-link to="/" class="back-link">← Back to Channels</router-link>
      <div class="header-top">
        <div class="channel-identity">
          <span class="color-dot" :style="{ backgroundColor: channel.color || 'var(--primary)' }"></span>
          <h1 class="page-title">{{ channel.name }}</h1>
        </div>
        <StatusBadge :status="channel.status" />
      </div>
    </div>

    <!-- Relay Info Card -->
    <div class="card relay-card">
      <div class="card-header">
        <h3 class="card-title">Relay Phone Information</h3>
        <button
          v-if="!isEditing"
          type="button"
          class="btn btn-secondary btn-sm"
          @click="isEditing = true"
        >
          Edit Channel
        </button>
      </div>

      <!-- Editable form -->
      <div v-if="isEditing" class="edit-box">
        <div class="edit-inputs">
          <input v-model="editName" type="text" placeholder="Channel Name" required maxlength="40" />
          <input v-model="editColor" type="color" class="color-picker" />
        </div>
        <div class="edit-actions">
          <button type="button" class="btn btn-secondary btn-sm" @click="isEditing = false">Cancel</button>
          <button type="button" class="btn btn-primary btn-sm" :disabled="isSaving" @click="handleSaveEdit">
            Save Changes
          </button>
        </div>
      </div>

      <div class="specs-grid">
        <div class="spec-item">
          <span class="spec-label">Device Name</span>
          <span class="spec-val">{{ channel.relayDeviceName || 'Not specified' }}</span>
        </div>
        <div class="spec-item">
          <span class="spec-label">Hardware Model</span>
          <span class="spec-val">{{ channel.relayDeviceModel || 'Generic Android' }}</span>
        </div>
        <div class="spec-item">
          <span class="spec-label">Operating System</span>
          <span class="spec-val">{{ channel.relayOsVersion || 'Android' }}</span>
        </div>
        <div class="spec-item">
          <span class="spec-label">App Version</span>
          <span class="spec-val">{{ channel.relayAppVersion || '1.0.0' }}</span>
        </div>
        <div class="spec-item">
          <span class="spec-label">Connected Since</span>
          <span class="spec-val">{{ formatDate(channel.activatedAt) }}</span>
        </div>
        <div class="spec-item">
          <span class="spec-label">Last Ping / Sync</span>
          <span class="spec-val">{{ formatDate(channel.lastSeenAt) }}</span>
        </div>
      </div>
    </div>

    <!-- Info Box: How to Pair Subscribers -->
    <div class="info-callout">
      <div class="callout-icon">🔐</div>
      <div>
        <strong>End-to-End Encryption Note:</strong>
        To pair a subscriber phone, open this channel on the <strong>relay phone</strong> and tap
        <strong>Share with subscriber</strong>. Because encryption keys are generated on-device,
        subscriber pairing QR codes are minted exclusively by the relay phone and never stored on the server.
      </div>
    </div>

    <!-- Subscribers Section -->
    <div class="card subscribers-card">
      <div class="card-header">
        <h3 class="card-title">
          Paired Subscribers ({{ activeSubscribers.length }})
        </h3>
      </div>

      <div v-if="activeSubscribers.length === 0" class="subscribers-empty">
        <p>No subscriber phones paired with this channel yet.</p>
        <span class="sub-hint">Use the relay phone's "Share with subscriber" feature to pair phones.</span>
      </div>

      <div v-else class="table-container">
        <table class="subscribers-table">
          <thead>
            <tr>
              <th>Subscriber Device</th>
              <th>Delivery Mode</th>
              <th>Subscribed At</th>
              <th>Last Seen</th>
              <th class="actions-col">Action</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="sub in activeSubscribers" :key="sub.subscriberId">
              <td>
                <div class="device-cell">
                  <strong>{{ sub.deviceName || 'Android Device' }}</strong>
                  <span v-if="sub.deviceModel" class="device-model">{{ sub.deviceModel }}</span>
                </div>
              </td>
              <td>
                <span class="delivery-badge" :class="sub.deliveryMode">
                  {{ sub.deliveryMode === 'battery_saver' ? 'Battery Saver' : 'Immediate Preview' }}
                </span>
              </td>
              <td>{{ formatDate(sub.createdAt) }}</td>
              <td>{{ formatDate(sub.lastSeenAt) }}</td>
              <td class="actions-col">
                <button
                  type="button"
                  class="btn btn-danger btn-xs"
                  @click="promptRevokeSubscriber(sub.subscriberId)"
                >
                  Revoke
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Danger Zone -->
    <div class="card danger-card">
      <h3 class="danger-title">Danger Zone</h3>
      <template v-if="channel.status === 'revoked'">
        <p class="danger-desc">
          This channel is currently revoked. All notifications and subscriber keys have been permanently invalidated.
        </p>
        <button type="button" class="btn btn-danger" @click="showDeleteChannelDialog = true">
          Delete Channel Permanently
        </button>
      </template>
      <template v-else>
        <p class="danger-desc">
          Revoking this channel immediately locks out the relay phone and all subscribers.
          All queued and stored encrypted notifications will be permanently deleted.
        </p>
        <button type="button" class="btn btn-danger" @click="showRevokeChannelDialog = true">
          Revoke This Channel
        </button>
      </template>
    </div>

    <!-- Delete Channel Dialog -->
    <ConfirmDialog
      :open="showDeleteChannelDialog"
      title="Delete Channel Permanently?"
      message="Are you sure you want to permanently remove this channel from the dashboard? This action cannot be undone."
      confirm-label="Delete Channel"
      :danger="true"
      @confirm="confirmDeleteChannel"
      @cancel="showDeleteChannelDialog = false"
    />

    <!-- Revoke Channel Dialog -->
    <ConfirmDialog
      :open="showRevokeChannelDialog"
      title="Revoke Entire Channel?"
      message="Are you sure you want to revoke this channel? All paired subscribers will be locked out and their keys invalidated immediately."
      confirm-label="Revoke Channel"
      :danger="true"
      @confirm="confirmRevokeChannel"
      @cancel="showRevokeChannelDialog = false"
    />

    <!-- Revoke Subscriber Dialog -->
    <ConfirmDialog
      :open="showRevokeSubscriberDialog"
      title="Revoke Subscriber Device?"
      message="This subscriber's key will be immediately invalidated and a revocation push will be dispatched. Other subscribers will not be affected."
      confirm-label="Revoke Subscriber"
      :danger="true"
      @confirm="confirmRevokeSubscriber"
      @cancel="showRevokeSubscriberDialog = false"
    />
  </div>
</template>

<style scoped>
.detail-view {
  max-width: 900px;
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

.header-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.channel-identity {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.color-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
}

.page-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
}

.card-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
}

.edit-box {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  background-color: var(--bg-surface-elevated);
  padding: 1rem;
  border-radius: var(--radius-md);
  margin-bottom: 1.25rem;
}

.edit-inputs {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}

.color-picker {
  width: 48px;
  height: 38px;
  padding: 0;
  border: none;
  cursor: pointer;
  background: transparent;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}

.specs-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 1rem;
}

.spec-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.spec-label {
  font-size: 0.75rem;
  color: var(--text-muted);
  text-transform: uppercase;
  font-weight: 600;
}

.spec-val {
  font-size: 0.9375rem;
  color: var(--text-primary);
  font-weight: 500;
}

.info-callout {
  display: flex;
  align-items: flex-start;
  gap: 0.875rem;
  background-color: var(--bg-surface-elevated);
  border-left: 3px solid var(--primary);
  padding: 1rem;
  border-radius: var(--radius-md);
  font-size: 0.875rem;
  line-height: 1.5;
  color: var(--text-secondary);
}

.callout-icon {
  font-size: 1.25rem;
  flex-shrink: 0;
}

.subscribers-empty {
  text-align: center;
  padding: 2rem;
  color: var(--text-muted);
}

.sub-hint {
  font-size: 0.8125rem;
  margin-top: 0.25rem;
  display: block;
}

.table-container {
  overflow-x: auto;
}

.subscribers-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}

.subscribers-table th {
  text-align: left;
  padding: 0.75rem;
  color: var(--text-muted);
  font-size: 0.75rem;
  text-transform: uppercase;
  border-bottom: 1px solid var(--border-subtle);
}

.subscribers-table td {
  padding: 0.875rem 0.75rem;
  border-bottom: 1px solid var(--border-subtle);
  color: var(--text-secondary);
}

.device-cell {
  display: flex;
  flex-direction: column;
}

.device-model {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.delivery-badge {
  font-size: 0.75rem;
  padding: 0.2rem 0.5rem;
  border-radius: var(--radius-full);
  font-weight: 500;
}

.delivery-badge.battery_saver {
  background-color: var(--success-light);
  color: var(--success);
}

.delivery-badge.immediate_preview {
  background-color: var(--primary-light);
  color: var(--primary);
}

.actions-col {
  text-align: right;
}

.btn-xs {
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
}

.danger-card {
  border-color: var(--danger);
  background-color: var(--bg-surface);
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.danger-title {
  color: var(--danger);
  font-size: 1.125rem;
  font-weight: 700;
}

.danger-desc {
  font-size: 0.875rem;
  color: var(--text-secondary);
  line-height: 1.4;
}
</style>
