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
    return presentation('正在读取 AI 状态', '正在确认 Dify 与 Mock 工作流的运行状态。', 'info');
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
      'Dify 尚未达到可调用条件，且当前没有可用的 Mock 回退。',
      'danger',
      true,
      false,
      false,
      requestedLabel,
      diagnostic,
    );
  }

  if (active.includes('MOCK')) {
    const fallbackActive = requested === 'DIFY';
    return presentation(
      fallbackActive ? 'Mock 降级中' : 'Mock AI',
      fallbackActive
        ? '目标 Provider 为 Dify，但最近一次调用已由 Mock 保底完成。'
        : '当前使用本地 Mock 工作流，不会调用外部 Dify 服务。',
      'warning',
      false,
      true,
      fallbackActive,
      requestedLabel,
      diagnostic,
    );
  }

  if (active.startsWith('DIFY')) {
    const partial = active.includes('PARTIAL');
    return presentation(
      partial ? 'Dify 部分可用' : 'Dify AI',
      partial
        ? '只有部分工作流已配置发布应用，未配置流程将按后端策略处理。'
        : '当前工作流由服务端配置的 Dify 应用执行。',
      partial ? 'warning' : 'success',
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
  if (value === 'DIFY') return 'Dify';
  if (value === 'MOCK') return 'Mock';
  return value || '未知';
}
