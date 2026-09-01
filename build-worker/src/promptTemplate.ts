import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

/** Loads the build-task prompt Mustache template - a `PROMPT_TEMPLATE_DIR` override (see
 * config.ts) takes precedence, one file at a time, falling back to the bundled `templates/`
 * default when the override dir has no `build-task.mustache` (same per-file fallback granularity
 * as the Java agents' `prompts_dir`, see agents/src/main/java/ai/pdlc/agents/templates/PromptTemplates.java).
 * `__dirname` resolution works both from `dist/` in-repo and from the npm-pack tarball (`templates`
 * is a sibling of `dist`, see package.json's `files`). A missing bundled file is a build-worker
 * install bug, so it throws the raw `ENOENT` rather than a friendlier wrapper. */
export function loadBuildTaskTemplate(dir?: string): string {
  if (dir) {
    const override = path.join(dir, 'build-task.mustache');
    if (existsSync(override)) {
      return readFileSync(override, 'utf8');
    }
  }
  return readFileSync(path.resolve(__dirname, '../templates/build-task.mustache'), 'utf8');
}
