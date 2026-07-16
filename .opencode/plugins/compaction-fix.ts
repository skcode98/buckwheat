import type { Plugin } from "@opencode-ai/plugin";

const trackDir = (worktree: string) => `${worktree}/.track`;

async function readFile(path: string): Promise<string> {
  try { return await Bun.file(path).text(); } catch { return ""; }
}

function extractSection(text: string, heading: string): string {
  const regex = new RegExp(`## ${heading}([\\s\\S]*?)(?=## |$)`);
  const match = text.match(regex);
  return match ? match[1].trim() : "";
}

export const BuckwheatCompactionFix: Plugin = async ({ project, client, $, directory, worktree }) => {
  return {
    "experimental.session.compacting": async (_input, output) => {
      const td = trackDir(worktree);
      const [memory, changelog, cache] = await Promise.all([
        readFile(`${td}/MEMORY.md`),
        readFile(`${td}/CHANGELOG.md`),
        readFile(`${td}/CACHE.md`),
      ]);

      let state: Record<string, unknown> = {};
      try {
        state = JSON.parse(await readFile(`${td}/.session-state.json`));
      } catch { /* no state file yet */ }

      const activeSection = extractSection(memory, "Active");
      const decisionsSection = extractSection(memory, "Architecture Decisions");
      const cacheBuildSection = extractSection(cache, "Build Commands");
      const cachePathsSection = extractSection(cache, "Key File Paths");

      const context = [
        "## Preserved Session State (pre-compaction snapshot)",
        "",
        "### Active Task Context",
        activeSection || "See MEMORY.md",
        "",
        "### Recent Changes",
        changelog.slice(0, 1500) || "No recent changes recorded",
        "",
        "### Decisions Made",
        decisionsSection || "See MEMORY.md",
        "",
        "### Next Move (from previous session)",
        (state.nextMove as string) || "Continue with current feature work \u2014 check MEMORY.md",
        "",
        "### Files Being Worked On",
        (Array.isArray(state.files) ? (state.files as string[]).join(", ") : "") || "See CACHE.md",
        "",
        "### Build Commands",
        cacheBuildSection || ".\\gradlew.bat assembleDebug",
        "",
        "### Key File Paths",
        cachePathsSection || "See CACHE.md",
        "",
        "### Immediate Recovery Steps",
        "1. Read .track/AGENTS.md (project guide)",
        "2. Read .track/MEMORY.md (full context & decisions)",
        "3. Read .track/CHANGELOG.md (what changed)",
        "4. Read .track/CACHE.md (build commands & references)",
        "5. Run .\\gradlew.bat assembleDebug after any changes",
      ].join("\n");

      output.context.push(context);
    },
  };
};
