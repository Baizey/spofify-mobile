// Rendered-reference checks only: synthetic fixtures, no backend, device, or browser download.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const [playwrightModule, chromiumExecutable, outputDirectory] = process.argv.slice(2);
if (!playwrightModule || !chromiumExecutable || !outputDirectory) {
    console.error('Usage: node check-offline.cjs <playwright-module> <chromium-executable> <private-output-directory>');
    process.exit(1);
}
const output = path.resolve(outputDirectory);
for (const name of ['tmp', 'home', 'cache', 'downloads']) {
    fs.mkdirSync(path.join(output, name), { recursive: true, mode: 0o700 });
}
process.env.TMPDIR = path.join(output, 'tmp');
process.env.HOME = path.join(output, 'home');
process.env.XDG_CACHE_HOME = path.join(output, 'cache');
const { chromium } = require(path.resolve(playwrightModule));

(async () => {
    const browser = await chromium.launch({
        executablePath: path.resolve(chromiumExecutable),
        headless: true,
        downloadsPath: path.join(output, 'downloads'),
    });
    try {
        const page = await browser.newPage({ viewport: { width: 1280, height: 1200 } });
        const errors = [];
        const requests = [];
        page.on('pageerror', error => errors.push(error.message));
        page.on('request', request => {
            if (/^https?:/.test(request.url())) requests.push(request.url());
        });
        await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
        await page.evaluate(() => document.fonts.ready);
        await page.evaluate(() => scenario('offline'));

        const tabs = await page.locator('#app .nav button').allTextContents();
        assert.deepEqual(tabs.map(text => text.trim()), ['History', 'Search', 'Playlists', 'Settings']);
        assert.equal(await page.locator('#app .offline-strip').count(), 1);
        assert.equal(await page.evaluate(() => listenerSongs(state.route).length), 4);
        await page.locator('#phone').screenshot({ path: path.join(output, 'offline-playlist.png') });

        // Offline is a mode of the ordinary shell; Saved music is only a Settings destination.
        await page.locator('#app [data-action="tab:settings"]').click();
        assert.equal(await page.locator('#app [data-action="offline"]').getAttribute('aria-checked'), 'true');
        assert.equal(await page.locator('#cache-value').innerText(), '10 songs');
        assert.deepEqual(await page.evaluate(() => cacheSteps), [2, 5, 10, 100, 1000]);
        await page.locator('#cache-slider').evaluate(element => {
            element.value = '4';
            element.dispatchEvent(new Event('input', { bubbles: true }));
        });
        assert.equal(await page.locator('#cache-value').innerText(), '1000 songs');
        assert.equal(await page.evaluate(() => temporaryCachePolicy().lookahead), 3);
        await page.locator('#app [data-action="saved"]').click();
        assert.equal(await page.locator('#app .nav button').count(), 4);
        assert.ok((await page.locator('#app .content').innerText()).includes('Available offline'));
        await page.locator('#phone').screenshot({ path: path.join(output, 'offline-saved-settings-page.png') });

        await page.locator('#app [data-action="tab:history"]').click();
        assert.deepEqual((await page.locator('#app [aria-label="History view"] button').allTextContents()).map(text => text.trim()),
            ['Overview', 'Timeline', 'Top', 'Year']);
        await page.locator('#app [data-action="history-view:timeline"]').click();
        assert.ok((await page.locator('#app .content').innerText()).includes('Not saved'));
        assert.ok(await page.locator('#app [data-history-event]').count() > 0);
        await page.locator('#phone').screenshot({ path: path.join(output, 'offline-history-timeline.png') });
        await page.locator('#app [data-action="history-view:year"]').click();
        assert.ok(Number(await page.locator('#app .record-total').innerText()) > 0);
        await page.locator('#phone').screenshot({ path: path.join(output, 'offline-history-year.png') });

        await page.evaluate(() => scenario('offline'));
        await page.locator('#app [data-action="playcollection"]').click();
        const currentId = await page.evaluate(() => state.current.track.id);
        assert.equal(await page.evaluate(() => state.current.leased), true);
        await page.locator('#app .mini [data-action="expand"]').click();
        assert.equal(await page.locator('#app .player .offline-strip').count(), 1);
        assert.ok((await page.locator('#app .player-cover').boundingBox()).width <= 280.5);
        assert.equal(await page.locator('#app .player [data-action="player-more"]').count(), 0);
        await page.locator('#phone').screenshot({ path: path.join(output, 'offline-full-player.png') });
        await page.locator('#app .player [data-action="devices"]').click();
        assert.equal(await page.locator('#app .device-row').count(), 1);
        assert.ok((await page.locator('#app .device-row').innerText()).includes('This phone'));
        await page.keyboard.press('Escape');
        await page.keyboard.press('Escape');

        // Shared retention protects the same bytes; removing one collection does not remove another's songs.
        await page.locator('#app [data-action="collectionmenu:playlist:0"]').click();
        await page.locator('#app [data-action="retention"]').click();
        await page.keyboard.press('Escape');
        assert.equal(await page.evaluate(() => localTrack('t1')), true);
        assert.equal(await page.evaluate(() => localTrack('t4')), false);
        assert.equal(await page.evaluate(() => state.queue.some(entry => entry.track.id === 't4')), false);
        assert.equal(await page.evaluate(() => state.current.track.id), currentId);

        await page.evaluate(() => openRoute({ kind: 'album', id: 0 }));
        await page.locator('#app [data-action="collectionmenu:album:0"]').click();
        await page.locator('#app [data-action="retention"]').click();
        await page.keyboard.press('Escape');
        assert.equal(await page.evaluate(() => localTrack('t1')), false);
        assert.equal(await page.evaluate(() => state.current.track.id), currentId);
        assert.equal(await page.evaluate(() => state.current.leased), true);
        assert.equal(await page.evaluate(() => state.queue.length), 0);

        // Enabling retention while offline records a waiting intent; it never fabricates completed bytes.
        await page.evaluate(() => openRoute({ kind: 'album', id: 1 }));
        await page.locator('#app [data-action="collectionmenu:album:1"]').click();
        await page.locator('#app [data-action="retention"]').click();
        assert.equal(await page.evaluate(() => retention.get('album:1').desired), true);
        assert.equal(await page.evaluate(() => retention.get('album:1').status), 'waiting');
        assert.equal(await page.evaluate(() => retention.get('album:1').installed.size), 0);
        await page.locator('#phone').screenshot({ path: path.join(output, 'offline-save-waiting.png') });
        await page.keyboard.press('Escape');

        const route = await page.evaluate(() => ({ ...state.route }));
        await page.locator('#app > .offline-strip [data-action="online"]').click();
        assert.equal(await page.evaluate(() => state.offline), false);
        assert.equal(await page.evaluate(() => state.playing), false);
        assert.deepEqual(await page.evaluate(() => state.route), route);
        assert.equal(await page.evaluate(() => retention.get('album:1').status), 'paused');
        assert.deepEqual(errors, []);
        assert.deepEqual(requests, []);
        console.log('PASS: complete offline shell, Settings/cache/saved route, History, player/output, shared retention and waiting/pause behavior');
    } finally {
        await browser.close();
    }
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
