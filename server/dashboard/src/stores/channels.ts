import { defineStore } from 'pinia';
import { ref } from 'vue';
import { apiRequest } from '../api/client.js';

export interface Channel {
  channelId: string;
  name: string;
  color: string | null;
  status: 'pending' | 'active' | 'revoked';
  relayDeviceName: string | null;
  relayDeviceModel: string | null;
  subscriberCount: number;
  storedNotifications: number;
  createdAt: number;
  activatedAt: number | null;
  lastSeenAt: number | null;
  pendingExpiresAt: number | null;
}

export interface Subscriber {
  subscriberId: string;
  deviceName: string | null;
  deviceModel: string | null;
  deliveryMode: 'battery_saver' | 'immediate_preview';
  status: 'pending' | 'active' | 'revoked';
  createdAt: number;
  activatedAt: number | null;
  lastSeenAt: number | null;
}

export interface ChannelDetail extends Channel {
  relayOsVersion: string | null;
  relayAppVersion: string | null;
  subscribers: Subscriber[];
}

export interface RelayProvisioningResponse {
  channelId: string;
  expiresAt: number;
  qrPayload: any;
}

export const useChannelsStore = defineStore('channels', () => {
  const channels = ref<Channel[]>([]);
  const currentChannel = ref<ChannelDetail | null>(null);
  const isLoading = ref<boolean>(false);

  async function fetchChannels() {
    isLoading.value = true;
    try {
      channels.value = await apiRequest<Channel[]>('/admin/api/channels');
      return channels.value;
    } finally {
      isLoading.value = false;
    }
  }

  async function fetchChannel(id: string): Promise<ChannelDetail> {
    const data = await apiRequest<ChannelDetail>(`/admin/api/channels/${id}`);
    currentChannel.value = data;
    return data;
  }

  async function createRelay(payload: { name: string; color?: string }): Promise<RelayProvisioningResponse> {
    return apiRequest<RelayProvisioningResponse>('/admin/api/relays', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  }

  async function regenerateRelay(channelId: string): Promise<RelayProvisioningResponse> {
    return apiRequest<RelayProvisioningResponse>(`/admin/api/relays/${channelId}/regenerate`, {
      method: 'POST',
    });
  }

  async function updateChannel(channelId: string, updates: { name?: string; color?: string }) {
    const updated = await apiRequest<Channel>(`/admin/api/channels/${channelId}`, {
      method: 'PATCH',
      body: JSON.stringify(updates),
    });
    if (currentChannel.value && currentChannel.value.channelId === channelId) {
      currentChannel.value.name = updated.name;
      currentChannel.value.color = updated.color;
    }
    await fetchChannels();
    return updated;
  }

  async function revokeChannel(channelId: string) {
    await apiRequest(`/admin/api/channels/${channelId}`, { method: 'DELETE' });
    await fetchChannels();
  }

  async function revokeSubscriber(channelId: string, subscriberId: string) {
    await apiRequest(`/admin/api/channels/${channelId}/subscribers/${subscriberId}`, {
      method: 'DELETE',
    });
    if (currentChannel.value && currentChannel.value.channelId === channelId) {
      currentChannel.value.subscribers = currentChannel.value.subscribers.filter(
        (s) => s.subscriberId !== subscriberId
      );
      currentChannel.value.subscriberCount = Math.max(0, currentChannel.value.subscriberCount - 1);
    }
    await fetchChannels();
  }

  return {
    channels,
    currentChannel,
    isLoading,
    fetchChannels,
    fetchChannel,
    createRelay,
    regenerateRelay,
    updateChannel,
    revokeChannel,
    revokeSubscriber,
  };
});
