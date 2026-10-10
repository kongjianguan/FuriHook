(function () {
  "use strict";
  if (window.__FuriHook102ScriptInstalled) return;
  window.__FuriHook102ScriptInstalled = true;

  const VERSION = 1;
  const MAX_BATCH_NODES = 24;
  const MAX_BATCH_TEXT = 12000;
  const MAX_NODE_TEXT = 900;
  const MAX_VISITED_NODES = 320;
  const MAX_DIRTY_ROOTS = 192;
  const MAX_PENDING_BATCHES = 7;
  const KANJI = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u{20000}-\u{2fa1d}]/u;
  const SKIP = "script,style,noscript,template,textarea,input,select,option,button,code,pre,ruby,rt,rp";

  function createState(generation) {
    const state = {
      version: VERSION,
      generation: generation,
      nextId: 1,
      nodes: new Map(),
      processed: new WeakSet(),
      offsets: new WeakMap(),
      batches: [],
      dirtyNodes: [],
      dirtyNodeSet: new Set(),
      dirtyRoots: [],
      dirtyRootSet: new Set(),
      dirtyWalker: null,
      initialWalker: null,
      initialDone: false,
      observer: null,
      scanPending: false
    };

    function rootElement() {
      return document.documentElement;
    }

    function ensureWalkers() {
      const root = rootElement();
      if (!root) return;
      if (!state.initialWalker) state.initialWalker = document.createTreeWalker(root, 4);
      if (!state.observer) {
        state.observer = new MutationObserver(function (mutations) {
          for (const mutation of mutations) {
            if (mutation.type === "characterData") {
              state.processed.delete(mutation.target);
              state.offsets.delete(mutation.target);
              queueDirtyNode(mutation.target);
            } else if (mutation.type === "childList") {
              for (const added of mutation.addedNodes) queueDirtyRoot(added);
            }
          }
          scheduleScan();
        });
      }
      state.observer.observe(root, {subtree: true, childList: true, characterData: true});
    }

    function blocked(node) {
      const parent = node.parentElement;
      if (!parent || !parent.isConnected || parent.closest(SKIP)) return true;
      if (parent.namespaceURI !== "http://www.w3.org/1999/xhtml") return true;
      if (parent.closest("[contenteditable]:not([contenteditable='false'])")) return true;
      if (parent.closest("[data-furihook-owned='1']")) return true;
      return false;
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
        state.dirtyWalker = document.createTreeWalker(root, 4);
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
      const id = state.nextId++;
      state.nodes.set(id, {node: node, offset: chunk.start, text: chunk.text});
      result.items.push({id: id, offset: chunk.start, text: chunk.text});
      return total + chunk.text.length;
    }

    function takeBatch() {
      ensureWalkers();
      const result = {generation: state.generation, items: [], more: false};
      if (!state.initialWalker && !state.dirtyNodes.length && !state.dirtyRoots.length && !state.dirtyWalker) {
        return result;
      }
      let visited = 0;
      let dirtyVisited = 0;
      let total = 0;
      while (visited < MAX_VISITED_NODES) {
        let node = null;
        let dirty = false;
        if (dirtyVisited < MAX_VISITED_NODES / 2) {
          node = nextDirtyNode();
          dirty = node !== null;
          if (dirty) dirtyVisited++;
        }
        if (!node) node = nextInitialNode();
        if (!node) {
          node = nextDirtyNode();
          dirty = node !== null;
          if (dirty) dirtyVisited++;
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
        if (window.__FuriHook102 !== state) return;
        const batch = takeBatch();
        if (batch.items.length) state.batches.push(batch);
        if (batch.more && state.batches.length < MAX_PENDING_BATCHES) scheduleScan();
      }, 80);
    }

    function wrapTextNode(node, offset, original, segments) {
      if (!node.isConnected || blocked(node)
          || node.data.slice(offset, offset + original.length) !== original) {
        if (node.isConnected) {
          state.processed.delete(node);
          state.offsets.delete(node);
          queueDirtyNode(node);
        }
        return 0;
      }
      const fragment = document.createDocumentFragment();
      if (offset > 0) fragment.appendChild(document.createTextNode(node.data.slice(0, offset)));
      let cursor = 0;
      let rubyCount = 0;
      for (const segment of segments) {
        const start = segment[0];
        const end = segment[1];
        const reading = segment[2];
        if (start < cursor || end <= start || end > original.length || !reading) continue;
        if (start > cursor) fragment.appendChild(document.createTextNode(original.slice(cursor, start)));
        const ruby = document.createElement("ruby");
        ruby.setAttribute("data-furihook-owned", "1");
        ruby.setAttribute("style", "ruby-position:over;ruby-align:center");
        const rb = document.createElement("rb");
        rb.textContent = original.slice(start, end);
        const rt = document.createElement("rt");
        rt.setAttribute("data-furihook-owned", "1");
        rt.setAttribute("style", "font-size:0.5em;line-height:1;user-select:none;-webkit-user-select:none");
        rt.textContent = reading;
        ruby.appendChild(rb);
        ruby.appendChild(rt);
        fragment.appendChild(ruby);
        cursor = end;
        rubyCount++;
      }
      if (rubyCount === 0) return 0;
      if (cursor < original.length) fragment.appendChild(document.createTextNode(original.slice(cursor)));
      const tailStart = offset + original.length;
      if (tailStart < node.data.length) fragment.appendChild(document.createTextNode(node.data.slice(tailStart)));
      const parent = node.parentNode;
      if (state.initialWalker && state.initialWalker.currentNode === node) {
        state.initialWalker.currentNode = parent;
      }
      if (state.dirtyWalker && state.dirtyWalker.currentNode === node) {
        state.dirtyWalker.currentNode = parent;
      }
      parent.replaceChild(fragment, node);
      return rubyCount;
    }

    state.apply = function (payload) {
      if (payload.generation !== state.generation || window.__FuriHook102 !== state) return 0;
      let applied = 0;
      for (const entry of payload.items) {
        const tracked = state.nodes.get(entry.id);
        state.nodes.delete(entry.id);
        if (tracked) applied += wrapTextNode(tracked.node, entry.offset, entry.text, entry.segments);
      }
      return applied;
    };
    state.drop = function (ids) {
      for (const id of ids) {
        const tracked = state.nodes.get(id);
        state.nodes.delete(id);
        if (!tracked || !tracked.node.isConnected) continue;
        state.processed.delete(tracked.node);
        state.offsets.set(tracked.node, tracked.offset);
        queueDirtyNode(tracked.node);
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
    return state;
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
      if (window.__FuriHook102 && window.__FuriHook102.observer) window.__FuriHook102.observer.disconnect();
      const state = createState(generation);
      window.__FuriHook102 = state;
      state.installObserver();
      state.processInitial();
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
    }
  };

  document.addEventListener("copy", function (event) {
    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0 || !selection.toString()) return;
    const range = selection.getRangeAt(0);
    const container = range.commonAncestorContainer.nodeType === 1
      ? range.commonAncestorContainer : range.commonAncestorContainer.parentElement;
    if (!container || !(container.closest("ruby[data-furihook-owned='1']")
        || container.matches("ruby[data-furihook-owned='1']")
        || container.querySelector("ruby[data-furihook-owned='1']"))) return;
    const wrapper = document.createElement("div");
    wrapper.appendChild(range.cloneContents());
    for (const annotation of wrapper.querySelectorAll("rt[data-furihook-owned='1']")) annotation.remove();
    if (event.clipboardData) {
      event.clipboardData.setData("text/plain", wrapper.textContent || "");
      event.clipboardData.setData("text/html", wrapper.innerHTML);
      event.preventDefault();
    }
  }, true);
})();
