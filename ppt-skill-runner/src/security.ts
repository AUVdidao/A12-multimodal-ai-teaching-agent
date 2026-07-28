import fs from "node:fs/promises";
import path from "node:path";
import { ALLOWED_PRESETS, AllowedPreset } from "./types";
import { RunnerError } from "./errors";

const FORBIDDEN_URI = /^(?:file|https?):\/\//i;
const ASSET_KEYS = new Set(["hero_image", "image", "diagram", "mermaid_source", "logo"]);

export function validatePreset(value: string): asserts value is AllowedPreset {
  if (!(ALLOWED_PRESETS as readonly string[]).includes(value)) {
    throw new RunnerError("UNSUPPORTED_PRESET", `Unsupported style preset: ${value}`, 400);
  }
}

export function validateOutlineSecurity(value: unknown): void {
  walk(value, "$");
}

function walk(value: unknown, pointer: string, key?: string): void {
  if (typeof value === "string") {
    if (FORBIDDEN_URI.test(value.trim())) {
      throw new RunnerError("EXTERNAL_URI_FORBIDDEN", `External URI is forbidden at ${pointer}`, 400);
    }
    if (key && ASSET_KEYS.has(key)) validateRelativeAssetPath(value, pointer);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => walk(item, `${pointer}[${index}]`));
    return;
  }
  if (value && typeof value === "object") {
    for (const [childKey, childValue] of Object.entries(value)) {
      if (childKey === "generated_image" || childKey === "generated-image") {
        throw new RunnerError("GENERATED_IMAGE_FORBIDDEN", "Phase 1 does not allow generated-image assets", 400);
      }
      walk(childValue, `${pointer}.${childKey}`, childKey);
    }
  }
}

function validateRelativeAssetPath(value: string, pointer: string): void {
  const normalized = value.replace(/\\/g, "/");
  if (path.isAbsolute(value) || /^[A-Za-z]:/.test(value) || normalized.split("/").includes("..")) {
    throw new RunnerError("ASSET_PATH_FORBIDDEN", `Unsafe asset path at ${pointer}`, 400);
  }
}

export async function ensureDirectory(directory: string): Promise<string> {
  await fs.mkdir(directory, { recursive: true });
  return fs.realpath(directory);
}

export function assertPathInside(root: string, candidate: string): void {
  const relative = path.relative(root, candidate);
  if (relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative))) return;
  throw new RunnerError("PATH_TRAVERSAL", `Path escapes controlled root: ${candidate}`, 400);
}

export async function assertNoSymlinks(root: string): Promise<void> {
  const rootReal = await fs.realpath(root);
  await inspect(root, rootReal);
}

async function inspect(current: string, rootReal: string): Promise<void> {
  const entries = await fs.readdir(current, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(current, entry.name);
    const stat = await fs.lstat(fullPath);
    if (stat.isSymbolicLink()) {
      throw new RunnerError("SYMLINK_FORBIDDEN", `Symbolic link is forbidden: ${fullPath}`, 400);
    }
    const real = await fs.realpath(fullPath);
    assertPathInside(rootReal, real);
    if (stat.isDirectory()) await inspect(fullPath, rootReal);
  }
}
