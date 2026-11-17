// Run with an already provisioned Playwright module and Chromium executable.
// No browser download, HTTP server, shared profile, or production API is used.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { Script } = require('node:vm');

const [playwrightModule, chromiumExecutable, outputDirectory] = process.argv.slice(2);
if (!playwrightModule || !chromiumExecutable || !outputDirectory) {
    console.error('Usage: node check.cjs <playwright-module> <chromium-executable> <private-output-directory>');
    process.exit(1);
}

const output = path.resolve(outputDirectory);
for (const directory of ['tmp', 'home', 'cache', 'downloads']) {
    fs.mkdirSync(path.join(output, directory), { recursive: true, mode: 0o700 });
}
process.env.TMPDIR = path.join(output, 'tmp');
process.env.HOME = path.join(output, 'home');
process.env.XDG_CACHE_HOME = path.join(output, 'cache');
const { chromium } = require(path.resolve(playwrightModule));
const reference = path.join(__dirname, 'index.html');
const html = fs.readFileSync(reference, 'utf8');
for (const [index, match] of [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].entries()) {
    new Script(match[1], { filename: `reference-script-${index}.js` });
}

async function player(page, { width = 412, height = 906, scale = 1, long = false, mode = 'timed' } = {}) {
    await page.evaluate(({ width, height, scale, long, mode }) => {
        document.documentElement.style.setProperty('--width', width + 'px');
        document.documentElement.style.setProperty('--height', height + 'px');
        document.documentElement.style.setProperty('--scale', scale);
        document.querySelector('#phone').classList.toggle('large', scale >= 1.5);
        document.querySelector('#feature-data').value = 'ready';
        document.querySelector('#lyrics-mode').value = mode;
        document.querySelector('#long-lyrics').checked = long;
        scenario('player');
        setSamplePosition(22);
        render();
        updatePlayerScrollability();
    }, { width, height, scale, long, mode });
}

async function geometry(page) {
    return page.evaluate(() => {
        const rectangle = selector => {
            const bounds = document.querySelector(selector).getBoundingClientRect();
            return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height };
        };
        return Object.fromEntries(['.player-album', '.player-cover', '.player-lyric-slot', '.player-identity', '.seek', '.transport']
            .map(selector => [selector, rectangle(selector)]));
    });
}

function sameGeometry(before, after) {
    for (const selector of Object.keys(before)) {
        for (const property of ['x', 'y', 'width', 'height']) {
            assert.ok(Math.abs(before[selector][property] - after[selector][property]) < 1,
                `${selector} ${property} shifted: ${before[selector][property]} -> ${after[selector][property]}`);
        }
    }
}

(async () => {
    const browser = await chromium.launch({
        executablePath: path.resolve(chromiumExecutable),
        headless: true,
        downloadsPath: path.join(output, 'downloads'),
    });
    try {
        const page = await browser.newPage({ viewport: { width: 1280, height: 1200 }, colorScheme: 'light' });
        const errors = [];
        const externalRequests = [];
        page.on('pageerror', error => errors.push(error.message));
        page.on('request', request => {
            if (/^https?:/.test(request.url())) externalRequests.push(request.url());
        });
        await page.goto(pathToFileURL(reference).href);
        await page.evaluate(() => document.fonts.ready);
        await player(page);
        const initial = await geometry(page);
        assert.ok(initial['.player-album'].y < initial['.player-cover'].y);
        assert.ok(initial['.player-cover'].y < initial['.player-lyric-slot'].y);
        assert.ok(initial['.player-lyric-slot'].y < initial['.player-identity'].y);
        assert.equal(await page.locator('[data-action="player-more"]').count(), 0);
        assert.ok((await page.locator('[data-action="player-artist"]').boundingBox()).height >= 48);
        assert.equal(await page.locator('.inline-lyric svg').count(), 0);
        assert.ok(!(await page.locator('.player').innerText()).includes('Scroll lyrics'));
        const typography = await page.evaluate(() => ['.player-album', '.player-identity .credit', '.lyric-stage'].map(selector => {
            const style = getComputedStyle(document.querySelector(selector));
            return [style.fontSize, style.lineHeight, style.fontWeight];
        }));
        assert.deepEqual(typography[0], ['13.2px', '17.82px', '400']);
        assert.deepEqual(typography[1], typography[0]);
        assert.deepEqual(typography[2], typography[0]);
        const centers = await page.evaluate(() => {
            const center = selector => {
                const bounds = document.querySelector(selector).getBoundingClientRect();
                return bounds.x + bounds.width / 2;
            };
            return [center('#elapsed'), center('[data-action="shuffle"]'),
                center('.times > span:last-child'), center('[data-action="repeat"]')];
        });
        assert.ok(Math.abs(centers[0] - centers[1]) < 1);
        assert.ok(Math.abs(centers[2] - centers[3]) < 1);
        await page.locator('#phone').screenshot({ path: path.join(output, 'player.png') });

        await player(page, { width: 320, height: 620, scale: 1.8 });
        const shortLine = await geometry(page);
        await page.evaluate(() => { document.querySelector('#long-lyrics').checked = true; render(); });
        sameGeometry(shortLine, await geometry(page));
        assert.ok(await page.locator('.inline-lyric').evaluate(element => element.scrollHeight > element.clientHeight));
        await page.locator('.inline-lyric').hover();
        await page.mouse.wheel(0, 30);
        await page.waitForFunction(() => document.querySelector('.inline-lyric').scrollTop > 0);
        await page.evaluate(() => {
            window.checkedLyricNode = document.querySelector('.inline-lyric');
            window.checkedLyricScroll = window.checkedLyricNode.scrollTop;
            setSamplePosition(23);
        });
        assert.ok(await page.evaluate(() => window.checkedLyricNode === document.querySelector('.inline-lyric')));
        assert.equal(await page.locator('.inline-lyric').evaluate(element => element.scrollTop),
            await page.evaluate(() => window.checkedLyricScroll));
        const preservedScroll = await page.locator('.inline-lyric').evaluate(element => element.scrollTop);
        await page.locator('.inline-lyric').click({ position: { x: 50, y: 20 } });
        assert.equal(await page.locator('.lyrics-pane').count(), 1);
        await page.evaluate(() => dismissSheet());
        assert.equal(await page.locator('.inline-lyric').evaluate(element => element.scrollTop), preservedScroll);
        await page.evaluate(() => handle('toggle'));
        assert.equal(await page.locator('.inline-lyric').evaluate(element => element.scrollTop), preservedScroll);
        await page.evaluate(() => setSamplePosition(31));
        assert.equal(await page.locator('.inline-lyric').evaluate(element => element.scrollTop), 0);
        sameGeometry(shortLine, await geometry(page));
        await page.locator('#phone').screenshot({ path: path.join(output, 'player-long-large.png') });
        await page.evaluate(() => setSamplePosition(57));
        assert.equal(await page.locator('.inline-lyric').count(), 0);
        assert.equal(await page.locator('.lyric-entry').innerText(), 'View lyrics');
        sameGeometry(shortLine, await geometry(page));
        await page.locator('.lyric-entry').click();
        assert.equal(await page.locator('.lyrics-pane').count(), 1);
        assert.equal(await page.locator('.lyric-line[aria-current="true"]').count(), 0);
        await page.evaluate(() => dismissSheet());
        assert.ok(await page.evaluate(() => document.activeElement?.dataset.action?.startsWith('lyrics:')));

        await player(page, { mode: 'plain' });
        assert.equal(await page.locator('.inline-lyric').count(), 0);
        await page.locator('.lyric-entry').click();
        assert.ok((await page.locator('.feature-badge').innerText()).includes('Plain lyrics'));
        await page.evaluate(() => dismissSheet());
        await page.evaluate(() => { document.querySelector('#feature-data').value = 'error'; render(); });
        await page.locator('.lyric-entry').click();
        await page.locator('.sheet [data-action="feature-retry"]').click();
        assert.equal(await page.locator('.lyric-lines').count(), 1);
        await page.evaluate(() => dismissSheet());
        for (const mode of ['missing', 'loading']) {
            await page.evaluate(mode => { document.querySelector('#feature-data').value = mode; render(); }, mode);
            assert.equal(await page.locator('.inline-lyric, .lyric-entry').count(), 0);
        }
        await player(page);
        await page.locator('.lyric-stage').click();
        assert.equal(await page.locator('.lyrics-pane').count(), 1);
        await page.evaluate(() => dismissSheet());
        await page.locator('.player-album').click();
        assert.equal(await page.evaluate(() => state.route.kind), 'album');
        await page.keyboard.press('Escape');
        assert.ok(await page.evaluate(() => state.player));
        await page.locator('[data-action="player-artist"]').click();
        assert.equal(await page.evaluate(() => state.route.kind), 'artist');
        await page.keyboard.press('Escape');
        assert.ok(await page.evaluate(() => state.player));

        for (const configuration of [
            { width: 280, height: 530 }, { width: 320, height: 906 },
            { width: 412, height: 906, scale: 2 }, { width: 320, height: 530, scale: 2 },
        ]) {
            await player(page, configuration);
            assert.ok(await page.locator('.player-body').evaluate(element => element.scrollWidth <= element.clientWidth + 1));
            assert.ok(await page.locator('.inline-lyric').evaluate(element => element.scrollWidth <= element.clientWidth + 1));
        }

        assert.ok(await page.locator('.player').evaluate(element => element.classList.contains('scrollable')));
        assert.ok(await page.locator('.player-body').evaluate(element => {
            element.scrollTop = element.scrollHeight;
            const queue = element.querySelector('[data-action="queue"]').getBoundingClientRect();
            return element.scrollTop > 0 && queue.bottom <= element.getBoundingClientRect().bottom;
        }));

        await page.evaluate(() => {
            document.documentElement.style.setProperty('--scale', 1);
            document.documentElement.style.setProperty('--width', '412px');
            document.documentElement.style.setProperty('--height', '906px');
            document.querySelector('#phone').classList.remove('large');
            scenario('history');
        });
        await page.locator('[data-action="tab:settings"]').click();
        assert.equal(await page.getByRole('switch', { name: 'Light theme' }).count(), 0);
        assert.equal(await page.getByRole('switch', { name: 'Offline mode' }).count(), 1);
        assert.equal(await page.getByRole('button', { name: 'Credits' }).count(), 1);
        await page.getByRole('button', { name: /Account colours/ }).click();
        const paletteGeometry = () => page.locator('.palette-option').evaluateAll(elements => elements.map(element => {
            const bounds = element.getBoundingClientRect();
            return [bounds.y, bounds.height];
        }));
        const originalChoices = await paletteGeometry();
        const palettes = ['oxblood', 'pine', 'ink-blue'];
        const phone = page.locator('#phone');
        const preview = page.getByLabel('Preview palette');
        const paletteRoles = new Map();
        for (const palette of palettes) {
            const choice = page.locator(`[data-action="palette:${palette}"]`);
            await choice.click();
            assert.equal(await choice.getAttribute('aria-pressed'), 'true');
            assert.deepEqual(await paletteGeometry(), originalChoices);
            assert.equal(await page.locator('.content .progress, .content .refresh-progress').count(), 0);
            const { scheme, canvas, layer, accent, contrast } = await phone.evaluate(element => {
                const style = getComputedStyle(element);
                const canvas = style.backgroundColor;
                const layer = getComputedStyle(element.querySelector('.palette-sample')).backgroundColor;
                const accent = getComputedStyle(element.querySelector('.sample-play'));
                const pixel = document.createElement('canvas').getContext('2d', { willReadFrequently: true });
                function luminance(color) {
                    pixel.fillStyle = color;
                    pixel.fillRect(0, 0, 1, 1);
                    const [r, g, b] = [...pixel.getImageData(0, 0, 1, 1).data].slice(0, 3).map(n => {
                        const c = n / 255;
                        return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4;
                    });
                    return .2126 * r + .7152 * g + .0722 * b;
                }
                const ratio = (a, b) => (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
                const muted = luminance(style.getPropertyValue('--muted'));
                const fill = luminance(accent.backgroundColor);
                return {
                    scheme: style.colorScheme, canvas, layer, accent: accent.backgroundColor,
                    contrast: [ratio(muted, luminance(canvas)), ratio(muted, luminance(layer)),
                        ratio(fill, luminance(accent.color)), ratio(fill, luminance(layer))],
                };
            });
            assert.equal(scheme, 'dark');
            assert.notEqual(canvas, layer);
            assert.notEqual(layer, accent);
            assert.ok(contrast.slice(0, 3).every(ratio => ratio >= 4.5) && contrast[3] >= 3, `Contrast for ${palette}: ${contrast}`);
            paletteRoles.set(palette, { canvas, layer, accent });
        }
        assert.equal(new Set([...paletteRoles.values()].map(role => role.canvas)).size, 3);
        await phone.screenshot({ path: path.join(output, 'colours-dark.png') });

        // Reviewer changes are visual only: the same player, artwork and saved choice survive.
        await page.locator('[data-scenario="player"]').click();
        const playerSurface = page.locator('.player');
        const originalPlayer = await playerSurface.elementHandle();
        const cover = page.locator('.player-cover');
        const artworkColour = await cover.evaluate(node => getComputedStyle(node).backgroundColor);
        for (const palette of palettes) {
            await preview.selectOption(palette);
            assert.equal(await phone.getAttribute('data-palette'), palette);
            assert.equal(await playerSurface.evaluate((node, original) => node === original, originalPlayer), true);
            assert.equal(await cover.evaluate(node => getComputedStyle(node).backgroundColor), artworkColour);
            assert.deepEqual(await playerSurface.evaluate(node => {
                const background = target => getComputedStyle(target).backgroundColor;
                return { canvas: background(node), layer: background(node.querySelector('.player-controls')),
                    accent: background(node.querySelector('.transport .play')) };
            }), paletteRoles.get(palette));
            await phone.screenshot({ path: path.join(output, `player-${palette}.png`) });
        }
        await originalPlayer.dispose();
        await preview.selectOption('');
        assert.equal(await phone.getAttribute('data-palette'), 'ink-blue');
        await page.locator('.developer-panel > summary').click();
        await page.getByLabel('Account fixture').selectOption('secondary');
        assert.equal(await phone.getAttribute('data-palette'), 'pine');
        await page.getByLabel('Account fixture').selectOption('primary');
        assert.equal(await phone.getAttribute('data-palette'), 'ink-blue');

        for (const kind of ['album', 'artist', 'playlist']) {
            await page.evaluate(kind => { scenario('history'); openRoute({ kind, id: 0 }); }, kind);
            const alignment = await page.evaluate(() => {
                const center = element => { const bounds = element.getBoundingClientRect(); return bounds.x + bounds.width / 2; };
                const row = document.querySelector('.motion-face .row');
                return [center(document.querySelector('[data-action="playcollection"]')), center(row.querySelector('.art')),
                    center(document.querySelector('.collection-controls > .icon:last-child')), center(row.querySelector('.icon'))];
            });
            assert.ok(Math.abs(alignment[0] - alignment[1]) < 1, `${kind} Play column differs from artwork`);
            assert.ok(Math.abs(alignment[2] - alignment[3]) < 1, `${kind} More columns differ`);
        }
        const rowFocus = await page.locator('.motion-face .row-body').first().evaluate(element => ({
            action: element.dataset.action,
            context: element.dataset.context || null,
        }));
        await page.locator('[data-action="playcollection"]').focus();
        assert.ok(await page.evaluate(token => restoreButtonFocus(token), rowFocus));
        assert.ok(await page.evaluate(token => document.activeElement.tagName === 'BUTTON' &&
            document.activeElement.dataset.action === token.action, rowFocus));
        const gutterFace = page.locator('.motion-surface > .motion-face').first();
        const gutterBounds = await gutterFace.boundingBox();
        const gutterBackground = await gutterFace.evaluate(element => getComputedStyle(element).backgroundColor);
        const rowTrack = await gutterFace.evaluate(element => element.dataset.rowAction.split(':')[1]);
        await page.mouse.move(gutterBounds.x + 5, gutterBounds.y + gutterBounds.height / 2);
        await page.mouse.down();
        assert.notEqual(await gutterFace.evaluate(element => getComputedStyle(element).backgroundColor), gutterBackground);
        await page.mouse.up();
        assert.equal(await page.evaluate(() => state.current.track.id), rowTrack);
        const beforeMore = await page.evaluate(() => state.current?.occurrence);
        const more = page.locator('.motion-face .row > .icon').first();
        const face = more.locator('xpath=ancestor::div[contains(@class,"motion-face")]');
        const background = await face.evaluate(element => getComputedStyle(element).backgroundColor);
        const bounds = await more.boundingBox();
        await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        await page.mouse.down();
        assert.notEqual(await face.evaluate(element => getComputedStyle(element).backgroundColor), background);
        assert.ok(await face.evaluate(element => {
            const bounds = element.getBoundingClientRect();
            const app = document.querySelector('#app').getBoundingClientRect();
            return Math.abs(bounds.left - app.left) < 1 && Math.abs(bounds.right - app.right) < 1;
        }));
        await page.locator('#phone').screenshot({ path: path.join(output, 'row-more-pressed.png') });
        await page.mouse.up();
        assert.equal(await page.evaluate(() => state.current?.occurrence), beforeMore);
        assert.equal(await page.evaluate(() => state.sheet.type), 'song');
        assert.deepEqual(errors, []);
        assert.deepEqual(externalRequests, []);
        console.log('PASS: syntax, player geometry/type, scrolling/reset, lyrics/album access, responsive bounds, palette roles/contrast/isolation, row columns, More isolation, no external requests.');
    } finally {
        await browser.close();
    }
})().catch(error => {
    console.error(error.stack);
    process.exitCode = 1;
});
