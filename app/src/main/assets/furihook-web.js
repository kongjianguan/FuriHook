(function () {
  "use strict";
  if (window.__FuriHook102ScriptInstalled) return;
  window.__FuriHook102ScriptInstalled = true;

  const VERSION = 2;
  const MAX_BATCH_NODES = 24;
  const MAX_BATCH_TEXT = 12000;
  const MAX_NODE_TEXT = 900;
  const MAX_VISITED_NODES = 320;
  const MAX_DIRTY_ROOTS = 192;
  const MAX_PENDING_BATCHES = 7;
  const MAX_TRACKED_RECORDS = 4096;
  const MAX_VISIBLE_ANNOTATIONS = 128;
  const MAX_LAYOUT_RESERVATIONS = 128;
  const MAX_OCCUPANCY_NODES = 2048;
  const KANJI = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u{20000}-\u{2fa1d}]/u;
  const KANA = /[\u3040-\u30ff\u31f0-\u31ff\uff66-\uff9d]/u;
  const SKIP = "script,style,noscript,template,textarea,input,select,option,button,code,pre,ruby,rt,rp";
  const OWNED = "[data-furihook-owned='1']";
  const PAINTED_TAGS = new Set(["img", "svg", "canvas", "video", "audio", "iframe", "object", "embed",
    "button", "input", "select", "textarea", "option", "meter", "progress", "output", "fieldset"]);
  const RESERVABLE_TAGS = new Set(["p", "div", "article", "section", "blockquote", "li", "h1", "h2", "h3", "h4", "h5", "h6", "dd", "dt"]);
  const XHTML = "http://www.w3.org/1999/xhtml";
  const VIEWPORT_MARGIN = 24;
  const measureContext = document.createElement("canvas").getContext("2d");

  function rectObject(rect) {
    return {left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
      width: rect.width, height: rect.height};
  }

  function intersects(a, b) {
    return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
  }

  function createState(generation) {
    const state = {
      version: VERSION,
      generation: generation,
      nextId: 1,
      nextNodeId: 1,
      nodes: new Map(),
      nodeIds: new WeakMap(),
      records: new Map(),
      processed: new WeakSet(),
      offsets: new WeakMap(),
      languageCache: new WeakMap(),
      batches: [],
      dirtyNodes: [],
      dirtyNodeSet: new Set(),
      dirtyRoots: [],
      dirtyRootSet: new Set(),
      dirtyWalker: null,
      initialWalker: null,
      initialDone: false,
      observer: null,
      resizeObserver: null,
      observedElements: new Set(),
      overlay: null,
      layer: null,
      shadow: null,
      labelElements: new Map(),
      layoutPending: false,
      scanPending: false,
      animationPending: false,
      hidden: document.visibilityState === "hidden",
      layoutRejections: [],
      layoutReservations: new Map(),
      reservationDiagnostics: [],
      layoutExclusions: new WeakMap(),
      ownStyleWrites: new WeakMap(),
      textRectSources: new WeakMap(),
      stats: {layoutPasses: 0, skippedOverlapCount: 0, skippedClippedCount: 0,
        skippedOccludedCount: 0, skippedGeometryCount: 0, staleResultCount: 0}
    };

    function rootElement() {
      return document.documentElement;
    }

    function noteRejection(rejection) {
      if (state.layoutRejections.length < 128) state.layoutRejections.push(rejection);
    }

    function noteReservation(reservation) {
      if (reservation.element) {
        if (reservation.status === "excluded") state.layoutExclusions.set(reservation.element, reservation.reason || "excluded");
        else if (reservation.status === "reserved") state.layoutExclusions.delete(reservation.element);
      }
      const existingIndex = reservation.element
        ? state.reservationDiagnostics.findIndex(item => item.element === reservation.element) : -1;
      if (existingIndex >= 0) {
        state.reservationDiagnostics[existingIndex] = reservation;
      } else if (state.reservationDiagnostics.length < MAX_LAYOUT_RESERVATIONS) {
        state.reservationDiagnostics.push(reservation);
      }
    }

    function ensureOverlay() {
      if (state.overlay && state.overlay.isConnected) return state.overlay;
      const overlay = document.createElement("div");
      overlay.setAttribute("data-furihook-owned", "1");
      overlay.setAttribute("aria-hidden", "true");
      overlay.style.cssText = "all:initial!important;display:block!important;position:fixed!important;inset:0!important;overflow:hidden!important;pointer-events:none!important;z-index:2147483647!important;contain:strict!important;";
      state.shadow = overlay.attachShadow({mode: "closed"});
      state.layer = document.createElement("div");
      state.layer.style.cssText = "all:initial!important;position:absolute!important;inset:0!important;overflow:hidden!important;pointer-events:none!important;";
      state.shadow.appendChild(state.layer);
      rootElement().appendChild(overlay);
      state.overlay = overlay;
      return overlay;
    }

    function scheduleLayout() {
      if (state.layoutPending || state.generation < 0) return;
      state.layoutPending = true;
      requestAnimationFrame(function () {
        state.layoutPending = false;
        if (window.__FuriHook102 !== state) return;
        renderVisible();
      });
    }

    function ensureWalkers() {
      const root = rootElement();
      if (!root) return;
      if (!state.initialWalker) state.initialWalker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      if (!state.observer) {
        state.observer = new MutationObserver(function (mutations) {
          let languageChanged = false;
          let layoutChanged = false;
          const ownStyleTargets = new Set();
          for (const mutation of mutations) {
            if (mutation.target.nodeType === 1 && mutation.target.matches(OWNED)) continue;
            if (mutation.type === "attributes" && mutation.attributeName === "style"
                && state.ownStyleWrites.get(mutation.target) === mutation.target.getAttribute("style")) {
              ownStyleTargets.add(mutation.target);
              continue;
            }
            if (mutation.type === "attributes" && mutation.attributeName === "style") {
              state.ownStyleWrites.delete(mutation.target);
            }
            if (mutation.type === "characterData") {
              if (mutation.target.parentElement && mutation.target.parentElement.localName === "style") {
                releaseLayoutReservations(null, "stylesheet-change");
              }
              invalidateNode(mutation.target);
              queueDirtyNode(mutation.target);
              languageChanged = true;
              layoutChanged = true;
            } else if (mutation.type === "childList") {
              if (mutation.target.nodeType === 1 && mutation.target.closest(OWNED)) continue;
              if (isStylesheetNode(mutation.target)
                  || [...mutation.addedNodes, ...mutation.removedNodes].some(containsStylesheetNode)) {
                releaseLayoutReservations(null, "stylesheet-change");
              }
              for (const removed of mutation.removedNodes) removeDisconnected(removed);
              for (const added of mutation.addedNodes) {
                if (added.nodeType === 1 && added.matches(OWNED)) continue;
                invalidateTrackedSubtree(added);
                queueDirtyRoot(added);
              }
              languageChanged = true;
              layoutChanged = true;
            } else if (mutation.type === "attributes") {
              if (mutation.target.nodeType === 1 && mutation.target.closest(OWNED)) continue;
              if (mutation.attributeName === "class" || mutation.attributeName === "style") {
                releaseLayoutReservations(mutation.target, "host-style-change");
              } else if ((mutation.attributeName === "href" || mutation.attributeName === "rel"
                  || mutation.attributeName === "media" || mutation.attributeName === "disabled"
                  || mutation.attributeName === "type") && (isStylesheetNode(mutation.target)
                    || (mutation.target.localName === "link" && mutation.attributeName === "rel"
                      && /(?:^|\s)stylesheet(?:\s|$)/i.test(mutation.oldValue || "")))) {
                releaseLayoutReservations(null, "stylesheet-change");
              }
              if (mutation.attributeName === "lang" || mutation.attributeName === "xml:lang") {
                languageChanged = true;
                invalidateTrackedSubtree(mutation.target);
                state.processed = new WeakSet();
                queueDirtyRoot(mutation.target);
              } else if (mutation.attributeName === "contenteditable") {
                invalidateTrackedSubtree(mutation.target);
                state.processed = new WeakSet();
                queueDirtyRoot(mutation.target);
              }
              layoutChanged = true;
            }
          }
          for (const element of ownStyleTargets) state.ownStyleWrites.delete(element);
          if (languageChanged) state.languageCache = new WeakMap();
          if (layoutChanged) {
            scheduleLayout();
            scheduleScan();
          }
        });
        state.observer.observe(root, {subtree: true, childList: true, characterData: true,
          attributes: true, attributeFilter: ["class", "style", "hidden", "lang", "xml:lang", "dir", "contenteditable",
            "href", "rel", "media", "disabled", "type"], attributeOldValue: true});
      }
      if (!state.resizeObserver && typeof ResizeObserver === "function") {
        state.resizeObserver = new ResizeObserver(scheduleLayout);
      }
    }

    function nodeId(node) {
      let id = state.nodeIds.get(node);
      if (!id) {
        id = state.nextNodeId++;
        state.nodeIds.set(node, id);
      }
      return id;
    }

    function forgetRecord(id) {
      const record = state.records.get(id);
      state.records.delete(id);
      state.nodes.delete(id);
      if (!record) return;
      for (const [key, element] of state.labelElements) {
        if (key.startsWith(id + ":")) element.remove();
      }
      unobserveIfUnused(record.parent);
    }

    function unobserveIfUnused(element) {
      if (!element || !state.resizeObserver) return;
      for (const record of state.records.values()) if (record.parent === element) return;
      if (state.observedElements.delete(element)) state.resizeObserver.unobserve(element);
    }

    function invalidateNode(node) {
      for (const [id, record] of state.records) {
        if (record.node === node) forgetRecord(id);
      }
      state.processed.delete(node);
      state.offsets.delete(node);
    }

    function invalidateTrackedSubtree(root) {
      for (const [id, record] of state.records) {
        if (record.node === root || (root.nodeType === 1 && root.contains(record.node))) {
          const node = record.node;
          const offset = record.offset;
          forgetRecord(id);
          state.processed.delete(node);
          state.offsets.set(node, offset);
          queueDirtyNode(node);
        }
      }
    }

    function removeDisconnected(root) {
      for (const [id, record] of state.records) {
        if (record.node === root || (root.nodeType === 1 && root.contains(record.node))) {
          if (!record.node.isConnected) forgetRecord(id);
        }
      }
      if (root.nodeType === 1) {
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) {
          state.processed.delete(node);
          state.offsets.delete(node);
        }
      } else if (root.nodeType === 3) {
        state.processed.delete(root);
        state.offsets.delete(root);
      }
    }

    function blocked(node) {
      const parent = node.parentElement;
      if (!parent || !parent.isConnected || parent.closest(SKIP)) return true;
      if (parent.namespaceURI !== XHTML || parent.closest("[contenteditable]:not([contenteditable='false'])")) return true;
      if (parent.closest(OWNED)) return true;
      return false;
    }

    function nearestBlock(node) {
      let element = node.parentElement;
      while (element && element !== document.body && element !== rootElement()) {
        const display = getComputedStyle(element).display;
        if (display !== "inline" && display !== "contents") return element;
        element = element.parentElement;
      }
      return node.parentElement || rootElement();
    }

    function languageDecision(node, text) {
      let element = node.parentElement;
      while (element) {
        const value = element.getAttribute("lang") || element.getAttribute("xml:lang");
        if (value) {
          const language = value.trim().toLowerCase().split(/[-_]/)[0];
          if (language === "ja" || language === "jpn") return "japanese";
          if (language === "zh" || language === "ko") return "excluded";
          if (element !== document.documentElement && element !== document.body && language !== "en") break;
        }
        element = element.parentElement;
      }
      if (KANA.test(text)) return "japanese-candidate";
      const block = nearestBlock(node);
      let hasKana = state.languageCache.get(block);
      if (hasKana === undefined) {
        hasKana = KANA.test(block.textContent || "");
        state.languageCache.set(block, hasKana);
      }
      return hasKana ? "japanese-context" : "unconfirmed";
    }

    function queueDirtyNode(node) {
      if (!node || node.nodeType !== 3 || state.dirtyNodeSet.has(node)) return;
      if (state.dirtyNodes.length >= MAX_DIRTY_ROOTS) {
        restartDocumentScan();
        return;
      }
      state.dirtyNodes.push(node);
      state.dirtyNodeSet.add(node);
    }

    function queueDirtyRoot(node) {
      if (!node || state.dirtyRootSet.has(node)) return;
      if (state.dirtyRoots.length >= MAX_DIRTY_ROOTS) {
        restartDocumentScan();
        return;
      }
      state.dirtyRoots.push(node);
      state.dirtyRootSet.add(node);
    }

    function restartDocumentScan() {
      state.dirtyNodes.length = 0;
      state.dirtyNodeSet.clear();
      state.dirtyRoots.length = 0;
      state.dirtyRootSet.clear();
      state.dirtyWalker = null;
      state.initialWalker = null;
      state.initialDone = false;
      state.processed = new WeakSet();
    }

    function nextTextChunk(node) {
      const start = state.offsets.get(node) || 0;
      let end = Math.min(node.data.length, start + MAX_NODE_TEXT);
      if (end < node.data.length && /[\uD800-\uDBFF]$/.test(node.data.slice(start, end))) end--;
      return {start: start, end: end, text: node.data.slice(start, end)};
    }

    function nextDirtyNode() {
      while (state.dirtyNodes.length) {
        const node = state.dirtyNodes.shift();
        state.dirtyNodeSet.delete(node);
        if (node.isConnected) return node;
      }
      while (true) {
        if (state.dirtyWalker) {
          const node = state.dirtyWalker.nextNode();
          if (node) return node;
          state.dirtyWalker = null;
        }
        if (!state.dirtyRoots.length) return null;
        const root = state.dirtyRoots.shift();
        state.dirtyRootSet.delete(root);
        if (!root.isConnected) continue;
        if (root.nodeType === 3) return root;
        state.dirtyWalker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      }
    }

    function nextInitialNode() {
      if (!state.initialWalker || state.initialDone) return null;
      const node = state.initialWalker.nextNode();
      if (!node) state.initialDone = true;
      return node;
    }

    function appendCandidate(node, chunk, result, total) {
      if (blocked(node) || !chunk.text) {
        state.processed.add(node);
        state.offsets.delete(node);
        return total;
      }
      const more = chunk.end < node.data.length;
      if (more) {
        state.offsets.set(node, chunk.end);
        queueDirtyNode(node);
      } else {
        state.processed.add(node);
        state.offsets.delete(node);
      }
      if (!KANJI.test(chunk.text)) return total;
      const language = languageDecision(node, chunk.text);
      if (language === "excluded" || language === "unconfirmed") return total + chunk.text.length;
      const id = state.nextId++;
      const record = {id: id, node: node, nodeId: nodeId(node), offset: chunk.start,
        text: chunk.text, segments: null, language: language, parent: nearestBlock(node), labels: []};
      state.nodes.set(id, record);
      state.records.set(id, record);
      if (state.records.size > MAX_TRACKED_RECORDS) {
        forgetRecord(state.records.keys().next().value);
        state.stats.staleResultCount++;
      }
      result.items.push({id: id, offset: chunk.start, text: chunk.text});
      return total + chunk.text.length;
    }

    function takeBatch() {
      ensureWalkers();
      const result = {generation: state.generation, items: [], more: false};
      if (!state.initialWalker && !state.dirtyNodes.length && !state.dirtyRoots.length && !state.dirtyWalker) return result;
      let visited = 0;
      let dirtyVisited = 0;
      let total = 0;
      while (visited < MAX_VISITED_NODES) {
        let node = null;
        if (dirtyVisited < MAX_VISITED_NODES / 2) {
          node = nextDirtyNode();
          if (node) dirtyVisited++;
        }
        if (!node) node = nextInitialNode();
        if (!node) {
          node = nextDirtyNode();
          if (node) dirtyVisited++;
        }
        if (!node) break;
        visited++;
        if (state.processed.has(node)) continue;
        const chunk = nextTextChunk(node);
        if (result.items.length >= MAX_BATCH_NODES || total + chunk.text.length > MAX_BATCH_TEXT) {
          queueDirtyNode(node);
          break;
        }
        total = appendCandidate(node, chunk, result, total);
      }
      result.more = state.dirtyNodes.length > 0 || state.dirtyRoots.length > 0
        || state.dirtyWalker !== null || !state.initialDone;
      return result;
    }

    function scheduleScan() {
      if (state.scanPending || state.generation < 0 || state.batches.length >= MAX_PENDING_BATCHES) return;
      state.scanPending = true;
      setTimeout(function () {
        state.scanPending = false;
        if (window.__FuriHook102 !== state || state.hidden) return;
        const batch = takeBatch();
        if (batch.items.length) state.batches.push(batch);
        if (batch.more && state.batches.length < MAX_PENDING_BATCHES) scheduleScan();
      }, 80);
    }

    function observeParent(element) {
      if (!state.resizeObserver || !element || state.observedElements.has(element)
          || state.observedElements.size >= 256) return;
      state.observedElements.add(element);
      state.resizeObserver.observe(element);
    }

    function visibleViewport() {
      const viewport = window.visualViewport;
      const width = viewport ? viewport.width : document.documentElement.clientWidth;
      const height = viewport ? viewport.height : document.documentElement.clientHeight;
      const left = viewport ? viewport.offsetLeft : 0;
      const top = viewport ? viewport.offsetTop : 0;
      return {left: left, top: top, right: left + width, bottom: top + height,
        width: width, height: height, scale: viewport ? viewport.scale : 1};
    }

    function elementVisible(element) {
      let current = element;
      while (current && current.nodeType === 1) {
        const style = getComputedStyle(current);
        if (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse"
            || Number(style.opacity) === 0) return false;
        current = current.parentElement;
      }
      return true;
    }

    function clippingRect(node, viewport) {
      let clip = {left: viewport.left, top: viewport.top, right: viewport.right, bottom: viewport.bottom};
      let current = node.parentElement;
      while (current && current !== document.documentElement) {
        const style = getComputedStyle(current);
        if (/(hidden|clip|scroll|auto)/.test(style.overflowX) || /(hidden|clip|scroll|auto)/.test(style.overflowY)) {
          const rect = current.getBoundingClientRect();
          const scaleX = current.offsetWidth > 0 ? rect.width / current.offsetWidth : 1;
          const scaleY = current.offsetHeight > 0 ? rect.height / current.offsetHeight : 1;
          const clientLeft = rect.left + current.clientLeft * scaleX;
          const clientTop = rect.top + current.clientTop * scaleY;
          const client = {left: clientLeft, top: clientTop,
            right: clientLeft + current.clientWidth * scaleX,
            bottom: clientTop + current.clientHeight * scaleY};
          if (/(hidden|clip|scroll|auto)/.test(style.overflowX)) {
            clip.left = Math.max(clip.left, client.left);
            clip.right = Math.min(clip.right, client.right);
          }
          if (/(hidden|clip|scroll|auto)/.test(style.overflowY)) {
            clip.top = Math.max(clip.top, client.top);
            clip.bottom = Math.min(clip.bottom, client.bottom);
          }
        }
        current = current.parentElement;
      }
      return clip;
    }

    function textRects(block) {
      const rects = [];
      const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
      let visited = 0;
      let node;
      while ((node = walker.nextNode())) {
        if (++visited > MAX_OCCUPANCY_NODES) return null;
        if (!node.data || blocked(node) || !elementVisible(node.parentElement)) continue;
        const range = document.createRange();
        range.selectNodeContents(node);
        for (const rect of range.getClientRects()) {
          if (rect.width > 0 && rect.height > 0) {
            const geometry = rectObject(rect);
            state.textRectSources.set(geometry, {node: node, parent: node.parentElement});
            rects.push(geometry);
          }
        }
      }
      return rects;
    }

    function geometryRoot(block) {
      return block.parentElement || block;
    }

    function textStyle(parent) {
      const style = getComputedStyle(parent);
      const fontSize = Number.parseFloat(style.fontSize) || 16;
      const fontFamily = style.fontFamily || "sans-serif";
      const weight = style.fontWeight || "400";
      const fontStyle = style.fontStyle || "normal";
      const fontStretch = style.fontStretch || "normal";
      const rubySize = Math.max(8, fontSize * 0.5);
      return {fontSize: rubySize, fontFamily: fontFamily,
        font: fontStyle + " " + weight + " " + rubySize + "px " + fontFamily,
        fontStyle: fontStyle, fontWeight: weight, fontStretch: fontStretch,
        color: style.color, baseFont: style.font,
        writingMode: style.writingMode, letterSpacing: style.letterSpacing};
    }

    function labelMetrics(text, baseText, font, baseRect, transformScale) {
      const context = measureContext;
      context.font = font.font;
      if ("fontStretch" in context) context.fontStretch = font.fontStretch;
      if ("letterSpacing" in context) context.letterSpacing = font.letterSpacing;
      const metrics = context.measureText(text);
      const acceptedCanvasFont = context.font;
      context.font = font.baseFont;
      if ("fontStretch" in context) context.fontStretch = font.fontStretch;
      if ("letterSpacing" in context) context.letterSpacing = font.letterSpacing;
      const baseMetrics = context.measureText(baseText);
      const scaleX = baseMetrics.width > 0 ? baseRect.width / baseMetrics.width : transformScale.x;
      const scaleY = transformScale.y;
      const height = Math.max(font.fontSize, metrics.actualBoundingBoxAscent + metrics.actualBoundingBoxDescent);
      return {width: metrics.width * scaleX, height: height * scaleY, scaleX: scaleX, scaleY: scaleY,
        requestedFont: font.font, acceptedCanvasFont: acceptedCanvasFont};
    }

    function hasUnsupportedTransform(element) {
      let current = element;
      while (current && current !== document.documentElement) {
        const value = getComputedStyle(current).transform;
        if (value && value !== "none") {
          const matrix = new DOMMatrixReadOnly(value);
          if (Math.abs(matrix.b) > 0.001 || Math.abs(matrix.c) > 0.001
              || Math.abs(matrix.m14) > 0.001 || Math.abs(matrix.m24) > 0.001
              || Math.abs(matrix.m34) > 0.001) return true;
        }
        current = current.parentElement;
      }
      return false;
    }

    function transformScale(element) {
      let x = 1;
      let y = 1;
      let current = element;
      while (current && current !== document.documentElement) {
        const style = getComputedStyle(current);
        const zoom = Number.parseFloat(style.zoom);
        if (Number.isFinite(zoom) && zoom > 0) {
          x *= zoom;
          y *= zoom;
        }
        if (style.transform && style.transform !== "none") {
          const matrix = new DOMMatrixReadOnly(style.transform);
          x *= Math.abs(matrix.a);
          y *= Math.abs(matrix.d);
        }
        current = current.parentElement;
      }
      return {x: x, y: y};
    }

    function firstIntersection(rect, rects) {
      for (const other of rects) if (intersects(rect, other)) return other;
      return null;
    }

    function isClipped(rect, clip) {
      return rect.left < clip.left || rect.right > clip.right || rect.top < clip.top || rect.bottom > clip.bottom;
    }

    function isTransparentColor(color) {
      if (color === "transparent") return true;
      const match = /^rgba\([^)]*,\s*([+-]?(?:\d+\.?\d*|\.\d+))\s*\)$/.exec(color);
      return Boolean(match && Number(match[1]) === 0);
    }

    function occlusionEvidence(rect, node, block, region) {
      const points = [
        [(rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2],
        [rect.left + rect.width * 0.2, rect.top + rect.height * 0.2],
        [rect.left + rect.width * 0.8, rect.top + rect.height * 0.2],
        [rect.left + rect.width * 0.2, rect.top + rect.height * 0.8],
        [rect.left + rect.width * 0.8, rect.top + rect.height * 0.8]
      ];
      for (const point of points) {
        const hits = document.elementsFromPoint(point[0], point[1]);
        for (const hit of hits) {
          if (hit.matches(OWNED) || hit === node.parentElement || hit.contains(node) || hit.contains(block)) continue;
          const style = getComputedStyle(hit);
          const sameRegion = hit === region || region.contains(hit);
          const hasBorder = (style.borderTopStyle !== "none" && Number.parseFloat(style.borderTopWidth) > 0
              && !isTransparentColor(style.borderTopColor))
            || (style.borderRightStyle !== "none" && Number.parseFloat(style.borderRightWidth) > 0
              && !isTransparentColor(style.borderRightColor))
            || (style.borderBottomStyle !== "none" && Number.parseFloat(style.borderBottomWidth) > 0
              && !isTransparentColor(style.borderBottomColor))
            || (style.borderLeftStyle !== "none" && Number.parseFloat(style.borderLeftWidth) > 0
              && !isTransparentColor(style.borderLeftColor));
          const hasPaintedSurface = style.backgroundImage !== "none"
            || !isTransparentColor(style.backgroundColor)
            || style.boxShadow !== "none"
            || hasBorder || PAINTED_TAGS.has(hit.localName);
          if (sameRegion && !hasPaintedSurface) continue;
          return {point: point, blocker: hit, hits: hits};
        }
      }
      return null;
    }

    function segmentRects(record, segment) {
      const range = document.createRange();
      range.setStart(record.node, record.offset + segment[0]);
      range.setEnd(record.node, record.offset + segment[1]);
      return Array.from(range.getClientRects()).filter(rect => rect.width > 0 && rect.height > 0);
    }

    function makeLabel(segment, base, font, metrics, top, hidden) {
      const label = document.createElement("span");
      label.setAttribute("data-furihook-owned", "1");
      label.setAttribute("data-furihook-reading", segment[2]);
      label.textContent = segment[2];
      label.style.cssText = "all:initial!important;position:absolute!important;display:block!important;box-sizing:content-box!important;white-space:nowrap!important;pointer-events:none!important;user-select:none!important;-webkit-user-select:none!important;line-height:1!important;margin:0!important;padding:0!important;border:0!important;text-align:center!important;transform:translateX(-50%) scale(" + metrics.scaleX + "," + metrics.scaleY + ")!important;transform-origin:top center!important;";
      label.style.setProperty("left", ((base.left + base.right) / 2) + "px", "important");
      label.style.setProperty("top", top + "px", "important");
      label.style.setProperty("font-style", font.fontStyle, "important");
      label.style.setProperty("font-weight", font.fontWeight, "important");
      label.style.setProperty("font-size", font.fontSize + "px", "important");
      label.style.setProperty("font-family", font.fontFamily, "important");
      label.style.setProperty("font-stretch", font.fontStretch, "important");
      label.style.setProperty("color", font.color, "important");
      if (hidden) label.style.setProperty("visibility", "hidden", "important");
      return label;
    }

    function reservationBlockReason(element) {
      const style = getComputedStyle(element);
      if (!RESERVABLE_TAGS.has(element.localName)) return "unsupported-element";
      if (style.display !== "block" && style.display !== "flow-root" && style.display !== "list-item") return "unsupported-display";
      if (style.position !== "static" || style.float !== "none") return "complex-layout";
      if (style.writingMode !== "horizontal-tb" || style.contain !== "none"
          || style.columnCount !== "auto" || style.columnWidth !== "auto") return "complex-layout";
      if (element.style.getPropertyValue("height") || element.style.getPropertyValue("block-size")
          || style.maxHeight !== "none" || style.maxBlockSize !== "none") return "fixed-size";
      if (element.closest("[contenteditable]:not([contenteditable='false'])") || element.matches(SKIP)) return "interactive";
      const parentStyle = element.parentElement ? getComputedStyle(element.parentElement) : null;
      if (!parentStyle || parentStyle.display.includes("flex") || parentStyle.display.includes("grid")
          || parentStyle.display.includes("table") || parentStyle.columnCount !== "auto"
          || parentStyle.columnWidth !== "auto") return "complex-layout";
      if (style.overflowX !== "visible" || style.overflowY !== "visible"
          || (style.whiteSpace !== "normal" && style.whiteSpace !== "pre-wrap" && style.whiteSpace !== "pre-line")) return "existing-space";
      let ancestor = element.parentElement;
      while (ancestor && ancestor !== document.documentElement) {
        const ancestorStyle = getComputedStyle(ancestor);
        if (ancestorStyle.overflowX !== "visible" || ancestorStyle.overflowY !== "visible") return "ancestor-clip";
        if (ancestor.style.getPropertyValue("height") || ancestor.style.getPropertyValue("block-size")
            || ancestorStyle.maxHeight !== "none" || ancestorStyle.maxBlockSize !== "none") return "ancestor-fixed-size";
        ancestor = ancestor.parentElement;
      }
      return "";
    }

    function inlineDeclaration(element, property) {
      return {value: element.style.getPropertyValue(property), priority: element.style.getPropertyPriority(property)};
    }

    function declarationMatches(element, property, declaration) {
      return element.style.getPropertyValue(property) === declaration.value
        && element.style.getPropertyPriority(property) === declaration.priority;
    }

    function sameCssPixels(actual, requested) {
      if (actual === requested) return true;
      if (!/^-?(?:\d+|\d*\.\d+)px$/.test(actual) || !/^-?(?:\d+|\d*\.\d+)px$/.test(requested)) return false;
      return Math.abs(Number.parseFloat(actual) - Number.parseFloat(requested)) <= 0.01;
    }

    function writeReservationStyle(element, reservation, property, value) {
      element.style.setProperty(property, value);
      reservation.applied[property] = inlineDeclaration(element, property);
      state.ownStyleWrites.set(element, element.getAttribute("style"));
    }

    function restoreReservation(element, reservation) {
      for (const property of ["line-height", "padding-top"]) {
        if (!declarationMatches(element, property, reservation.applied[property])) continue;
        const original = reservation.original[property];
        if (original.value) element.style.setProperty(property, original.value, original.priority);
        else element.style.removeProperty(property);
        state.ownStyleWrites.set(element, element.getAttribute("style"));
      }
    }

    function isStylesheetNode(node) {
      return node && node.nodeType === 1
        && (node.localName === "style" || (node.localName === "link" && /(?:^|\s)stylesheet(?:\s|$)/i.test(node.rel)));
    }

    function containsStylesheetNode(node) {
      if (!node || node.nodeType !== 1) return false;
      if (isStylesheetNode(node)) return true;
      return Boolean(node.querySelector && node.querySelector("style,link[rel~='stylesheet']"));
    }

    function releaseLayoutReservations(target, reason) {
      for (const [element, reservation] of state.layoutReservations) {
        if (target && element !== target && !target.contains(element)) continue;
        restoreReservation(element, reservation);
        state.layoutReservations.delete(element);
        noteReservation({element: element, status: "restored", reason: reason,
          baseline: reservation.baseline,
          applied: {lineHeight: reservation.applied["line-height"].value,
            paddingTop: reservation.applied["padding-top"].value}, required: reservation.required});
      }
    }

    function prepareLayoutReservations(layer, viewport) {
      const needed = new Map();
      let measured = 0;
      for (const record of state.records.values()) {
        if (measured >= MAX_VISIBLE_ANNOTATIONS) break;
        if (!record.segments || !record.segments.length || !record.node.isConnected
            || record.node.data.slice(record.offset, record.offset + record.text.length) !== record.text
            || blocked(record.node) || !elementVisible(record.node.parentElement)) continue;
        const hostBox = record.node.parentElement.getBoundingClientRect();
        if (hostBox.right < viewport.left - VIEWPORT_MARGIN || hostBox.left > viewport.right + VIEWPORT_MARGIN
            || hostBox.bottom < viewport.top - VIEWPORT_MARGIN || hostBox.top > viewport.bottom + VIEWPORT_MARGIN) continue;
        const element = record.parent;
        const reason = reservationBlockReason(element);
        if (reason) {
          noteReservation({element: element,
            status: reason === "existing-space" || reason === "ancestor-clip" || reason === "ancestor-fixed-size"
              ? "unmanaged" : "excluded", reason: reason});
          continue;
        }
        for (const segment of record.segments) {
          if (measured >= MAX_VISIBLE_ANNOTATIONS) break;
          if (!Array.isArray(segment) || segment.length < 3 || !Number.isInteger(segment[0])
              || !Number.isInteger(segment[1]) || segment[0] < 0 || segment[1] <= segment[0]
              || segment[1] > record.text.length || typeof segment[2] !== "string" || !segment[2]) continue;
          const fragments = segmentRects(record, segment);
          if (fragments.length !== 1) continue;
          const base = fragments[0];
          if (base.right < viewport.left - VIEWPORT_MARGIN || base.left > viewport.right + VIEWPORT_MARGIN
              || base.bottom < viewport.top - VIEWPORT_MARGIN || base.top > viewport.bottom + VIEWPORT_MARGIN) continue;
          const font = textStyle(record.node.parentElement);
          if (font.writingMode !== "horizontal-tb" || hasUnsupportedTransform(record.node.parentElement)) continue;
          const baseText = record.text.slice(segment[0], segment[1]);
          const metrics = labelMetrics(segment[2], baseText, font, base, transformScale(record.node.parentElement));
          const gap = Math.max(1, font.fontSize * 0.16);
          const label = makeLabel(segment, base, font, metrics, 0, true);
          layer.appendChild(label);
          const labelRect = label.getBoundingClientRect();
          label.remove();
          const prospective = {left: (base.left + base.right) / 2 - labelRect.width / 2,
            right: (base.left + base.right) / 2 + labelRect.width / 2,
            top: base.top - labelRect.height - gap, bottom: base.top - gap};
          const clip = clippingRect(record.node, viewport);
          if (isClipped(prospective, clip) || isClipped(prospective, viewport)) {
            noteReservation({element: element, status: "excluded", reason: "clipped"});
            continue;
          }
          const current = needed.get(element) || {rangeHeight: 0, labelHeight: 0, gap: 0};
          current.rangeHeight = Math.max(current.rangeHeight, base.height);
          current.labelHeight = Math.max(current.labelHeight, labelRect.height);
          current.gap = Math.max(current.gap, gap);
          needed.set(element, current);
          measured++;
        }
      }

      for (const [element, reservation] of state.layoutReservations) {
        if (needed.has(element)) continue;
        restoreReservation(element, reservation);
        state.layoutReservations.delete(element);
        noteReservation({element: element, status: "restored", reason: "no-readings",
          baseline: reservation.baseline,
          applied: {lineHeight: reservation.applied["line-height"].value,
            paddingTop: reservation.applied["padding-top"].value}, required: reservation.required});
      }

      let changed = false;
      for (const [element, metrics] of needed) {
        let reservation = state.layoutReservations.get(element);
        if (reservation && (!declarationMatches(element, "line-height", reservation.applied["line-height"])
            || !declarationMatches(element, "padding-top", reservation.applied["padding-top"]))) {
          restoreReservation(element, reservation);
          state.layoutReservations.delete(element);
          reservation = null;
          noteReservation({element: element, status: "excluded", reason: "host-style-change"});
        }
        const firstReservation = !reservation;
        if (!reservation && state.layoutReservations.size >= MAX_LAYOUT_RESERVATIONS) {
          noteReservation({element: element, status: "excluded", reason: "reservation-limit"});
          continue;
        }
        const style = getComputedStyle(element);
        const currentLineHeight = Number.parseFloat(style.lineHeight);
        const baseLineHeight = Number.isFinite(currentLineHeight) ? currentLineHeight : metrics.rangeHeight;
        const currentPadding = Number.parseFloat(style.paddingTop) || 0;
        if (!reservation) {
          if (inlineDeclaration(element, "line-height").priority === "important"
              || inlineDeclaration(element, "padding-top").priority === "important") {
            noteReservation({element: element, status: "excluded", reason: "important-style"});
            continue;
          }
          reservation = {original: {"line-height": inlineDeclaration(element, "line-height"),
            "padding-top": inlineDeclaration(element, "padding-top")}, applied: {},
            baseline: {lineHeight: style.lineHeight, paddingTop: style.paddingTop},
            baseLineHeight: baseLineHeight, basePadding: currentPadding, element: element};
          state.layoutReservations.set(element, reservation);
        }
        const extra = metrics.labelHeight + metrics.gap;
        const requiredLineHeight = Math.max(reservation.baseLineHeight, metrics.rangeHeight + extra);
        const requiredPadding = reservation.basePadding + extra;
        const lineValue = requiredLineHeight + "px";
        const paddingValue = requiredPadding + "px";
        const beforeHeight = element.getBoundingClientRect().height;
        let changedElement = false;
        reservation.required = {rangeHeight: metrics.rangeHeight, labelHeight: metrics.labelHeight,
          gap: metrics.gap, lineHeight: lineValue, paddingTop: paddingValue};
        if (!sameCssPixels(element.style.getPropertyValue("line-height"), lineValue)) {
          writeReservationStyle(element, reservation, "line-height", lineValue);
          changedElement = true;
        } else if (!reservation.applied["line-height"]) {
          reservation.applied["line-height"] = inlineDeclaration(element, "line-height");
        }
        if (!sameCssPixels(element.style.getPropertyValue("padding-top"), paddingValue)) {
          writeReservationStyle(element, reservation, "padding-top", paddingValue);
          changedElement = true;
        } else if (!reservation.applied["padding-top"]) {
          reservation.applied["padding-top"] = inlineDeclaration(element, "padding-top");
        }
        if (changedElement) {
          const actualGrowth = element.getBoundingClientRect().height - beforeHeight;
          const expectedGrowth = extra * transformScale(element).y;
          if (firstReservation && actualGrowth + 0.5 < expectedGrowth) {
            restoreReservation(element, reservation);
            state.layoutReservations.delete(element);
            noteReservation({element: element, status: "excluded", reason: "fixed-size"});
            continue;
          }
          changed = true;
        }
        const computed = getComputedStyle(element);
        noteReservation({element: element, status: "reserved", reason: "",
          baseline: reservation.baseline,
          applied: {lineHeight: element.style.getPropertyValue("line-height"),
            paddingTop: element.style.getPropertyValue("padding-top")},
          required: reservation.required,
          computed: {lineHeight: computed.lineHeight, paddingTop: computed.paddingTop,
            width: computed.width}});
      }
      return changed;
    }

    function removeLabels() {
      if (state.layer) state.layer.replaceChildren();
      state.labelElements.clear();
      for (const record of state.records.values()) record.labels = [];
    }

    state.hideLabels = removeLabels;

    function renderVisible() {
      state.stats.layoutPasses++;
      if (state.hidden || document.visibilityState === "hidden" || !rootElement()) {
        removeLabels();
        return 0;
      }
      const viewport = visibleViewport();
      const overlay = ensureOverlay();
      const layer = state.layer || overlay;
      layer.replaceChildren();
      state.labelElements.clear();
      state.layoutRejections = [];
      state.layoutExclusions = new WeakMap();
      state.textRectSources = new WeakMap();
      for (const record of state.records.values()) record.labels = [];
      if (prepareLayoutReservations(layer, viewport)) {
        scheduleLayout();
        return 0;
      }
      const occupancyByRoot = new Map();
      const labelRects = [];
      let painted = 0;
      for (const record of state.records.values()) {
        if (painted >= MAX_VISIBLE_ANNOTATIONS) break;
        if (!record.segments || !record.segments.length || !record.node.isConnected
            || record.node.data.slice(record.offset, record.offset + record.text.length) !== record.text
            || blocked(record.node) || !elementVisible(record.node.parentElement)) continue;
        if (state.layoutExclusions.has(record.parent)) continue;
        const hostBox = record.node.parentElement.getBoundingClientRect();
        if (hostBox.right < viewport.left - VIEWPORT_MARGIN || hostBox.left > viewport.right + VIEWPORT_MARGIN
            || hostBox.bottom < viewport.top - VIEWPORT_MARGIN || hostBox.top > viewport.bottom + VIEWPORT_MARGIN) continue;
        observeParent(record.parent);
        const region = geometryRoot(record.parent);
        let occupied = occupancyByRoot.get(region);
        if (occupied === undefined) {
          occupied = textRects(region);
          occupancyByRoot.set(region, occupied);
        }
        if (!occupied) {
          state.stats.skippedGeometryCount++;
          noteRejection({reason: "geometry-limit", record: record, region: region});
          continue;
        }
        const clip = clippingRect(record.node, viewport);
        for (const segment of record.segments) {
          if (painted >= MAX_VISIBLE_ANNOTATIONS) break;
          if (!Array.isArray(segment) || segment.length < 3 || !Number.isInteger(segment[0])
              || !Number.isInteger(segment[1]) || segment[0] < 0 || segment[1] <= segment[0]
              || segment[1] > record.text.length || typeof segment[2] !== "string" || !segment[2]) continue;
          const fragments = segmentRects(record, segment);
          if (fragments.length !== 1) {
            if (fragments.length > 1) {
              state.stats.skippedGeometryCount++;
              noteRejection({reason: "multiple-fragments", record: record, segment: segment,
                baseRects: fragments.map(rectObject)});
            }
            continue;
          }
          const base = fragments[0];
          if (base.right < viewport.left - VIEWPORT_MARGIN || base.left > viewport.right + VIEWPORT_MARGIN
              || base.bottom < viewport.top - VIEWPORT_MARGIN || base.top > viewport.bottom + VIEWPORT_MARGIN) continue;
          const font = textStyle(record.node.parentElement);
          if (font.writingMode !== "horizontal-tb" || hasUnsupportedTransform(record.node.parentElement)) {
            state.stats.skippedGeometryCount++;
            noteRejection({reason: "unsupported-geometry", record: record, segment: segment,
              baseRect: rectObject(base), writingMode: font.writingMode});
            continue;
          }
          const baseText = record.text.slice(segment[0], segment[1]);
          const metrics = labelMetrics(segment[2], baseText, font, base, transformScale(record.node.parentElement));
          const gap = Math.max(1, font.fontSize * 0.16);
          const label = makeLabel(segment, base, font, metrics, base.top - metrics.height - gap, false);
          layer.appendChild(label);
          const labelRect = rectObject(label.getBoundingClientRect());
          if (isClipped(labelRect, clip) || isClipped(labelRect, viewport)) {
            label.remove();
            state.stats.skippedClippedCount++;
            noteRejection({reason: "clipped", record: record, segment: segment,
              baseRect: rectObject(base), readingRect: labelRect, clip: clip});
            continue;
          }
          const overlappingTextRect = firstIntersection(labelRect, occupied);
          const overlappingLabelRect = firstIntersection(labelRect, labelRects);
          if (overlappingTextRect || overlappingLabelRect) {
            label.remove();
            state.stats.skippedOverlapCount++;
            noteRejection({reason: overlappingTextRect ? "text-overlap" : "label-overlap",
              record: record, segment: segment, baseRect: rectObject(base), readingRect: labelRect,
              overlappingTextRect: overlappingTextRect,
              overlappingTextSource: overlappingTextRect ? state.textRectSources.get(overlappingTextRect) || null : null,
              overlappingLabelRect: overlappingLabelRect});
            continue;
          }
          const occlusion = occlusionEvidence(labelRect, record.node, record.parent, region);
          if (occlusion) {
            label.remove();
            state.stats.skippedOccludedCount++;
            noteRejection({reason: "occluded", record: record, segment: segment,
              baseRect: rectObject(base), readingRect: labelRect, occlusion: occlusion});
            continue;
          }
          const key = record.id + ":" + segment[0] + ":" + segment[1];
          state.labelElements.set(key, label);
          const info = {nodeId: record.nodeId, baseText: baseText,
            reading: segment[2], segmentStart: record.offset + segment[0],
            segmentEnd: record.offset + segment[1],
            baseRect: rectObject(base), readingRect: labelRect,
            requestedFont: metrics.requestedFont, acceptedCanvasFont: metrics.acceptedCanvasFont,
            computedFont: getComputedStyle(label).font,
            computedFontSize: getComputedStyle(label).fontSize,
            textRects: occupied,
            visible: true, clearance: "clear"};
          record.labels.push(info);
          labelRects.push(labelRect);
          painted++;
        }
      }
      updateAnimationFrame();
      return painted;
    }

    state.scheduleLayout = scheduleLayout;
    state.stylesheetLoaded = function (event) {
      if (!isStylesheetNode(event.target)) return;
      releaseLayoutReservations(null, "stylesheet-change");
      scheduleLayout();
    };
    function updateAnimationFrame() {
      if (state.animationPending) return;
      for (const record of state.records.values()) {
        if (!record.labels.length) continue;
        let element = record.node.parentElement;
        while (element && element !== document.documentElement) {
          if (element.getAnimations().some(animation => animation.playState === "running")) {
            state.animationPending = true;
            requestAnimationFrame(function () {
              state.animationPending = false;
              scheduleLayout();
            });
            return;
          }
          element = element.parentElement;
        }
      }
    }

    state.apply = function (payload) {
      if (!payload || payload.generation !== state.generation || window.__FuriHook102 !== state) return 0;
      const updatedRecords = [];
      for (const entry of payload.items || []) {
        const record = state.nodes.get(entry.id);
        state.nodes.delete(entry.id);
        if (!record || record.node.data.slice(entry.offset, entry.offset + entry.text.length) !== entry.text
            || record.offset !== entry.offset || record.text !== entry.text || !record.node.isConnected) {
          state.stats.staleResultCount++;
          if (record) forgetRecord(record.id);
          continue;
        }
        record.segments = Array.isArray(entry.segments) ? entry.segments : [];
        record.labels = [];
        updatedRecords.push(record);
      }
      const viewport = visibleViewport();
      const visibleUpdate = updatedRecords.some(record => record.segments.some(segment => {
        const fragments = segmentRects(record, segment);
        return fragments.some(rect => rect.right > viewport.left && rect.left < viewport.right
          && rect.bottom > viewport.top && rect.top < viewport.bottom);
      }));
      if (visibleUpdate) renderVisible();
      scheduleLayout();
      return updatedRecords.reduce((count, record) => count + record.labels.length, 0);
    };

    state.drop = function (ids) {
      for (const id of ids || []) {
        const record = state.nodes.get(id);
        if (!record) continue;
        state.nodes.delete(id);
        forgetRecord(id);
        if (record.node.isConnected) {
          state.processed.delete(record.node);
          state.offsets.set(record.node, record.offset);
          queueDirtyNode(record.node);
        }
      }
      scheduleScan();
    };
    state.pull = function (requestedGeneration) {
      if (requestedGeneration !== state.generation || window.__FuriHook102 !== state) return "";
      const batch = state.batches.shift() || {generation: state.generation, items: [], more: false};
      if (batch.items.length) scheduleScan();
      return JSON.stringify(batch);
    };
    state.installObserver = ensureWalkers;
    state.processInitial = function () {
      if (state.batches.length >= MAX_PENDING_BATCHES) return;
      const batch = takeBatch();
      if (batch.items.length) state.batches.push(batch);
      if (batch.more) scheduleScan();
    };
    function inspectElement(element) {
      if (!element || element.nodeType !== 1) return null;
      const style = getComputedStyle(element);
      const borderBox = element.getBoundingClientRect();
      return {tag: element.tagName, id: element.id || "", className: String(element.className || "").slice(0, 120),
        textSample: (element.textContent || "").slice(0, 120), rect: rectObject(borderBox),
        display: style.display, visibility: style.visibility, opacity: style.opacity,
        color: style.color, fontSize: style.fontSize, lineHeight: style.lineHeight,
        pointerEvents: style.pointerEvents, backgroundColor: style.backgroundColor,
        backgroundImage: style.backgroundImage, boxShadow: style.boxShadow,
        borderTopColor: style.borderTopColor, borderTopWidth: style.borderTopWidth};
    }
    function inspectTextSource(source) {
      if (!source || !source.node) return null;
      return {nodeId: nodeId(source.node), textSample: source.node.data.slice(0, 160),
        parent: inspectElement(source.parent)};
    }
    function inspectRejection(rejection) {
      const record = rejection.record;
      const result = {reason: rejection.reason, nodeId: record.nodeId,
        segmentStart: rejection.segment ? record.offset + rejection.segment[0] : record.offset,
        segmentEnd: rejection.segment ? record.offset + rejection.segment[1] : record.offset + record.text.length,
        targetText: rejection.segment
          ? record.node.data.slice(record.offset + rejection.segment[0], record.offset + rejection.segment[1])
          : record.text,
        reading: rejection.segment ? rejection.segment[2] : "",
        baseRect: rejection.baseRect || null, readingRect: rejection.readingRect || null,
        clip: rejection.clip || null, baseRects: rejection.baseRects || null,
        writingMode: rejection.writingMode || ""};
      if (rejection.overlappingTextRect) {
        result.overlappingTextRects = [{rect: rejection.overlappingTextRect,
          source: inspectTextSource(rejection.overlappingTextSource)}];
      }
      if (rejection.overlappingLabelRect) result.overlappingLabelRects = [rejection.overlappingLabelRect];
      if (rejection.occlusion) {
        result.occlusion = {point: rejection.occlusion.point,
          blocker: inspectElement(rejection.occlusion.blocker),
          hits: rejection.occlusion.hits.map(inspectElement)};
      }
      return result;
    }
    state.inspect = function (includeDiagnostics) {
      const viewport = visibleViewport();
      const annotations = [];
      const hostNodes = new Map();
      const uniqueNodeIds = new Set();
      let annotationCount = 0;
      let visibleAnnotationCount = 0;
      let analyzedSegmentCount = 0;
      for (const record of state.records.values()) {
        uniqueNodeIds.add(record.nodeId);
        if (record.segments) analyzedSegmentCount += record.segments.length;
        if (!hostNodes.has(record.nodeId) && hostNodes.size < 100) {
          const siblings = record.node.parentNode ? Array.from(record.node.parentNode.childNodes) : [];
          hostNodes.set(record.nodeId, {nodeId: record.nodeId, connected: record.node.isConnected,
            data: record.node.data, parentTag: record.node.parentElement ? record.node.parentElement.tagName : "",
            parentElementId: record.node.parentElement ? nodeId(record.node.parentElement) : 0,
            childIndex: siblings.indexOf(record.node)});
        }
        for (const label of record.labels) {
          annotationCount++;
          const onScreen = label.readingRect.right > viewport.left && label.readingRect.left < viewport.right
            && label.readingRect.bottom > viewport.top && label.readingRect.top < viewport.bottom;
          if (onScreen) visibleAnnotationCount++;
          if (annotations.length < MAX_VISIBLE_ANNOTATIONS) annotations.push(label);
        }
      }
      return JSON.stringify({generation: state.generation, active: state.generation >= 0 && !state.hidden,
        hostNodeCount: uniqueNodeIds.size,
        cachedReadingCount: Array.from(state.records.values()).filter(record => record.segments).length,
        analyzedSegmentCount: analyzedSegmentCount,
        annotationCount: annotationCount, visibleAnnotationCount: visibleAnnotationCount,
        skippedOverlapCount: state.stats.skippedOverlapCount, skippedClippedCount: state.stats.skippedClippedCount,
        skippedOccludedCount: state.stats.skippedOccludedCount, skippedGeometryCount: state.stats.skippedGeometryCount,
        staleResultCount: state.stats.staleResultCount, layoutPasses: state.stats.layoutPasses,
        rejections: includeDiagnostics ? state.layoutRejections.map(inspectRejection) : [],
        layoutReservations: includeDiagnostics ? state.reservationDiagnostics.map(item => ({
          elementId: item.element ? item.element.id || "" : "",
          nodeId: item.element ? nodeId(item.element) : 0,
          tag: item.element ? item.element.tagName : "",
          status: item.status, reason: item.reason || "",
          baseline: item.baseline || null,
          applied: item.applied && item.applied.lineHeight !== undefined
            ? {lineHeight: item.applied.lineHeight, paddingTop: item.applied.paddingTop} : null,
          required: item.required || null,
          computed: item.computed || null
        })) : [],
        devicePixelRatio: window.devicePixelRatio || 1,
        visualViewport: {offsetLeft: viewport.left, offsetTop: viewport.top, width: viewport.width,
          height: viewport.height, scale: viewport.scale},
        scan: {dirtyNodes: state.dirtyNodes.length, dirtyRoots: state.dirtyRoots.length,
          dirtyWalker: state.dirtyWalker !== null, initialDone: state.initialDone,
          pendingBatches: state.batches.length, awaitingReadings: state.nodes.size},
        hostNodes: Array.from(hostNodes.values()), annotations: annotations});
    };
    state.dispose = function () {
      state.generation = -1;
      if (state.observer) state.observer.disconnect();
      if (state.resizeObserver) state.resizeObserver.disconnect();
      window.removeEventListener("scroll", scheduleLayout, true);
      window.removeEventListener("resize", scheduleLayout);
      if (window.visualViewport) {
        window.visualViewport.removeEventListener("scroll", scheduleLayout);
        window.visualViewport.removeEventListener("resize", scheduleLayout);
      }
      document.removeEventListener("visibilitychange", visibilityChanged);
      window.removeEventListener("pagehide", pageHidden);
      window.removeEventListener("pageshow", pageShown);
      document.removeEventListener("transitionrun", state.scheduleLayout, true);
      document.removeEventListener("animationstart", state.scheduleLayout, true);
      document.removeEventListener("load", state.stylesheetLoaded, true);
      if (document.fonts) document.fonts.removeEventListener("loadingdone", state.scheduleLayout);
      for (const [element, reservation] of state.layoutReservations) restoreReservation(element, reservation);
      state.layoutReservations.clear();
      if (state.overlay) state.overlay.remove();
      state.records.clear();
      state.nodes.clear();
    };
    return state;
  }

  function visibilityChanged() {
    const state = window.__FuriHook102;
    if (!state || state.generation < 0) return;
    state.hidden = document.visibilityState === "hidden";
    if (state.hidden) state.hideLabels();
    else {
      state.installObserver();
      state.processInitial();
      state.scheduleLayout && state.scheduleLayout();
    }
  }

  function pageHidden() {
    const state = window.__FuriHook102;
    if (!state || state.generation < 0) return;
    state.hidden = true;
    state.hideLabels();
  }

  function pageShown() {
    const state = window.__FuriHook102;
    if (!state || state.generation < 0) return;
    state.hidden = false;
    state.installObserver();
    state.processInitial();
    state.scheduleLayout && state.scheduleLayout();
  }

  window.__FuriHook102 = window.__FuriHook102 || null;
  window.FuriHook102 = {
    install: function (generation) {
      if (window.__FuriHook102 && window.__FuriHook102.version === VERSION
          && window.__FuriHook102.generation === generation) {
        window.__FuriHook102.installObserver();
        window.__FuriHook102.processInitial();
        return true;
      }
      if (window.__FuriHook102 && window.__FuriHook102.dispose) window.__FuriHook102.dispose();
      const state = createState(generation);
      window.__FuriHook102 = state;
      state.installObserver();
      state.processInitial();
      window.addEventListener("scroll", state.scheduleLayout, {capture: true, passive: true});
      window.addEventListener("resize", state.scheduleLayout, {passive: true});
      if (window.visualViewport) {
        window.visualViewport.addEventListener("scroll", state.scheduleLayout, {passive: true});
        window.visualViewport.addEventListener("resize", state.scheduleLayout, {passive: true});
      }
      document.addEventListener("visibilitychange", visibilityChanged);
      window.addEventListener("pagehide", pageHidden);
      window.addEventListener("pageshow", pageShown);
      document.addEventListener("transitionrun", state.scheduleLayout, true);
      document.addEventListener("animationstart", state.scheduleLayout, true);
      document.addEventListener("load", state.stylesheetLoaded, true);
      if (document.fonts) {
        document.fonts.ready.then(state.scheduleLayout);
        document.fonts.addEventListener("loadingdone", state.scheduleLayout);
      }
      return true;
    },
    pull: function (generation) {
      return window.__FuriHook102 ? window.__FuriHook102.pull(generation) : "";
    },
    apply: function (json) {
      if (!window.__FuriHook102) return 0;
      return window.__FuriHook102.apply(JSON.parse(json));
    },
    drop: function (json) {
      if (!window.__FuriHook102) return;
      window.__FuriHook102.drop(JSON.parse(json));
    },
    inspect: function (includeDiagnostics) {
      return window.__FuriHook102 ? window.__FuriHook102.inspect(includeDiagnostics === true) : "";
    },
    dispose: function () {
      if (!window.__FuriHook102) return;
      window.__FuriHook102.dispose();
      window.__FuriHook102 = null;
    }
  };
})();
