import fs from "node:fs/promises";
import path from "node:path";
import { HarnessError, TemplateSpec } from "./domain.js";

export class TemplateRegistry {
  // Keep template lookup explicit so the same image layout works in local Node and Docker.
  private readonly root = path.resolve(process.env.PPT_HARNESS_TEMPLATE_ROOT || path.join(process.cwd(), "templates"));
  private readonly versions = new Map([
    ["a12-teaching-generic", "1.0.0"],
    ["a12-editorial-grid", "1.0.0"]
  ]);
  async list(): Promise<TemplateSpec[]> {
    return Promise.all(Array.from(this.versions, ([templateId, version]) => this.get(templateId, version)));
  }
  async get(templateId: string, version: string): Promise<TemplateSpec> {
    if (this.versions.get(templateId) !== version) throw new HarnessError("TEMPLATE_NOT_FOUND", "Requested presentation template is not available", 404);
    const file = path.join(this.root, templateId, "template.json");
    return JSON.parse(await fs.readFile(file, "utf8")) as TemplateSpec;
  }
}
