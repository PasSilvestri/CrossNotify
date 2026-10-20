export interface ApiError {
  error: string;
  detail?: string;
  message?: string;
  state?: string;
  channels?: number;
  status: number;
}

const ERROR_MESSAGES: Record<string, string> = {
  invalid_state: 'The server is in a different setup phase. Please refresh the page.',
  invalid_setup_token: 'The setup token is incorrect. Find the token printed in your server logs.',
  invalid_credentials: 'The username or password you entered is incorrect.',
  invalid_service_account: 'The uploaded file is not a valid Google service account JSON.',
  invalid_client_config: 'The uploaded file is not a valid google-services.json.',
  firebase_credential_rejected: 'Firebase rejected the service account. Ensure Firebase Cloud Messaging API (v1) is enabled in Google Cloud Console.',
  package_not_registered: 'Package name "com.crossnotify.app" was not found in your google-services.json. Please register this package in Firebase Console.',
  project_mismatch: 'The project IDs in serviceAccountKey.json and google-services.json do not match.',
  project_change_requires_reset: 'Switching to a different Firebase project will revoke active channels and require all phones to pair again.',
  public_url_required: 'A Public Server URL is required. Please set it in Settings first.',
  channel_not_found: 'Channel not found.',
  subscriber_not_found: 'Subscriber not found.',
  provisioning_expired: 'The provisioning QR code has expired. Generate a new one.',
  too_many_requests: 'Too many requests. Please wait a moment and try again.',
  unsupported_media_type: 'Request must have Content-Type: application/json.',
  invalid_origin: 'Cross-site request blocked by origin check.',
};

export function formatApiError(err: any): string {
  if (typeof err === 'string') return err;
  if (!err) return 'An unexpected error occurred';

  const code = err.error || err.code;
  if (code && ERROR_MESSAGES[code]) {
    if (err.channels !== undefined) {
      return `${ERROR_MESSAGES[code]} (${err.channels} channel${err.channels === 1 ? '' : 's'})`;
    }
    return ERROR_MESSAGES[code];
  }

  return err.detail || err.message || 'An unexpected error occurred';
}

export async function apiRequest<T = any>(
  endpoint: string,
  options: RequestInit = {}
): Promise<T> {
  const headers = new Headers(options.headers || {});

  if (options.body && typeof options.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(endpoint, {
    ...options,
    headers,
    credentials: 'same-origin',
  });

  const contentType = res.headers.get('content-type') || '';
  const isJson = contentType.includes('application/json');

  let data: any = null;
  if (isJson) {
    try {
      data = await res.json();
    } catch {
      data = null;
    }
  } else {
    data = await res.text();
  }

  if (!res.ok) {
    const errorObj: ApiError = {
      error: data?.error || 'http_error',
      detail: data?.detail,
      message: data?.message || (typeof data === 'string' ? data : res.statusText),
      state: data?.state,
      channels: data?.channels,
      status: res.status,
    };
    throw errorObj;
  }

  return data as T;
}
