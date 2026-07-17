import { getAiGatewayStatus, type AiGatewayStatus } from '@/api/aiAssistant';
import { describeAiProvider } from '@/utils/aiProvider';
import { computed, ref } from 'vue';

export function useAiGatewayStatus() {
  const status = ref<AiGatewayStatus>();
  const loading = ref(false);
  const error = ref('');
  const presentation = computed(() => describeAiProvider(status.value, loading.value, error.value));

  async function refresh() {
    loading.value = true;
    error.value = '';
    try {
      status.value = await getAiGatewayStatus();
    } catch {
      error.value = '无法读取 AI Provider 状态，请检查后端服务后重试。';
    } finally {
      loading.value = false;
    }
  }

  return { status, loading, error, presentation, refresh };
}
