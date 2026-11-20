// Generates the attribution list for the JavaScript shipped inside ui.exe.
// Run: node windows/scripts/generate-frontend-license-notice.mjs
// Re-run after changing windows/frontend/package-lock.json; the output is checked in.
import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const windowsRoot = resolve(scriptDir, '..')
const frontendRoot = join(windowsRoot, 'frontend')
const outputPath = join(windowsRoot, 'deployment', 'THIRD_PARTY_FRONTEND.md')

const raw = execFileSync('npm', ['ls', '--omit=dev', '--all', '--json'], {
    cwd: frontendRoot,
    encoding: 'utf8',
    shell: process.platform === 'win32'
})

const packages = new Map()
const walk = (node, baseDir) => {
    for (const [name, info] of Object.entries(node.dependencies ?? {})) {
        const version = info.version ?? 'unknown'
        const key = `${name} ${version}`
        if (!packages.has(key)) {
            const location = info.resolvedPath ?? locate(name, baseDir)
            if (location) packages.set(key, { name, version, ...readMetadata(name, location) })
        }
        walk(info, baseDir)
    }
}

const locate = (name, baseDir) => {
    const candidates = [join(frontendRoot, 'node_modules', name)]
    for (let dir = baseDir; ; dir = dirname(dir)) {
        candidates.push(join(dir, 'node_modules', name))
        if (dir === dirname(dir)) break
    }
    return candidates.find(candidate => {
        try {
            readFileSync(join(candidate, 'package.json'))
            return true
        } catch {
            return false
        }
    }) ?? null
}

const readMetadata = (name, location) => {
    try {
        const manifest = JSON.parse(readFileSync(join(location, 'package.json'), 'utf8'))
        const repository = typeof manifest.repository === 'string'
            ? manifest.repository
            : manifest.repository?.url ?? ''
        return {
            license: manifest.license ?? 'NOASSERTION',
            repository: repository.replace(/^git\+/, '').replace(/\.git$/, '')
        }
    } catch {
        return { license: 'NOASSERTION', repository: '' }
    }
}

walk(JSON.parse(raw), frontendRoot)

const rows = [...packages.values()].sort((left, right) =>
    left.name.localeCompare(right.name) || left.version.localeCompare(right.version))

const licenses = new Map()
for (const row of rows) licenses.set(row.license, (licenses.get(row.license) ?? 0) + 1)
const summary = [...licenses.entries()].sort((left, right) => right[1] - left[1])

const lines = [
    '# Third-Party JavaScript (ZeroCon settings UI)',
    '',
    'Generated from the production dependency tree of `windows/frontend` by',
    '`windows/scripts/generate-frontend-license-notice.mjs`. These packages are bundled into `ui.exe`.',
    '',
    'Do not hand-edit. Regenerate after changing `windows/frontend/package-lock.json`.',
    '',
    '## License summary',
    '',
    '| License | Packages |',
    '| --- | ---: |',
    ...summary.map(([license, count]) => `| ${license} | ${count} |`),
    '',
    '## Packages',
    '',
    '| Package | License | Upstream |',
    '| --- | --- | --- |',
    ...rows.map(row => `| ${row.name}@${row.version} | ${row.license} | ${row.repository} |`),
    ''
]

writeFileSync(outputPath, lines.join('\n'), 'utf8')
console.log(`Wrote ${outputPath} (${rows.length} packages)`)
