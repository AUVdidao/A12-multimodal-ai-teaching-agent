import { spawn } from "node:child_process";
import { CommandExecutor, CommandResult, CommandSpec } from "./types";

const MAX_CAPTURE_BYTES = 512 * 1024;

export const executeCommand: CommandExecutor = (spec: CommandSpec) => new Promise<CommandResult>((resolve, reject) => {
  const child = spawn(spec.command, spec.args, {
    cwd: spec.cwd,
    shell: false,
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"]
  });
  let stdout = "";
  let stderr = "";
  const timer = setTimeout(() => {
    child.kill("SIGKILL");
    reject(new Error(`Command timed out after ${spec.timeoutMs}ms`));
  }, spec.timeoutMs);
  child.stdout.on("data", (chunk: Buffer) => { stdout = appendLimited(stdout, chunk); });
  child.stderr.on("data", (chunk: Buffer) => { stderr = appendLimited(stderr, chunk); });
  child.on("error", (error) => { clearTimeout(timer); reject(error); });
  child.on("close", (code) => {
    clearTimeout(timer);
    resolve({ exitCode: code ?? -1, stdout, stderr });
  });
});

function appendLimited(current: string, chunk: Buffer): string {
  if (Buffer.byteLength(current) >= MAX_CAPTURE_BYTES) return current;
  return `${current}${chunk.toString("utf8")}`.slice(0, MAX_CAPTURE_BYTES);
}
