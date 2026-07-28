import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { HarnessError } from "./domain.js";

export class ControlledArtifactStore {
  private readonly root: string;
  constructor(root = process.env.PPT_HARNESS_ARTIFACT_ROOT || path.join(process.cwd(), "data", "artifacts")) { this.root = path.resolve(root); }
  async save(jobId: string, fileName: string, content: Uint8Array): Promise<{ sizeBytes: number; sha256: string }> {
    if (!/^[0-9a-f-]{36}$/i.test(jobId) || fileName !== "presentation.pptx") throw new HarnessError("ARTIFACT_INVALID", "Invalid controlled artifact reference", 500);
    const directory = path.resolve(this.root, jobId);
    if (!directory.startsWith(`${this.root}${path.sep}`)) throw new HarnessError("ARTIFACT_INVALID", "Artifact directory is outside controlled storage", 500);
    await fs.mkdir(directory, { recursive: true });
    const output = path.join(directory, fileName);
    await fs.writeFile(output, content, { flag: "w" });
    const stat = await fs.lstat(output);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size === 0) throw new HarnessError("PPT_EMPTY_FILE", "Saved presentation artifact is invalid", 502);
    return { sizeBytes: stat.size, sha256: crypto.createHash("sha256").update(content).digest("hex") };
  }
  async read(jobId: string): Promise<Uint8Array> {
    const file = path.resolve(this.root, jobId, "presentation.pptx");
    if (!file.startsWith(`${this.root}${path.sep}`)) throw new HarnessError("ARTIFACT_NOT_FOUND", "Presentation artifact is unavailable", 404);
    const stat = await fs.lstat(file).catch(() => undefined);
    if (!stat?.isFile() || stat.isSymbolicLink() || stat.size === 0) throw new HarnessError("ARTIFACT_NOT_FOUND", "Presentation artifact is unavailable", 404);
    return new Uint8Array(await fs.readFile(file));
  }
}
