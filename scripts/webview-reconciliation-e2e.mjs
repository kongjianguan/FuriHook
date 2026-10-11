import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageRequire = createRequire(path.join(repository, 'scripts/webview-e2e/package.json'));
const { PNG: PngDecoder } = packageRequire('pngjs');
const { chromium: chromiumLauncher } = packageRequire('playwright');
const fixture = path.join(repository, 'testapp/src/main/assets/webview_ruby_fixture.html');
const renderer = path.join(repository, 'app/src/main/assets/furihook-web.js');
const classpathFile = path.join(repository, 'core/build/reports/webview-e2e/bridge-classpath.txt');
const outputDirectory = path.join(repository, 'work/webview-reconciliation-e2e');
const reactOriginal = '今日は学校へ行きます。';
const reactTranslation = '今天去学校学习日语。';
const retainedRow = '今日は大学。';
const insertedRow = '明日学校へ行く。';
let segmentCheckNumber = 0;

await mkdir(outputDirectory, { recursive: true });
const classpath = (await readFile(classpathFile, 'utf8')).trim();
assert.ok(classpath, 'Production reading-engine bridge classpath is empty');
const bridge = spawn('java', ['-cp', classpath, 'dev.furihook.engine.ReadingEngineJsonBridge'], {
  cwd: repository,
  stdio: ['pipe', 'pipe', 'inherit']
});
const bridgeLines = createInterface({ input: bridge.stdout });
const bridgeQueue = [];
const bridgeWaiters = [];
bridgeLines.on('line', line => {
  const waiter = bridgeWaiters.shift();
  if (waiter) waiter(line);
  else bridgeQueue.push(line);
});
bridge.on('exit', (code, signal) => {
  if (code !== 0) {
    while (bridgeWaiters.length) bridgeWaiters.shift()(null, new Error(`Reading bridge exited: ${code ?? signal}`));
  }
});

function nextBridgeResponse() {
  if (bridgeQueue.length) return Promise.resolve(bridgeQueue.shift());
  return new Promise((resolve, reject) => bridgeWaiters.push(line => {
    if (line === null) reject(new Error('Reading bridge ended before responding'));
    else resolve(line);
  }));
}

async function analyzeTexts(texts) {
  bridge.stdin.write(`${JSON.stringify({ texts })}\n`);
  return JSON.parse(await nextBridgeResponse());
}

const productionScript = await readFile(renderer, 'utf8');
const browserOptions = { headless: true, viewport: { width: 1280, height: 960 }, deviceScaleFactor: 1 };
const browserExecutable = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || process.env.CHROME_PATH;
if (browserExecutable) browserOptions.executablePath = browserExecutable;
const context = await chromiumLauncher.launchPersistentContext(
  path.join(outputDirectory, 'chrome-profile'), browserOptions);
const browserVersion = context.browser().version();
try {
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.addInitScript({ content: `${productionScript}\nwindow.FuriHook102.install(Date.now());\n(async function pump(){if(!window.FuriHook102){setTimeout(pump,20);return;}try{for(let i=0;i<8;i++){const raw=window.FuriHook102.pull(JSON.parse(window.FuriHook102.inspect()).generation);if(!raw)break;const batch=JSON.parse(raw);if(!batch.items.length)break;const analyzed=await window.__furiBridgeAnalyze(batch.items.map(item=>item.text));const byText=new Map(analyzed.results.map(item=>[item.text,item.segments.map(segment=>[segment.startUtf16,segment.endUtf16,segment.reading])]));window.FuriHook102.apply(JSON.stringify({generation:batch.generation,items:batch.items.map(item=>({...item,segments:byText.get(item.text)||[]}))}));}}catch(error){window.__furiPumpError=String(error&&error.stack||error);}setTimeout(pump,12);})();` });
  await page.exposeFunction('__furiBridgeAnalyze', analyzeTexts);
  await page.goto(pathToFileURL(fixture).href, { waitUntil: 'load' });

  async function inspect() {
    return page.evaluate(() => {
      if (window.__furiPumpError) throw new Error(window.__furiPumpError);
      return JSON.parse(window.FuriHook102.inspect());
    });
  }
  async function inspectDetailed() {
    return page.evaluate(() => JSON.parse(window.FuriHook102.inspect(true)));
  }
  async function layoutSnapshot(selector) {
    return page.locator(selector).evaluate(element => {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return {width: rect.width, letterSpacing: style.letterSpacing, fontSize: style.fontSize,
        color: style.color, lineHeight: style.lineHeight, paddingTop: style.paddingTop,
        display: style.display, position: style.position};
    });
  }
  function reservationFor(inspection, elementId) {
    return inspection.layoutReservations.findLast(item => item.elementId === elementId);
  }
  function assertReservedSpace(reservation, elementId) {
    assert.ok(reservation, `inspect(true) must expose layout reservation for #${elementId}`);
    assert.equal(reservation.status, 'reserved', JSON.stringify(reservation));
    const baseline = reservation.baseline;
    const applied = reservation.applied;
    assert.ok(Number.parseFloat(applied.paddingTop) > Number.parseFloat(baseline.paddingTop)
      || Number.parseFloat(applied.lineHeight) > Number.parseFloat(baseline.lineHeight),
    `Japanese readings must reserve real host line space: ${JSON.stringify(reservation)}`);
  }
  async function waitFor(predicate, message, timeout = 20000, argument = null) {
    await page.waitForFunction(predicate, argument, { timeout, polling: 40 }).catch(async error => {
      throw new Error(`${message}; last inspection: ${JSON.stringify(await inspect())}`, { cause: error });
    });
  }
  function annotationsFor(inspection, text) {
    const ids = new Set(inspection.hostNodes.filter(node => node.connected && node.data === text)
      .map(node => node.nodeId));
    return inspection.annotations.filter(annotation => ids.has(annotation.nodeId));
  }
  async function checkProductionSegments(text, annotations) {
    const expected = (await analyzeTexts([text])).results[0].segments;
    const actual = [...annotations].sort((a, b) => a.segmentStart - b.segmentStart);
    const snapshot = {
      text,
      expected,
      actual,
      inspection: await inspectDetailed(),
      react: await page.evaluate(() => window.reactFixture ? window.reactFixture.inspect() : null)
    };
    segmentCheckNumber++;
    const checkPath = path.join(outputDirectory,
      `segment-check-${String(segmentCheckNumber).padStart(2, '0')}.json`);
    await writeFile(checkPath, `${JSON.stringify(snapshot, null, 2)}\n`);
    assert.equal(actual.length, expected.length,
      `Kuromoji segment count for ${text}; expected ${JSON.stringify(expected)}, actual ${JSON.stringify(actual)}; inspect=${checkPath}`);
    for (let index = 0; index < expected.length; index++) {
      assert.equal(actual[index].segmentStart, expected[index].startUtf16);
      assert.equal(actual[index].segmentEnd, expected[index].endUtf16);
      assert.equal(actual[index].baseText, expected[index].baseText);
      assert.equal(actual[index].reading, expected[index].reading);
    }
  }
  function assertGeometry(annotations, requireAny = true) {
    if (requireAny) assert.ok(annotations.length > 0, 'Expected at least one rendered annotation');
    for (const annotation of annotations) {
      assert.equal(annotation.visible, true);
      assert.equal(annotation.clearance, 'clear');
      assert.ok(annotation.readingRect.bottom <= annotation.baseRect.top,
        `Reading must be above base text: ${JSON.stringify(annotation)}`);
      for (const rect of annotation.textRects) {
        assert.ok(annotation.readingRect.right <= rect.left || annotation.readingRect.left >= rect.right
          || annotation.readingRect.bottom <= rect.top || annotation.readingRect.top >= rect.bottom,
        `Reading overlaps source text: ${JSON.stringify(annotation)}`);
      }
    }
  }
  async function settle() {
    await waitFor(() => {
      const data = JSON.parse(window.FuriHook102.inspect());
      return data.scan.initialDone && data.scan.dirtyRoots === 0 && data.scan.dirtyNodes === 0
        && data.scan.pendingBatches === 0 && data.scan.awaitingReadings === 0;
    }, 'WebView scan and reading queues did not drain');
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  }

  await waitFor(() => window.reactFixture && window.FuriHook102
    && JSON.parse(window.FuriHook102.inspect()).scan.initialDone, 'Fixture initialization');
  await settle();
  const translationSnapshot = await page.evaluate(() => window.reactFixture.inspect());
  const reactTranslationLayout = await layoutSnapshot('#react-current');
  const editableInitial = await page.evaluate(() => {
    const node = document.getElementById('editable').firstChild;
    window.__e2eEditableNode = node;
    return {text: node.data, sameNode: node === document.getElementById('editable').firstChild,
      hasRuby: [...window.__FuriHook102.records.values()].some(record => record.node === node
        && Array.isArray(record.segments) && record.segments.length > 0)};
  });
  assert.deepEqual(editableInitial, {text: '今日は学校。', sameNode: true, hasRuby: false});
  await page.evaluate(() => { window.__e2eEditableNode.data = '編集可能な漢字'; });
  await settle();
  const editableAfterHostUpdate = await page.evaluate(() => ({
    text: document.getElementById('editable').textContent,
    sameNode: window.__e2eEditableNode === document.getElementById('editable').firstChild,
    hasRuby: [...window.__FuriHook102.records.values()].some(record => record.node === window.__e2eEditableNode
      && Array.isArray(record.segments) && record.segments.length > 0)
  }));
  assert.deepEqual(editableAfterHostUpdate,
    {text: '編集可能な漢字', sameNode: true, hasRuby: false},
    'The host must keep its own contenteditable update and the renderer must leave the same Text node alone');
  assert.equal(translationSnapshot.reactProduction, true);
  assert.equal(translationSnapshot.postText, reactTranslation);
  assert.equal(translationSnapshot.postNodeIdentityPreserved, true);
  assert.equal(translationSnapshot.postNodeParentPreserved, true);
  assert.equal(annotationsFor(await inspect(), reactTranslation).length, 0,
    'Chinese translation must not receive Japanese ruby');

  await page.evaluate(() => window.reactFixture.setMode('original'));
  await waitFor(() => window.reactFixture.inspect().mode === 'original'
    && window.reactFixture.inspect().postText === '今日は学校へ行きます。',
  'React did not commit the Japanese source text');
  await page.locator('#react-current').scrollIntoViewIfNeeded();
  await settle();
  const originalSnapshot = await page.evaluate(() => window.reactFixture.inspect());
  assert.equal(originalSnapshot.postText, reactOriginal);
  assert.equal(originalSnapshot.postNodeIdentityPreserved, true);
  assert.equal(originalSnapshot.postNodeParentPreserved, true);
  const originalAnnotations = annotationsFor(await inspect(), reactOriginal);
  const originalLayout = await layoutSnapshot('#react-current');
  const originalReservation = reservationFor(await inspectDetailed(), 'react-current');
  assertReservedSpace(originalReservation, 'react-current');
  assert.equal(originalLayout.width, reactTranslationLayout.width,
    'Ruby reservation must preserve the host block width');
  assert.equal(originalLayout.letterSpacing, reactTranslationLayout.letterSpacing,
    'Ruby reservation must preserve host letter spacing');
  await checkProductionSegments(reactOriginal, originalAnnotations);
  assertGeometry(originalAnnotations);
  const originalHostText = await page.locator('#react-current').evaluate(node => ({
    data: node.firstChild.data,
    childCount: node.childNodes.length,
    directParent: node.firstChild.parentNode === node,
    ownedDescendants: node.querySelectorAll('[data-furihook-owned="1"]').length
  }));
  assert.deepEqual(originalHostText, { data: reactOriginal, childCount: 1, directParent: true, ownedDescendants: 0 });

  await page.evaluate(() => {
    const style = document.createElement('style');
    style.id = 'host-css-update';
    style.textContent = '#react-current.e2e-host-style { font-size: 23px; color: rgb(12, 34, 56); letter-spacing: 1.25px; line-height: 58px; padding-top: 18px; }';
    document.head.appendChild(style);
    document.getElementById('react-current').classList.add('e2e-host-style');
  });
  await settle();
  await waitFor(() => {
    const reservation = JSON.parse(window.FuriHook102.inspect(true)).layoutReservations
      .findLast(item => item.elementId === 'react-current');
    return getComputedStyle(document.getElementById('react-current')).lineHeight === '58px'
      && reservation?.status === 'reserved' && reservation.baseline?.lineHeight === '58px'
      && reservation.baseline?.paddingTop === '18px';
  }, 'Host class layout changes must be measured before Ruby reservation is reapplied');
  const hostCssJapaneseLayout = await layoutSnapshot('#react-current');
  assert.equal(hostCssJapaneseLayout.fontSize, '23px');
  assert.equal(hostCssJapaneseLayout.color, 'rgb(12, 34, 56)');
  assert.equal(hostCssJapaneseLayout.letterSpacing, '1.25px');
  assert.equal(hostCssJapaneseLayout.width, reactTranslationLayout.width);
  assert.equal(hostCssJapaneseLayout.lineHeight, '58px',
    'A host class line-height update must replace the previous module inline value');
  const classReservation = reservationFor(await inspectDetailed(), 'react-current');
  assert.equal(classReservation.status, 'reserved', JSON.stringify(classReservation));
  assert.equal(classReservation.baseline.lineHeight, '58px', JSON.stringify(classReservation));
  assert.equal(classReservation.baseline.paddingTop, '18px', JSON.stringify(classReservation));
  assert.equal(classReservation.computed.lineHeight, '58px', JSON.stringify(classReservation));
  assert.ok(Number.parseFloat(hostCssJapaneseLayout.paddingTop) >= 18,
    `Ruby space must preserve the host class padding baseline: ${JSON.stringify(classReservation)}`);

  await page.evaluate(() => window.reactFixture.setMode('translation'));
  await waitFor(() => window.reactFixture.inspect().mode === 'translation'
    && window.reactFixture.inspect().postText === '今天去学校学习日语。',
  'Host class styles must survive the first Chinese translation');
  await settle();
  const classRestoredLayout = await layoutSnapshot('#react-current');
  const classRestoredReservation = reservationFor(await inspectDetailed(), 'react-current');
  assert.equal(classRestoredLayout.lineHeight, '58px');
  assert.equal(classRestoredLayout.paddingTop, '18px');
  assert.equal(classRestoredReservation.status, 'restored', JSON.stringify(classRestoredReservation));

  await page.evaluate(() => window.reactFixture.setMode('original'));
  await waitFor(() => window.reactFixture.inspect().mode === 'original'
    && window.reactFixture.inspect().postText === '今日は学校へ行きます。'
    && (() => {
      const inspection = JSON.parse(window.FuriHook102.inspect());
      const node = inspection.hostNodes.find(item => item.connected && item.data === '今日は学校へ行きます。');
      return Boolean(node && inspection.annotations.some(item => item.nodeId === node.nodeId));
    })(),
  'Host class styles must be measured again when Japanese Ruby returns');
  const classReappliedReservation = reservationFor(await inspectDetailed(), 'react-current');
  assert.equal(classReappliedReservation.baseline.lineHeight, '58px', JSON.stringify(classReappliedReservation));
  assert.equal(classReappliedReservation.baseline.paddingTop, '18px', JSON.stringify(classReappliedReservation));

  await page.locator('#react-current').evaluate(element => {
    element.style.lineHeight = '62px';
    element.style.paddingTop = '24px';
  });
  await waitFor(() => {
    const element = document.getElementById('react-current');
    const style = getComputedStyle(element);
    const reservation = JSON.parse(window.FuriHook102.inspect(true)).layoutReservations
      .findLast(item => item.elementId === 'react-current');
    return style.lineHeight === '62px' && Number.parseFloat(style.paddingTop) >= 24
      && reservation?.status === 'reserved' && reservation.baseline?.lineHeight === '62px'
      && reservation.baseline?.paddingTop === '24px';
  }, 'Host inline line-height and padding changes must become the new reservation baseline');
  const hostInlineJapaneseLayout = await layoutSnapshot('#react-current');
  const inlineReservation = reservationFor(await inspectDetailed(), 'react-current');
  assert.equal(hostInlineJapaneseLayout.lineHeight, '62px');
  assert.ok(Number.parseFloat(hostInlineJapaneseLayout.paddingTop) >= 24);
  assert.equal(inlineReservation.computed.lineHeight, '62px', JSON.stringify(inlineReservation));
  assert.equal(inlineReservation.baseline.lineHeight, '62px', JSON.stringify(inlineReservation));
  assert.equal(inlineReservation.baseline.paddingTop, '24px', JSON.stringify(inlineReservation));

  await page.evaluate(() => window.reactFixture.setMode('translation'));
  await waitFor(() => window.reactFixture.inspect().mode === 'translation'
    && window.reactFixture.inspect().postText === '今天去学校学习日语。',
  'React did not commit the Chinese translation');
  await settle();
  const translatedAgain = await page.evaluate(() => window.reactFixture.inspect());
  assert.equal(translatedAgain.postNodeIdentityPreserved, true);
  assert.equal(translatedAgain.postNodeParentPreserved, true);
  assert.equal(translatedAgain.postNodeData, reactTranslation);
  assert.equal(annotationsFor(await inspect(), reactTranslation).length, 0);
  const translatedLayout = await layoutSnapshot('#react-current');
  assert.equal(translatedLayout.paddingTop, '24px',
    'Returning to Chinese must restore the host inline padding update');
  assert.equal(translatedLayout.lineHeight, '62px',
    'Returning to Chinese must restore the host inline line-height update');
  assert.equal(translatedLayout.width, reactTranslationLayout.width);
  assert.equal(translatedLayout.letterSpacing, '1.25px',
    'Restoration must preserve the host CSS update');
  assert.equal(translatedLayout.fontSize, '23px');
  assert.equal(translatedLayout.color, 'rgb(12, 34, 56)');
  const restoredReservation = reservationFor(await inspectDetailed(), 'react-current');
  assert.ok(restoredReservation, 'inspect(true) must retain the restoration result');
  assert.equal(restoredReservation.status, 'restored', JSON.stringify(restoredReservation));

  await page.evaluate(() => window.reactFixture.setMode('original'));
  await waitFor(() => window.reactFixture.inspect().mode === 'original'
    && window.reactFixture.inspect().postText === '今日は学校へ行きます。'
    && (() => {
      const inspection = JSON.parse(window.FuriHook102.inspect());
      const node = inspection.hostNodes.find(item => item.connected && item.data === '今日は学校へ行きます。');
      return Boolean(node && inspection.annotations.some(item => item.nodeId === node.nodeId));
    })(),
  'React must reapply Japanese Ruby after host inline layout changes');
  const reannotatedLayout = await layoutSnapshot('#react-current');
  const reannotatedReservation = reservationFor(await inspectDetailed(), 'react-current');
  assert.equal(reannotatedLayout.lineHeight, '62px');
  assert.ok(Number.parseFloat(reannotatedLayout.paddingTop) >= 24);
  assert.equal(reannotatedReservation.baseline.lineHeight, '62px', JSON.stringify(reannotatedReservation));
  assert.equal(reannotatedReservation.baseline.paddingTop, '24px', JSON.stringify(reannotatedReservation));
  await page.evaluate(() => window.reactFixture.setMode('translation'));
  await waitFor(() => window.reactFixture.inspect().mode === 'translation'
    && window.reactFixture.inspect().postText === '今天去学校学习日语。'
    && getComputedStyle(document.getElementById('react-current')).lineHeight === '62px'
    && getComputedStyle(document.getElementById('react-current')).paddingTop === '24px',
  'Removing Ruby after reannotation must retain the latest host inline values');

  const protectedText = '日本語学校。';
  await page.evaluate(text => {
    const fixed = document.createElement('p');
    fixed.id = 'fixed-japanese';
    fixed.textContent = text;
    fixed.style.cssText = 'position:fixed;right:12px;bottom:12px;z-index:10;margin:0;padding:0;font:20px sans-serif;';
    const control = document.createElement('button');
    control.id = 'japanese-control';
    control.textContent = text;
    document.body.append(fixed, control);
    window.__protectedStyleBaseline = {
      fixed: fixed.getAttribute('style'), control: control.getAttribute('style'),
      fixedPosition: getComputedStyle(fixed).position, controlDisplay: getComputedStyle(control).display
    };
  }, protectedText);
  await settle();
  const protectedInspection = await inspect();
  assert.equal(annotationsFor(protectedInspection, protectedText).length, 0,
    'Fixed text and interactive controls must not receive Ruby');
  assert.deepEqual(await page.evaluate(() => {
    const fixed = document.getElementById('fixed-japanese');
    const control = document.getElementById('japanese-control');
    return {fixed: fixed.getAttribute('style'), control: control.getAttribute('style'),
      fixedPosition: getComputedStyle(fixed).position, controlDisplay: getComputedStyle(control).display};
  }), await page.evaluate(() => window.__protectedStyleBaseline),
  'Fixed blocks and controls must keep their host styles');
  const protectedReservations = (await inspectDetailed()).layoutReservations;
  const fixedReservation = protectedReservations.find(item => item.elementId === 'fixed-japanese');
  assert.ok(fixedReservation, 'inspect(true) must record why a fixed block was excluded');
  assert.equal(fixedReservation.status, 'excluded', JSON.stringify(fixedReservation));

  await page.evaluate(() => window.reactFixture.reconcileList());
  await waitFor(() => window.reactFixture.inspect().schoolText === '今日は大学。'
    && window.reactFixture.inspect().addedText === '明日学校へ行く。',
  'React did not commit the keyed list update');
  await page.locator('#react-row-school').scrollIntoViewIfNeeded();
  await settle();
  const listSnapshot = await page.evaluate(() => window.reactFixture.inspect());
  assert.equal(listSnapshot.schoolText, retainedRow);
  assert.equal(listSnapshot.schoolNodeIdentityPreserved, true);
  assert.equal(listSnapshot.schoolElementIdentityPreserved, true);
  assert.equal(listSnapshot.schoolNodeParentPreserved, true);
  assert.equal(listSnapshot.addedText, insertedRow);
  assert.equal(listSnapshot.addedRenderedByReact, true);
  assert.equal(listSnapshot.removedRowDisconnected, true);
  const listInspection = await inspect();
  const retainedAnnotations = annotationsFor(listInspection, retainedRow);
  const insertedAnnotations = annotationsFor(listInspection, insertedRow);
  await checkProductionSegments(retainedRow, retainedAnnotations);
  await checkProductionSegments(insertedRow, insertedAnnotations);

  await page.evaluate(() => {
    const paragraph = document.getElementById('dynamic');
    window.__dynamicNode = paragraph.firstChild;
    window.__dynamicNode.data = '明日は図書館へ行きます。';
  });
  await settle();
  assert.equal(await page.evaluate(() => window.__dynamicNode.isConnected
    && window.__dynamicNode === document.getElementById('dynamic').firstChild), true,
  'Host dynamic mutation must preserve the updated Text node');

  const longText = `${'A'.repeat(1800)}週末に映画館へ行きます。`;
  const longSkipBaseline = await inspect();
  await page.evaluate(text => {
    const paragraph = document.createElement('p');
    paragraph.id = 'long-single-node';
    paragraph.appendChild(document.createTextNode(text));
    document.body.appendChild(paragraph);
    window.__longNode = paragraph.firstChild;
  }, longText);
  await page.evaluate(() => window.scrollTo({ left: 0, top: document.body.scrollHeight, behavior: 'instant' }));
  await settle();
  assert.equal(await page.evaluate(() => window.__longNode.data), longText);
  assert.equal(await page.evaluate(() => window.__longNode.parentNode.id), 'long-single-node');
  const longExpected = (await analyzeTexts([longText])).results[0].segments;
  const longAnalysis = await page.evaluate(() => {
    const node = window.__longNode;
    const records = [...window.__FuriHook102.records.values()].filter(record => record.node === node);
    const segments = records.flatMap(record => (record.segments || []).map(segment => ({
      startUtf16: record.offset + segment[0],
      endUtf16: record.offset + segment[1],
      baseText: record.text.slice(segment[0], segment[1]),
      reading: segment[2]
    })));
    return {nodeData: node.data, connected: node.isConnected, parentId: node.parentNode.id,
      records: records.map(record => ({offset: record.offset, text: record.text,
        segmentCount: (record.segments || []).length})), segments};
  });
  const longDiagnosticPath = path.join(outputDirectory, 'long-node-horizontal-scroll.json');
  const initialLongDiagnostic = {fullHostText: longText, analysis: longAnalysis,
    inspection: await inspectDetailed(), react: await page.evaluate(() => window.reactFixture.inspect())};
  await writeFile(longDiagnosticPath, JSON.stringify(initialLongDiagnostic, null, 2) + '\n');
  assert.equal(longAnalysis.nodeData, longText);
  assert.equal(longAnalysis.connected, true);
  assert.equal(longAnalysis.parentId, 'long-single-node');
  assert.ok(longAnalysis.records.length > 0, 'The real renderer must retain reading-analysis records for this Text node');
  const normalizedLongAnalysis = [...longAnalysis.segments].sort((a, b) => a.startUtf16 - b.startUtf16);
  assert.equal(normalizedLongAnalysis.length, longExpected.length,
    `Long-node production analysis: expected ${JSON.stringify(longExpected)}, actual ${JSON.stringify(normalizedLongAnalysis)}`);
  for (let index = 0; index < longExpected.length; index++) {
    assert.equal(normalizedLongAnalysis[index].startUtf16, longExpected[index].startUtf16);
    assert.equal(normalizedLongAnalysis[index].endUtf16, longExpected[index].endUtf16);
    assert.equal(normalizedLongAnalysis[index].baseText, longExpected[index].baseText);
    assert.equal(normalizedLongAnalysis[index].reading, longExpected[index].reading);
  }
  const naturalInspection = await inspectDetailed();
  const longNodeIds = new Set(naturalInspection.hostNodes.filter(item => item.connected && item.data === longText)
    .map(item => item.nodeId));
  assert.ok(longNodeIds.size > 0, 'inspect must expose the real long host Text node');
  const naturalRanges = await page.evaluate(() => {
    const node = window.__longNode;
    const paragraph = node.parentElement;
    const records = [...window.__FuriHook102.records.values()].filter(item => item.node === node && item.segments)
      .sort((a, b) => a.offset - b.offset);
    const ranges = records.flatMap(record => record.segments.map(segment => {
      const range = document.createRange();
      range.setStart(node, record.offset + segment[0]);
      range.setEnd(node, record.offset + segment[1]);
      const rect = range.getBoundingClientRect();
      return {start: record.offset + segment[0], end: record.offset + segment[1],
        baseText: node.data.slice(record.offset + segment[0], record.offset + segment[1]),
        reading: segment[2], rect: {left: rect.left, top: rect.top, right: rect.right,
          bottom: rect.bottom, width: rect.width, height: rect.height}};
    }));
    return {sameNode: node === paragraph.firstChild, connected: node.isConnected,
      data: node.data, parentTag: paragraph.tagName, paragraphRect: paragraph.getBoundingClientRect().toJSON(),
      scrollWidth: paragraph.scrollWidth, clientWidth: paragraph.clientWidth,
      viewport: {width: innerWidth, height: innerHeight}, ranges};
  });
  const naturalAnnotations = naturalInspection.annotations.filter(item => longNodeIds.has(item.nodeId));
  const expectedByRange = new Map(normalizedLongAnalysis.map(item =>
    [`${item.startUtf16}:${item.endUtf16}:${item.reading}`, item]));
  for (const annotation of naturalAnnotations) {
    assert.ok(expectedByRange.has(`${annotation.segmentStart}:${annotation.segmentEnd}:${annotation.reading}`),
      `Natural layout annotation must match production Kuromoji output: ${JSON.stringify(annotation)}`);
  }
  assertGeometry(naturalAnnotations, false);
  const naturalSkips = {
    overlap: naturalInspection.skippedOverlapCount - longSkipBaseline.skippedOverlapCount,
    clipped: naturalInspection.skippedClippedCount - longSkipBaseline.skippedClippedCount,
    occluded: naturalInspection.skippedOccludedCount - longSkipBaseline.skippedOccludedCount
  };
  let longDiagnostic = {fullHostText: longText, analysis: longAnalysis,
    naturalRanges, naturalInspection, naturalAnnotations, naturalSkips};
  await writeFile(longDiagnosticPath, JSON.stringify(longDiagnostic, null, 2) + '\n');
  assert.equal(naturalRanges.sameNode, true, 'Natural wrapping must retain the original Text node');
  assert.equal(naturalRanges.data, longText);
  assert.ok(naturalRanges.ranges.some(item => item.rect.left >= 0 && item.rect.right <= naturalRanges.viewport.width),
    `Natural wrapping should expose at least one measured Japanese range: ${JSON.stringify(naturalRanges)}`);
  const longScroll = await page.evaluate(() => {
    const node = window.__longNode;
    const paragraph = node.parentElement;
    paragraph.style.cssText = 'box-sizing:border-box;width:100%;overflow-x:auto;white-space:nowrap;padding:32px 0 4px;margin:14px 0;';
    const records = [...window.__FuriHook102.records.values()].filter(item => item.node === node && item.segments)
      .sort((a, b) => a.offset - b.offset);
    const ranges = records.flatMap(record => record.segments.map(segment => {
      const range = document.createRange();
      range.setStart(node, record.offset + segment[0]);
      range.setEnd(node, record.offset + segment[1]);
      const rect = range.getBoundingClientRect();
      return {start: record.offset + segment[0], end: record.offset + segment[1],
        reading: segment[2], rect: {left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom}};
    }));
    const clip = paragraph.getBoundingClientRect();
    const clipLeft = clip.left + paragraph.clientLeft;
    const clipRight = clipLeft + paragraph.clientWidth;
    const tail = ranges[ranges.length - 1];
    const maxScrollLeft = paragraph.scrollWidth - paragraph.clientWidth;
    return {sameNode: node === paragraph.firstChild, data: node.data, scrollLeft: paragraph.scrollLeft,
      scrollWidth: paragraph.scrollWidth, clientWidth: paragraph.clientWidth, maxScrollLeft,
      clip: {left: clipLeft, right: clipRight, top: clip.top + paragraph.clientTop,
        bottom: clip.top + paragraph.clientTop + paragraph.clientHeight}, ranges,
      tailInitiallyOutside: tail.rect.left >= clipRight || tail.rect.right <= clipLeft};
  });
  longDiagnostic = {...longDiagnostic, readerAreaBeforeScroll: longScroll};
  await writeFile(longDiagnosticPath, JSON.stringify(longDiagnostic, null, 2) + '\n');
  assert.equal(longScroll.sameNode, true);
  assert.equal(longScroll.data, longText);
  assert.ok(longScroll.scrollWidth > longScroll.clientWidth);
  assert.equal(longScroll.tailInitiallyOutside, true,
    `The real tail must begin outside the host scrollport: ${JSON.stringify(longScroll)}`);
  await page.evaluate(() => {
    const paragraph = window.__longNode.parentElement;
    paragraph.scrollLeft = paragraph.scrollWidth - paragraph.clientWidth;
  });
  const longAfterScroll = await page.evaluate(() => {
    const node = window.__longNode;
    const paragraph = node.parentElement;
    const records = [...window.__FuriHook102.records.values()]
      .filter(item => item.node === node && item.segments).sort((a, b) => a.offset - b.offset);
    const ranges = records.flatMap(record => record.segments.map(segment => {
      const range = document.createRange();
      range.setStart(node, record.offset + segment[0]);
      range.setEnd(node, record.offset + segment[1]);
      const rect = range.getBoundingClientRect();
      return {start: record.offset + segment[0], end: record.offset + segment[1],
        reading: segment[2], rect: {left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom}};
    }));
    const clip = paragraph.getBoundingClientRect();
    const clipLeft = clip.left + paragraph.clientLeft;
    return {scrollLeft: paragraph.scrollLeft, maxScrollLeft: paragraph.scrollWidth - paragraph.clientWidth,
      clip: {left: clipLeft, right: clipLeft + paragraph.clientWidth,
        top: clip.top + paragraph.clientTop, bottom: clip.top + paragraph.clientTop + paragraph.clientHeight}, ranges};
  });
  longDiagnostic = {...longDiagnostic, readerAreaAfterScrollBeforeLayout: longAfterScroll,
    inspectionBeforeLayout: await inspect()};
  await writeFile(longDiagnosticPath, JSON.stringify(longDiagnostic, null, 2) + '\n');
  await waitFor(() => {
    const node = window.__longNode;
    const paragraph = node.parentElement;
    const records = [...window.__FuriHook102.records.values()].filter(item => item.node === node && item.segments)
      .sort((a, b) => a.offset - b.offset);
    const ranges = records.flatMap(record => record.segments.map(segment => {
      const range = document.createRange(); range.setStart(node, record.offset + segment[0]);
      range.setEnd(node, record.offset + segment[1]); return range.getBoundingClientRect();
    }));
    const clip = paragraph.getBoundingClientRect();
    const left = clip.left + paragraph.clientLeft;
    const right = left + paragraph.clientWidth;
    return paragraph.scrollLeft > 0 && ranges.every(rect => rect.left >= left && rect.right <= right);
  }, 'Host horizontal scrolling did not reveal every measured Japanese segment');
  await settle();
  const longInspection = await inspectDetailed();
  const longAnnotations = longInspection.annotations.filter(item => longNodeIds.has(item.nodeId));
  const longVisibleGeometry = await page.evaluate(() => ({
    nodePreserved: window.__longNode === window.__longNode.parentElement.firstChild,
    dataPreserved: window.__longNode.data,
    scrollLeft: window.__longNode.parentElement.scrollLeft,
    clip: (() => { const p = window.__longNode.parentElement; const r = p.getBoundingClientRect();
      const left = r.left + p.clientLeft; return {left, right: left + p.clientWidth,
        top: r.top + p.clientTop, bottom: r.top + p.clientTop + p.clientHeight}; })()
  }));
  longDiagnostic = {...longDiagnostic, readerAreaAfterScroll: longVisibleGeometry,
    inspection: longInspection, annotations: longAnnotations};
  await writeFile(longDiagnosticPath,
    JSON.stringify(longDiagnostic, null, 2) + '\n');
  await checkProductionSegments(longText, longAnnotations);
  assertGeometry(longAnnotations);

  const clippedText = '日本語学校へ行きます。';
  await page.evaluate(text => {
    const clip = document.createElement('div');
    clip.id = 'scaled-overflow-clip';
    clip.style.cssText = 'box-sizing:border-box;width:440px;height:148px;padding:42px 22px;overflow:hidden;zoom:1.15;transform:scale(1.1);transform-origin:top left;margin:12px 0;';
    const paragraph = document.createElement('p');
    paragraph.id = 'scaled-overflow-text';
    paragraph.style.cssText = 'margin:0;font-size:22px;line-height:1.35;';
    paragraph.appendChild(document.createTextNode(text));
    clip.appendChild(paragraph);
    document.body.appendChild(clip);
    window.__scaledHostNode = paragraph.firstChild;
  }, clippedText);
  await page.locator('#scaled-overflow-clip').scrollIntoViewIfNeeded();
  await settle();
  const scaledInspection = await inspect();
  const scaledNodeIds = new Set(scaledInspection.hostNodes.filter(item => item.connected
    && item.data === clippedText).map(item => item.nodeId));
  const scaledAnnotations = scaledInspection.annotations.filter(item => scaledNodeIds.has(item.nodeId));
  await checkProductionSegments(clippedText, scaledAnnotations);
  assertGeometry(scaledAnnotations);
  const clippingMeasurement = await page.locator('#scaled-overflow-clip').evaluate(element => {
    const rect = element.getBoundingClientRect();
    const scaleX = rect.width / element.offsetWidth;
    const scaleY = rect.height / element.offsetHeight;
    const left = rect.left + element.clientLeft * scaleX;
    const top = rect.top + element.clientTop * scaleY;
    return {left, top, right: left + element.clientWidth * scaleX,
      bottom: top + element.clientHeight * scaleY,
      cssZoom: getComputedStyle(element).zoom,
      transform: getComputedStyle(element).transform};
  });
  assert.equal(await page.evaluate(() => window.__scaledHostNode.isConnected
    && window.__scaledHostNode.data === '日本語学校へ行きます。'), true);
  for (const annotation of scaledAnnotations) {
    assert.ok(annotation.readingRect.left >= clippingMeasurement.left
      && annotation.readingRect.right <= clippingMeasurement.right
      && annotation.readingRect.top >= clippingMeasurement.top
      && annotation.readingRect.bottom <= clippingMeasurement.bottom,
    `Reading must remain inside scaled overflow clip: ${JSON.stringify({annotation, clippingMeasurement})}`);
  }

  const insufficientText = '日本語学校。';
  const clippedBefore = scaledInspection.skippedClippedCount;
  await page.evaluate(text => {
    const clip = document.createElement('div');
    clip.id = 'insufficient-overflow-clip';
    clip.style.cssText = 'box-sizing:border-box;width:440px;height:72px;padding:1px;overflow:hidden;margin:12px 0;';
    const paragraph = document.createElement('p');
    paragraph.id = 'insufficient-overflow-text';
    paragraph.style.cssText = 'margin:0;font-size:22px;line-height:1.35;';
    paragraph.appendChild(document.createTextNode(text));
    clip.appendChild(paragraph);
    document.body.appendChild(clip);
    const style = getComputedStyle(paragraph);
    const rect = paragraph.getBoundingClientRect();
    window.__insufficientBaseline = {lineHeight: style.lineHeight, paddingTop: style.paddingTop,
      width: rect.width, letterSpacing: style.letterSpacing};
  }, insufficientText);
  await page.locator('#insufficient-overflow-clip').scrollIntoViewIfNeeded();
  await waitFor(previousSkips => {
    const data = JSON.parse(window.FuriHook102.inspect());
    return data.hostNodes.some(node => node.connected && node.data === '日本語学校。')
      && data.skippedClippedCount > previousSkips
      && data.scan.dirtyRoots === 0 && data.scan.dirtyNodes === 0
      && data.scan.pendingBatches === 0 && data.scan.awaitingReadings === 0;
  }, 'Ruby labels without clipping clearance were not rejected', 20000, clippedBefore);
  const insufficientInspection = await inspect();
  const insufficientNodeIds = new Set(insufficientInspection.hostNodes.filter(item => item.connected
    && item.data === insufficientText).map(item => item.nodeId));
  const insufficientAnnotations = insufficientInspection.annotations.filter(
    item => insufficientNodeIds.has(item.nodeId));
  assert.equal(insufficientAnnotations.length, 0,
    'Labels that cannot fit inside the real overflow clip must not be rendered');
  const insufficientAfter = await page.locator('#insufficient-overflow-text').evaluate(element => {
    const style = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return {lineHeight: style.lineHeight, paddingTop: style.paddingTop,
      width: rect.width, letterSpacing: style.letterSpacing};
  });
  assert.deepEqual(insufficientAfter, await page.evaluate(() => window.__insufficientBaseline),
    'A clipped text block must not receive layout reservations');
  const insufficientReservation = reservationFor(await inspectDetailed(), 'insufficient-overflow-text');
  assert.ok(insufficientReservation, 'inspect(true) must record clipped block exclusion');
  assert.equal(insufficientReservation.status, 'unmanaged', JSON.stringify(insufficientReservation));
  assert.equal(insufficientReservation.reason, 'ancestor-clip', JSON.stringify(insufficientReservation));

  const burstStart = (await inspect()).analyzedSegmentCount;
  await page.evaluate(() => {
    const root = document.createElement('div');
    root.id = 'dynamic-burst';
    for (let index = 0; index < 240; index++) {
      const paragraph = document.createElement('p');
      paragraph.appendChild(document.createTextNode('学校'));
      root.appendChild(paragraph);
    }
    document.body.appendChild(root);
  });
  await waitFor(requiredSegments => {
    const data = JSON.parse(window.FuriHook102.inspect());
    return data.analyzedSegmentCount >= requiredSegments && data.scan.dirtyRoots === 0
      && data.scan.dirtyNodes === 0 && data.scan.pendingBatches === 0 && data.scan.awaitingReadings === 0;
  }, '240 real host paragraphs were not fully analyzed', 60000, burstStart + 240);
  const burstInspection = await inspect();
  assert.equal(await page.locator('#dynamic-burst > p').count(), 240);
  assert.ok(burstInspection.annotationCount <= 128);

  const oldInstanceId = await page.evaluate(() => window.fixtureState.instanceId);
  const oldGeneration = (await inspect()).generation;
  const ownedBeforeReload = await page.locator('[data-furihook-owned="1"]').count();
  assert.ok(ownedBeforeReload <= 1, 'Product may own at most its single overlay under documentElement');
  await page.reload({ waitUntil: 'load' });
  await waitFor(previousPage => window.reactFixture && window.FuriHook102
    && window.fixtureState.instanceId !== previousPage.instanceId
    && JSON.parse(window.FuriHook102.inspect()).generation > previousPage.generation
    && JSON.parse(window.FuriHook102.inspect()).scan.initialDone, 'Reloaded page initialization',
  20000, { instanceId: oldInstanceId, generation: oldGeneration });
  await settle();
  assert.equal(await page.evaluate(() => {
    const node = document.getElementById('plain');
    return node.childNodes.length === 1 && node.firstChild.data === '今日は学校。'
      && node.querySelectorAll('[data-furihook-owned="1"]').length === 0;
  }), true);
  const afterReload = await inspect();
  const staticAnnotations = annotationsFor(afterReload, '今日は学校。');
  await checkProductionSegments('今日は学校。', staticAnnotations);
  assertGeometry(staticAnnotations);

  const screenshot = await page.screenshot({ path: path.join(outputDirectory, 'webview-ruby.png') });
  const image = PngDecoder.sync.read(screenshot);
  let rubyInkPixels = 0;
  for (const annotation of staticAnnotations.filter(item => item.visible)) {
    const rect = annotation.readingRect;
    for (let y = Math.max(0, Math.floor(rect.top)); y < Math.min(image.height, Math.ceil(rect.bottom)); y++) {
      for (let x = Math.max(0, Math.floor(rect.left)); x < Math.min(image.width, Math.ceil(rect.right)); x++) {
        const offset = (image.width * y + x) * 4;
        if (image.data[offset] < 245 || image.data[offset + 1] < 245 || image.data[offset + 2] < 245) rubyInkPixels++;
      }
    }
  }
  assert.ok(rubyInkPixels > 0, 'Real browser screenshot must contain pixels in the reading label rectangles');
  assert.deepEqual(pageErrors, [], `Browser page errors: ${pageErrors.join('\n')}`);

  const report = {
    browser: 'Playwright Chromium',
    browserVersion,
    reactVersion: translationSnapshot.reactProduction ? '18.3.1' : 'unknown',
    translationNodePreserved: translationSnapshot.postNodeIdentityPreserved,
    editableInitial,
    editableAfterHostUpdate,
    originalNodePreserved: originalSnapshot.postNodeIdentityPreserved,
    reactTranslationLayout,
    reactOriginalLayout: originalLayout,
    reactOriginalReservation: originalReservation,
    translatedLayoutAfterRestoration: translatedLayout,
    restoredReservation,
    hostCssJapaneseLayout,
    protectedText,
    protectedTextAnnotations: annotationsFor(protectedInspection, protectedText),
    fixedReservation,
    insufficientReservation,
    reactOriginalAnnotations: originalAnnotations,
    retainedListAnnotations: retainedAnnotations,
    insertedListAnnotations: insertedAnnotations,
    scaledOverflowAnnotations: scaledAnnotations,
    scaledOverflowClip: clippingMeasurement,
    insufficientOverflowRenderedAnnotations: insufficientAnnotations,
    insufficientOverflowSkippedClippedCount: insufficientInspection.skippedClippedCount - clippedBefore,
    longTextLength: longText.length,
    longNode: longDiagnostic,
    burstParagraphCount: 240,
    burstAnalyzedSegments: burstInspection.analyzedSegmentCount - burstStart,
    burstVisibleAnnotations: burstInspection.annotationCount,
    reloadGeneration: afterReload.generation,
    reloadAnnotations: staticAnnotations,
    screenshot: path.join(outputDirectory, 'webview-ruby.png'),
    rubyInkPixels,
    browserErrors: pageErrors
  };
  await writeFile(path.join(outputDirectory, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
  process.stdout.write(`WEBVIEW_RECONCILIATION_E2E_REPORT=${path.join(outputDirectory, 'report.json')}\n`);
} finally {
  await context.close();
  bridge.stdin.end();
}
