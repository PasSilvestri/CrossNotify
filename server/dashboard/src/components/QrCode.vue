<script setup lang="ts">
import { ref, watch, onMounted } from 'vue';
import QRCode from 'qrcode';

const props = withDefaults(
  defineProps<{
    value: string;
    size?: number;
    ariaLabel?: string;
  }>(),
  {
    size: 240,
    ariaLabel: 'QR Code',
  }
);

const canvasRef = ref<HTMLCanvasElement | null>(null);
const renderError = ref<string | null>(null);

async function renderQr() {
  if (!canvasRef.value || !props.value) return;
  try {
    renderError.value = null;
    await QRCode.toCanvas(canvasRef.value, props.value, {
      width: props.size,
      margin: 2,
      errorCorrectionLevel: 'M',
      color: {
        dark: '#000000',
        light: '#ffffff',
      },
    });
  } catch (err: any) {
    console.error('Failed to render QR code:', err);
    renderError.value = err.message || 'Error generating QR code';
  }
}

watch(() => props.value, renderQr);
onMounted(renderQr);
</script>

<template>
  <div class="qr-container">
    <canvas
      ref="canvasRef"
      :aria-label="ariaLabel"
      role="img"
      class="qr-canvas"
    ></canvas>
    <div v-if="renderError" class="qr-error">
      {{ renderError }}
    </div>
  </div>
</template>

<style scoped>
.qr-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  background-color: #ffffff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  width: fit-content;
  margin: 0 auto;
}

.qr-canvas {
  display: block;
  max-width: 100%;
  height: auto;
  border-radius: var(--radius-sm);
}

.qr-error {
  margin-top: 0.5rem;
  font-size: 0.75rem;
  color: var(--danger);
}
</style>
