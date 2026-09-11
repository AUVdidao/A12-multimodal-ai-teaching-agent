export interface ComposerFile {
  name: string;
  file?: File;
}

export const LESSONFORGE_COMPOSER_MIN_HEIGHT = 55;
export const LESSONFORGE_COMPOSER_MAX_HEIGHT = LESSONFORGE_COMPOSER_MIN_HEIGHT;

export function resizeComposerTextarea(
  textarea: HTMLTextAreaElement,
  minHeight = LESSONFORGE_COMPOSER_MIN_HEIGHT,
  maxHeight = LESSONFORGE_COMPOSER_MAX_HEIGHT,
) {
  const fixedHeight = Math.max(minHeight, maxHeight);
  textarea.style.height = `${fixedHeight}px`;
  textarea.style.overflowY = 'auto';
}

export function composerFileIdentity(item: ComposerFile) {
  if (!item.file) return `name:${item.name}`;
  return [item.name, item.file.size, item.file.lastModified, item.file.type].join('\u0000');
}

export function mergeComposerFiles(existing: ComposerFile[], incoming: ComposerFile[]) {
  const merged: ComposerFile[] = [];
  const identities = new Set<string>();
  for (const item of [...existing, ...incoming]) {
    const identity = composerFileIdentity(item);
    if (identities.has(identity)) continue;
    identities.add(identity);
    merged.push(item);
  }
  return merged;
}
