const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const staticDir = path.join(__dirname, '../../main/resources/static');
const css = fs.readFileSync(path.join(staticDir, 'style.css'), 'utf8');

// Parse balanced rule/declaration blocks, including nested media/keyframe rules.
// This validates stylesheet structure, not browser layout or paint performance.
function parseRules(source) {
    source = source.replace(/\/\*[\s\S]*?\*\//g, '');
    const tokens = source.match(/\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^{}"']+|[{}]/g) || [];
    const rules = [];
    const stack = [];
    let text = '';
    for (const token of tokens) {
        if (token.startsWith('/*')) continue;
        if (token === '{') {
            const selector = text.trim();
            assert.ok(selector, 'Rule must have a selector');
            stack.push({selector, scope: stack.map(rule => rule.selector)});
            text = '';
        } else if (token === '}') {
            assert.ok(stack.length, 'Unexpected closing brace');
            const rule = stack.pop();
            if (!rule.selector.startsWith('@media') && !rule.selector.includes('keyframes')) {
                rule.declarations = new Map();
                for (const declaration of text.split(';')) {
                    if (!declaration.trim()) continue;
                    const colon = declaration.indexOf(':');
                    assert.ok(colon > 0, `Invalid declaration in ${rule.selector}: ${declaration}`);
                    rule.declarations.set(declaration.slice(0, colon).trim(), declaration.slice(colon + 1).trim());
                }
                rules.push(rule);
            }
            text = '';
        } else {
            text += token;
        }
    }
    assert.equal(stack.length, 0, 'Unclosed stylesheet block');
    assert.equal(text.trim(), '', 'Unexpected trailing CSS');
    return rules;
}

const rules = parseRules(css);
const exact = selector => rules.filter(rule => rule.selector === selector && !rule.scope.length);

test('styles parse, theme variables resolve, hidden modals retain priority', () => {
    const variables = exact(':root')[0].declarations;
    for (const match of css.matchAll(/var\((--[\w-]+)\)/g)) assert.ok(variables.has(match[1]));
    assert.equal(exact('.hidden')[0].declarations.get('display'), 'none !important');
    assert.equal(exact('#propertiesModal .properties-content')[0].declarations.get('max-height'), '80vh');
    assert.equal(exact('#propertiesModal .properties-content')[0].declarations.get('position'), 'absolute');
    assert.equal(exact('#confirmMoveBtn').length, 1);
});

test('reduced-motion preference disables animation and transitions', () => {
    const reduced = rules.find(rule => rule.scope.includes('@media (prefers-reduced-motion: reduce)'));
    assert.ok(reduced);
    assert.equal(reduced.declarations.get('animation'), 'none !important');
    assert.equal(reduced.declarations.get('transition'), 'none !important');
    assert.equal(reduced.declarations.get('scroll-behavior'), 'auto !important');
});

test('private/public pages allow browser zoom and retain gallery/viewer hooks', () => {
    for (const name of ['index.html', 'share.html']) {
        const html = fs.readFileSync(path.join(staticDir, name), 'utf8');
        assert.match(html, /name="viewport" content="width=device-width, initial-scale=1"/);
        assert.doesNotMatch(html, /user-scalable=no|maximum-scale=1/);
        for (const id of ['gallery', 'viewer', 'transferList', 'propertiesModal']) {
            assert.ok(html.includes(`id="${id}"`), `${name} missing ${id}`);
        }
    }
});
