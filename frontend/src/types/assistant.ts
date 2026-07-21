import type { RouteLocationRaw } from 'vue-router';

export type AssistantMessageRole = 'assistant' | 'teacher' | 'system';
export type AssistantMessageStatus = 'pending' | 'streaming' | 'success' | 'error';
export type AssistantEvidenceSource =
  | 'PROJECT'
  | 'REQUIREMENT'
  | 'MATERIAL'
  | 'KNOWLEDGE'
  | 'INTENT'
  | 'ARTIFACT'
  | 'STUDENT_QUESTION';

export interface AssistantEvidence {
  id: string;
  label: string;
  value?: string;
  source: AssistantEvidenceSource;
  tone?: 'purple' | 'green' | 'orange' | 'blue' | 'gray' | 'red';
}

export interface AssistantWorkspaceAction {
  id: string;
  label: string;
  route?: RouteLocationRaw;
  tone: 'primary' | 'secondary' | 'success';
  actionType: 'NAVIGATE' | 'START_WORKFLOW' | 'CREATE_VERSION' | 'RETRY' | 'RETRY_SAVE';
  messageId?: string;
  disabled?: boolean;
  disabledReason?: string;
}

export interface AssistantResponseSection {
  id: string;
  title: string;
  content?: string;
  tone?: 'purple' | 'green' | 'orange' | 'blue' | 'gray' | 'red';
  items?: Array<{
    id: string;
    title: string;
    description?: string;
    status?: 'done' | 'pending' | 'warning' | 'failed';
    action?: AssistantWorkspaceAction;
  }>;
}

export interface AssistantMessage {
  id: string;
  role: AssistantMessageRole;
  content: string;
  createdAt: string;
  status: AssistantMessageStatus;
  persistenceStatus?: 'pending' | 'saved' | 'failed' | 'not_required';
  persistenceError?: string;
  persistRetryCount?: number;
  evidence?: AssistantEvidence[];
  actions?: AssistantWorkspaceAction[];
  sections?: AssistantResponseSection[];
  versionNotice?: string;
}

export interface AssistantSourceStatus {
  id: string;
  label: string;
  state: 'loaded' | 'empty' | 'loading' | 'error';
  detail?: string;
}

export interface AssistantProgressItem {
  id: string;
  label: string;
  value: string;
  tone: 'purple' | 'green' | 'orange' | 'blue' | 'gray' | 'red';
  route?: RouteLocationRaw;
}

export interface AssistantRecentWorkItem {
  id: string;
  title: string;
  time: string;
  route?: RouteLocationRaw;
  icon?: 'target' | 'document' | 'layers' | 'question-help';
}
