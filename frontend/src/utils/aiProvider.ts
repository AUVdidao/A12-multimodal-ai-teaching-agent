import type { AiGatewayStatus } from '@/api/aiAssistant';

export type AiProviderTone = 'success' | 'warning' | 'danger' | 'info';

export interface AiProviderPresentation {
  label: string;
  summary: string;
  tone: AiProviderTone;
  unavailable: boolean;
  mockActive: boolean;
  fallbackActive: boolean;
  requestedLabel: string;
  diagnostic: string;
}

export function describeAiProvider(
  status?: AiGatewayStatus,
  loading = false,
  error = '',
): AiProviderPresentation {
  if (loading && !status) {
    return presentation('正在读取 AI 状态', '正在确认 Kimi 与 Mock 工作流的运行状态。', 'info');
  }

  if (error && !status) {
    return presentation('AI 状态读取失败', '暂时无法确认运行状态，业务请求仍会由后端进行最终校验。', 'danger', false, false, false, '未知', error);
  }

  if (!status) {
    return presentation('AI 状态待确认', '尚未取得后端工作流状态。', 'info');
  }

  const active = normalize(status.activeProvider);
  const requested = normalize(status.requestedProvider);
  const requestedLabel = providerName(requested);
  const diagnostic = status.message || '';

  if (active === 'UNAVAILABLE') {
    return presentation(
      'AI 工作流不可用',
      'Kimi 尚未完成服务端配置，且当前没有可用的 Mock 回退。',
      'danger',
      true,
      false,
      false,
      requestedLabel,
      diagnostic,
    );
  }

  if (active.includes('MOCK')) {
    const fallbackActive = requested === 'KIMI';
    return presentation(
      fallbackActive ? 'Mock 降级中' : 'Mock AI',
      fallbackActive
        ? '目标 Provider 为 Kimi，但最近一次调用已由 Mock 保底完成。'
        : '当前使用本地 Mock 工作流，不会调用外部模型服务。',
      'warning',
      false,
      true,
      fallbackActive,
      requestedLabel,
      diagnostic,
    );
  }

  if (active === 'KIMI') {
    return presentation(
      'Kimi AI',
      status.providerConfigured
        ? '当前结构化教学工作流由 Spring Boot 直接调用 Kimi 执行。'
        : '后端选择了 Kimi，但服务端配置尚未完成。',
      status.providerConfigured ? 'success' : 'warning',
      false,
      false,
      false,
      requestedLabel,
      diagnostic,
    );
  }

  return presentation(
    status.activeProvider || '未知 Provider',
    '后端返回了未识别的 Provider 状态，请通过诊断信息确认配置。',
    'info',
    false,
    false,
    false,
    requestedLabel,
    diagnostic,
  );
}

function presentation(
  label: string,
  summary: string,
  tone: AiProviderTone,
  unavailable = false,
  mockActive = false,
  fallbackActive = false,
  requestedLabel = '未知',
  diagnostic = '',
): AiProviderPresentation {
  return { label, summary, tone, unavailable, mockActive, fallbackActive, requestedLabel, diagnostic };
}

function normalize(value?: string) {
  return (value || '').trim().toUpperCase();
}

function providerName(value: string) {
  if (value === 'KIMI') return 'Kimi';
  if (value === 'MOCK') return 'Mock';
  return value || '未知';
}
