<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useChannelsStore } from '../stores/channels.js';
import { useSetupStore } from '../stores/setup.js';
import { apiRequest } from '../api/client.js';
import ChannelCard from '../components/ChannelCard.vue';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import QrCode from '../components/QrCode.vue';
import CountdownTimer from '../components/CountdownTimer.vue';

const router = useRouter();
const channelsStore = useChannelsStore();
const setupStore = useSetupStore();

const publicUrl = ref('');
const showRevokeDialog = ref(false);
const channelToRevoke = ref<string | null>(null);

// QR modal for pending channel regeneration
const showQrModal = ref(false);
const qrPayloadString = ref<string>('');
const qrExpiresAt = ref<number>(0);
const qrChannelName = ref<string>('');

const isCopied = ref(false);
const showRawJson = ref(false);
const canShare = typeof navigator !== 'undefined' && !!navigator.share;

async function handleCopy() {
  if (!qrPayloadString.value) return;
  try {
    await navigator.clipboard.writeText(qrPayloadString.value);
    isCopied.value = true;
    setTimeout(() => {
      isCopied.value = false;
    }, 2000);
  } catch (err) {
    console.error('Failed to copy QR payload:', err);
  }
}

async function handleShare() {
  if (!qrPayloadString.value) return;
  if (navigator.share) {
    try {
      await navigator.share({
        title: `CrossNotify Relay - ${qrChannelName.value}`,
        text: qrPayloadString.value,
      });
    } catch {
      // User dismissed share dialog
    }
  } else {
    handleCopy();
  }
}

async function loadData() {
  await channelsStore.fetchChannels();
  try {
    const settings = await apiRequest<{ publicUrl: string | null }>('/admin/api/settings');
    publicUrl.value = settings.publicUrl || '';
  } catch {
    // ignore
  }
}

function handleAddRelay() {
  router.push('/relays/new');
}

async function handleShowQr(channelId: string) {
  try {
    const res = await channelsStore.regenerateRelay(channelId);
    qrPayloadString.value = JSON.stringify(res.qrPayload);
    qrExpiresAt.value = res.expiresAt;
    qrChannelName.value = res.qrPayload.channel.name;
    showQrModal.value = true;
  } catch (err: any) {
    alert(err.message || 'Failed to regenerate QR code');
  }
}

function promptRevoke(channelId: string) {
  channelToRevoke.value = channelId;
  showRevokeDialog.value = true;
}

async function confirmRevoke() {
  if (channelToRevoke.value) {
    await channelsStore.revokeChannel(channelToRevoke.value);
    channelToRevoke.value = null;
    showRevokeDialog.value = false;
  }
}

const showDeleteDialog = ref(false);
const channelToDelete = ref<string | null>(null);
const showClearAllRevokedDialog = ref(false);

const revokedChannels = computed(() => {
  return channelsStore.channels.filter((c) => c.status === 'revoked');
});

function promptDelete(channelId: string) {
  channelToDelete.value = channelId;
  showDeleteDialog.value = true;
}

async function confirmDelete() {
  if (channelToDelete.value) {
    await channelsStore.deleteChannel(channelToDelete.value);
    channelToDelete.value = null;
    showDeleteDialog.value = false;
  }
}

async function confirmClearAllRevoked() {
  await channelsStore.clearRevokedChannels();
  showClearAllRevokedDialog.value = false;
}

onMounted(loadData);
</script>

<template>
  <div class="channels-view">
    <div class="header-section">
      <div class="server-meta">
        <h1 class="page-title">{{ setupStore.serverName }}</h1>
        <span v-if="publicUrl" class="public-url-badge">
          <code>{{ publicUrl }}</code>
        </span>
      </div>
      <div class="header-actions">
        <button
          v-if="revokedChannels.length > 0"
          type="button"
          class="btn btn-secondary"
          @click="showClearAllRevokedDialog = true"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="btn-icon">
            <polyline points="3 6 5 6 21 6"></polyline>
            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
          </svg>
          Clean Up Revoked ({{ revokedChannels.length }})
        </button>
        <button type="button" class="btn btn-primary" @click="handleAddRelay">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="btn-icon">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="16"/>
            <line x1="8" y1="12" x2="16" y2="12"/>
          </svg>
          Add Relay Phone
        </button>
      </div>
    </div>

    <!-- Empty State -->
    <div v-if="channelsStore.channels.length === 0 && !channelsStore.isLoading" class="card empty-card">
      <div class="empty-icon">📱</div>
      <h3 class="empty-title">No Relay Phones Connected</h3>
      <p class="empty-desc">
        A <strong>Relay Phone</strong> runs the CrossNotify app, captures notifications you select, encrypts them on-device, and forwards them to your server.
      </p>
      <div class="empty-steps">
        <div class="empty-step">
          <span class="step-num">1</span>
          <span>Click <strong>Add Relay Phone</strong> above to generate a provisioning QR code.</span>
        </div>
        <div class="empty-step">
          <span class="step-num">2</span>
          <span>Scan it using CrossNotify on the phone whose notifications you want to relay.</span>
        </div>
        <div class="empty-step">
          <span class="step-num">3</span>
          <span>Other phones pair as subscribers directly from the relay phone.</span>
        </div>
      </div>
      <button type="button" class="btn btn-primary" @click="handleAddRelay">
        Add Your First Relay Phone
      </button>
    </div>

    <!-- Channels List -->
    <div v-else class="channels-grid">
      <ChannelCard
        v-for="channel in channelsStore.channels"
        :key="channel.channelId"
        :channel="channel"
        @show-qr="handleShowQr"
        @revoke="promptRevoke"
        @delete="promptDelete"
      />
    </div>

    <!-- Revoke Confirmation Dialog -->
    <ConfirmDialog
      :open="showRevokeDialog"
      title="Revoke Relay Channel?"
      message="This will immediately revoke all subscriber keys and permanently delete all stored notifications for this channel. This action cannot be undone."
      confirm-label="Revoke Channel"
      :danger="true"
      @confirm="confirmRevoke"
      @cancel="showRevokeDialog = false"
    />

    <!-- Delete Single Revoked Channel Confirmation Dialog -->
    <ConfirmDialog
      :open="showDeleteDialog"
      title="Remove Revoked Channel?"
      message="Remove this revoked channel from your dashboard? This permanently purges the channel record from the server."
      confirm-label="Remove Channel"
      :danger="true"
      @confirm="confirmDelete"
      @cancel="showDeleteDialog = false"
    />

    <!-- Clear All Revoked Channels Confirmation Dialog -->
    <ConfirmDialog
      :open="showClearAllRevokedDialog"
      title="Clean Up All Revoked Channels?"
      :message="`Are you sure you want to remove all ${revokedChannels.length} revoked channel(s) from the dashboard? This permanently removes their records from the server.`"
      confirm-label="Clean Up All"
      :danger="true"
      @confirm="confirmClearAllRevoked"
      @cancel="showClearAllRevokedDialog = false"
    />

    <!-- Show QR Modal -->
    <div v-if="showQrModal" class="modal-overlay" @click.self="showQrModal = false">
      <div class="modal-card">
        <div class="modal-header">
          <h3 class="modal-title">Pair Relay Phone: {{ qrChannelName }}</h3>
          <button type="button" class="close-btn" @click="showQrModal = false">✕</button>
        </div>
        <div class="modal-body">
          <QrCode :value="qrPayloadString" :size="240" aria-label="Relay Provisioning QR Code" />
          <div class="payload-actions">
            <button type="button" class="btn btn-secondary btn-sm" @click="handleCopy">
              <svg v-if="!isCopied" class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
              </svg>
              <span v-if="isCopied">✓ Copied</span>
              <span v-else>Copy QR JSON</span>
            </button>
            <button v-if="canShare" type="button" class="btn btn-secondary btn-sm" @click="handleShare">
              <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="18" cy="5" r="3"></circle>
                <circle cx="6" cy="12" r="3"></circle>
                <circle cx="18" cy="19" r="3"></circle>
                <line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line>
                <line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line>
              </svg>
              <span>Share</span>
            </button>
            <button type="button" class="btn btn-ghost btn-sm" @click="showRawJson = !showRawJson">
              <span>{{ showRawJson ? 'Hide JSON' : 'View JSON' }}</span>
            </button>
          </div>
          <div v-if="showRawJson" class="raw-json-container">
            <textarea
              class="raw-json-textarea"
              readonly
              :value="qrPayloadString"
              rows="4"
              @focus="($event.target as HTMLTextAreaElement).select()"
            ></textarea>
          </div>
          <div class="modal-timer">
            <span>Expires in:</span>
            <CountdownTimer :expires-at="qrExpiresAt" @expired="showQrModal = false" />
          </div>
          <p class="modal-instruction">
            Open CrossNotify on your relay phone → <strong>Relay</strong> → <strong>Add relay channel</strong> → scan this code.
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.channels-view {
  max-width: 1000px;
  margin: 0 auto;
  padding: 2rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.75rem;
}

.header-section {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.server-meta {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.page-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary);
}

.public-url-badge code {
  color: var(--text-muted);
  font-size: 0.8125rem;
}

.btn-icon {
  width: 18px;
  height: 18px;
}

.channels-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
  gap: 1.25rem;
}

.empty-card {
  text-align: center;
  padding: 3rem 2rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
}

.empty-icon {
  font-size: 3rem;
}

.empty-title {
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--text-primary);
}

.empty-desc {
  max-width: 500px;
  font-size: 0.9375rem;
  color: var(--text-secondary);
}

.empty-steps {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  text-align: left;
  max-width: 480px;
  margin: 0.75rem 0 1rem;
  background-color: var(--bg-surface-elevated);
  padding: 1rem 1.25rem;
  border-radius: var(--radius-md);
  font-size: 0.875rem;
}

.empty-step {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
}

.step-num {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background-color: var(--primary);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.75rem;
  font-weight: 700;
  flex-shrink: 0;
}

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background-color: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 1rem;
}

.modal-card {
  background-color: var(--bg-surface);
  border-radius: var(--radius-lg);
  padding: 1.5rem;
  max-width: 380px;
  width: 100%;
  box-shadow: var(--shadow-lg);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
}

.modal-title {
  font-size: 1.125rem;
  font-weight: 700;
}

.close-btn {
  background: transparent;
  border: none;
  font-size: 1.25rem;
  cursor: pointer;
  color: var(--text-muted);
}

.modal-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  text-align: center;
}

.modal-timer {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
}

.modal-instruction {
  font-size: 0.8125rem;
  color: var(--text-secondary);
}

.payload-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  margin-top: 0.25rem;
}

.btn-icon {
  width: 14px;
  height: 14px;
}

.raw-json-container {
  width: 100%;
  max-width: 380px;
}

.raw-json-textarea {
  width: 100%;
  font-family: var(--font-mono);
  font-size: 0.75rem;
  padding: 0.5rem;
  border-radius: var(--radius-sm);
  background-color: var(--bg-surface-elevated);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
  resize: vertical;
}
</style>
