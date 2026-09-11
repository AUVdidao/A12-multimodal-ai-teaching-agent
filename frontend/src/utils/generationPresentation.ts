export type GenerationSourceState = 'loaded' | 'empty' | 'loading' | 'error' | undefined;

export interface GenerationPresentationInput {
  contextLoading: boolean;
  sourceState: GenerationSourceState;
  hasWorkspace: boolean;
  hasArtifacts: boolean;
  hasPlan: boolean;
  planConfirmed: boolean;
}

export function generationStatusFor(input: GenerationPresentationInput) {
  if (input.contextLoading) return '读取中';
  if (input.sourceState === 'error') return '读取失败';
  if (!input.hasWorkspace) return '待同步';
  if (input.hasArtifacts) return '已有成果';
  if (input.planConfirmed) return '方案已确认';
  if (input.hasPlan) return '方案待确认';
  return '待生成';
}

export function generationToneFor(input: GenerationPresentationInput): 'pending' | 'ready' | 'error' {
  if (input.sourceState === 'error') return 'error';
  return input.hasArtifacts ? 'ready' : 'pending';
}
