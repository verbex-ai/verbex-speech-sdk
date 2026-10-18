import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

// npm cannot install a git dependency from a subdirectory, so `npm install github:verbex-ai/verbex-speech-sdk`
// installs the repository root. The root package.json is that package: it builds node/ in `prepare`
// and points at node/dist. It must describe the same package as node/package.json.
const here = dirname(fileURLToPath(import.meta.url));
const read = (path: string) => JSON.parse(readFileSync(path, "utf8"));
const node = read(join(here, "..", "package.json"));
const root = read(join(here, "..", "..", "package.json"));

/** node/package.json's paths, as seen from the repository root. */
function fromRoot(value: unknown): unknown {
  if (typeof value === "string") return value.startsWith("./") ? `./node/${value.slice(2)}` : value;
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, fromRoot(v)]));
  }
  return value;
}

describe("package manifests", () => {
  it("node/package.json builds on prepare, so a checkout or a path install has dist/", () => {
    expect(node.scripts.prepare).toBe("npm run build");
  });

  it("the root manifest is the same package as node/", () => {
    for (const field of ["name", "version", "description", "type", "engines", "license", "dependencies"]) {
      expect(root[field], field).toEqual(node[field]);
    }
  });

  it("the root manifest exports node/dist under the same entry points", () => {
    expect(root.main).toBe(fromRoot(node.main));
    expect(root.types).toBe(fromRoot(node.types));
    expect(root.exports).toEqual(fromRoot(node.exports));
  });

  it("the root manifest packs only the Node.js build and its README", () => {
    expect(root.files).toEqual(["node/dist", "node/README.md"]);
  });

  it("the root manifest builds node/ from its own lockfile on prepare", () => {
    expect(root.scripts.prepare).toBe("npm ci --prefix node --ignore-scripts && npm run build --prefix node");
  });

  it("the root manifest needs no dev dependencies of its own (prepare installs node/'s)", () => {
    expect(root.devDependencies).toBeUndefined();
  });

  // node/.gitignore lists dist/, and npm applies a nested .gitignore when packing even when `files`
  // names the folder: without node/.npmignore the package installed from GitHub has no code in it.
  it("packing the repository root includes node/dist and nothing else of the repository", () => {
    if (!existsSync(join(here, "..", "dist", "index.js"))) throw new Error("Run `npm run build` first.");
    const out = execFileSync("npm", ["pack", "--dry-run", "--json", "--ignore-scripts"], { cwd: join(here, "..", ".."), encoding: "utf8" });
    const packed = JSON.parse(out); // an array up to npm 11, an object keyed by package name from npm 12
    const entry = Array.isArray(packed) ? packed[0] : Object.values(packed)[0];
    const paths: string[] = (entry as { files: Array<{ path: string }> }).files.map((f) => f.path);
    expect(paths).toEqual(expect.arrayContaining(["node/dist/index.js", "node/dist/index.d.ts", "node/dist/listen/index.js", "node/dist/speak/index.js"]));
    expect(paths.filter((p) => !p.startsWith("node/dist/")).sort()).toEqual(["README.md", "node/README.md", "package.json"]);
  });
});
