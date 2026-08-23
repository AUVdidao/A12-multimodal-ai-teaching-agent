import type { ArtifactType } from '@/api/generation';

export function canReviseArtifact(type: ArtifactType | string | null | undefined): boolean {
  return type === 'DOCX' || type === 'INTERACTION';
}
