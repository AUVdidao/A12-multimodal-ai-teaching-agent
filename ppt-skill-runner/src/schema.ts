import fs from "node:fs";
import path from "node:path";
import Ajv2020, { ErrorObject, ValidateFunction } from "ajv/dist/2020";
import addFormats from "ajv-formats";
import { RunnerError } from "./errors";

let validator: ValidateFunction | undefined;

export function validateOutline(outline: unknown): asserts outline is Record<string, unknown> {
  const validate = validator ??= buildValidator();
  if (!validate(outline)) {
    throw new RunnerError("INVALID_OUTLINE", "Outline does not satisfy the Phase-1 schema", 400, formatErrors(validate.errors));
  }
}

function buildValidator(): ValidateFunction {
  const schemaPath = path.resolve(__dirname, "..", "..", "schema", "phase-1-outline.schema.json");
  const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
  const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
  addFormats(ajv);
  return ajv.compile(schema);
}

function formatErrors(errors: ErrorObject[] | null | undefined): string[] {
  return (errors ?? []).map((error) => `${error.instancePath || "/"} ${error.message ?? "is invalid"}`);
}
