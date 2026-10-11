# WebView reconciliation acceptance

The browser run fails when any of these conditions occurs:

- The production WebView script or React 18.3.1 fixture cannot load in Chromium.
- React's translated Chinese text receives a Ruby annotation, or switching between translation and Japanese source changes the original paragraph or Text node identity.
- Updating keyed React rows replaces the retained row/Text node, leaves the removed row connected, or fails to render the inserted row.
- An annotation does not match the production Kuromoji output for the exact source Text node and UTF-16 offsets.
- A rendered annotation is absent from the viewport, overlaps source text, or is not positioned above its base text.
- A screenshot sampled from the real browser has no glyph pixels inside a rendered reading rectangle.
- Dynamic host updates, long Text nodes, the 240-paragraph analysis burst, scan queue draining, or a full page reload leave stale or incomplete annotations.
- The product renderer inserts owned elements into a host subtree or changes host text-node data/parentage.
- Ruby spacing changes the host block width or letter spacing, fails to restore owned spacing when Japanese text returns to Chinese, or overwrites a host CSS update.
- Fixed text or interactive controls receive annotations, or a tightly clipped block receives annotations without enough actual space.
- Editing a real contenteditable Text node changes its identity or allows a Ruby annotation inside the editor.

The run writes the machine-readable report and browser PNG into the ignored `work/webview-reconciliation-e2e/` directory.
