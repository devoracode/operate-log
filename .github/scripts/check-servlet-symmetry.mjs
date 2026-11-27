import {readdirSync, readFileSync} from 'node:fs';
import {dirname, join, relative} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const servletPackage = 'io.github.devoracode.operatelog.boot.servlet';
const servletDir = join(root, 'operate-log-spring-boot-starter/src/main/java', ...servletPackage.split('.'));

function loadStack(namespace, prefix) {
    const directory = join(servletDir, namespace);
    const sources = new Map();
    const typePrefix = new RegExp(`\\b${prefix}(?=[A-Z])`, 'g');

    function visit(dir) {
        for (const entry of readdirSync(dir, {withFileTypes: true})) {
            const path = join(dir, entry.name);
            if (entry.isDirectory()) {
                visit(path);
            } else if (entry.isFile() && entry.name.endsWith('.java')) {
                const name = entry.name.startsWith(prefix) ? entry.name.slice(prefix.length) : entry.name;
                const key = join(relative(directory, dir), name);
                if (sources.has(key)) {
                    throw new Error(`Duplicate adapter name in ${namespace}: ${key}`);
                }
                // 仅忽略栈的包名、Servlet API 命名空间和类名前缀；其余文本逐字比较。
                const normalized = readFileSync(path, 'utf8')
                    .replaceAll('\r\n', '\n')
                    .replaceAll(`${servletPackage}.${namespace}`, `${servletPackage}.stack`)
                    .replaceAll(`${namespace}.servlet.`, 'stack.servlet.')
                    .replace(typePrefix, 'Stack');
                sources.set(key, {path, normalized});
            }
        }
    }

    visit(directory);
    return sources;
}

const javax = loadStack('javax', 'Javax');
const jakarta = loadStack('jakarta', 'Jakarta');
const keys = [...new Set([...javax.keys(), ...jakarta.keys()])].sort();
if (keys.length === 0) {
    throw new Error(`No Servlet adapters found in ${relative(root, servletDir)}`);
}

for (const key of keys) {
    const left = javax.get(key);
    const right = jakarta.get(key);
    if (!left || !right) {
        const file = left ?? right;
        console.error(`${relative(root, file.path)}: missing ${left ? 'jakarta' : 'javax'} counterpart`);
        process.exitCode = 1;
    } else if (left.normalized !== right.normalized) {
        const leftLines = left.normalized.split('\n');
        const rightLines = right.normalized.split('\n');
        let index = 0;
        while (leftLines[index] === rightLines[index]) {
            index++;
        }
        console.error(`Servlet adapters differ: ${relative(root, left.path)}:${index + 1}`
            + ` and ${relative(root, right.path)}:${index + 1}`);
        process.exitCode = 1;
    }
}

if (!process.exitCode) {
    console.log(`Servlet adapter symmetry passed (${keys.length} pairs).`);
}
