package dev.furihook.testapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.webkit.WebView;
import android.view.Window;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.furihook.engine.KuromojiReadingEngine;
import dev.furihook.engine.RubySegment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class RubyWebViewHookE2ETest {
    private static final String ORIGINAL = "今日は学校。";
    private static final String COPY_TEXT = "来週に映画を見ます。";
    private static final String UPDATED = "明日は図書館へ行きます。";
    private static final String REACT_TRANSLATION = "今天去学校学习日语。";
    private static final String REACT_ORIGINAL = "今日は学校へ行きます。";
    private static final String REACT_RETAINED_ROW = "今日は大学。";
    private static final String REACT_INSERTED_ROW = "明日学校へ行く。";
    private static final String LONG_JAPANESE = "週末に映画館へ行きます。";
    private static final KuromojiReadingEngine ENGINE = new KuromojiReadingEngine();

    @Test
    public void realWebViewKeepsReactDomAndDrawsDictionaryRubyWithoutChangingHostText()
            throws Exception {
        boolean requireFuriHook = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("requireFuriHook", "false"));
        assumeTrue("设置 -e requireFuriHook true 后才运行真实 LSPosed Hook 测试",
                requireFuriHook);

        ClipboardManager clipboard = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSystemService(ClipboardManager.class);
        assertTrue("设备必须提供系统剪贴板服务", clipboard != null);
        try (ActivityScenario<WebViewRubyFixtureActivity> scenario =
                     ActivityScenario.launch(WebViewRubyFixtureActivity.class)) {
            awaitPageLoaded(scenario);
            awaitJavascriptCondition(scenario,
                    "window.fixtureState && window.reactFixture && window.FuriHook102"
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.initialDone");
            scrollIntoView(scenario, "plain");
            awaitJavascriptCondition(scenario, hostHasAnnotationCondition(ORIGINAL, "今日"));

            JSONObject initial = inspect(scenario);
            JSONObject reactTranslationLayout = elementLayout(scenario, "react-current");
            awaitJavascript(scenario,
                    "(function(){window.__e2eEditableNode=document.getElementById('editable').firstChild;return true})()");
            JSONObject initialPage = pageSnapshot(scenario);
            assertTrue("日语正文必须包含生产引擎生成的读音记录",
                    hostTextHasRuby(scenario, "plain"));
            assertEquals("宿主初始 contenteditable 文本应保持fixture实际值",
                    "今日は学校。", initialPage.getString("editableText"));
            assertFalse("contenteditable 初始文本不得接受注音",
                    hostTextHasRuby(scenario, "editable"));
            JSONArray plainAnnotations = annotationsForText(initial, ORIGINAL);
            assertMatchesProductionEngine(ORIGINAL, plainAnnotations);
            JSONObject staticDom = pageSnapshot(scenario);
            assertEquals(ORIGINAL, staticDom.getString("plainText"));
            assertTrue("静态正文 Text 节点和父子关系必须保留",
                    staticDom.getBoolean("plainNodeIdentityPreserved"));
            assertEquals("日本語のリンク", staticDom.getString("linkText"));
            assertEquals("#link-result", staticDom.getString("linkHref"));
            assertTrue("链接正文也必须使用生产词典注音",
                    annotationsForText(initial, "日本語のリンク").length() > 0);

            JSONObject translation = reactSnapshot(scenario);
            assertEquals(REACT_TRANSLATION, translation.getString("postText"));
            assertEquals(1, translation.getInt("postChildCount"));
            assertTrue("React 初始文本节点身份必须可追踪",
                    translation.getBoolean("postNodeIdentityPreserved"));
            assertTrue("React 初始文本节点必须仍是正文的直接子节点",
                    translation.getBoolean("postNodeParentPreserved"));
            assertEquals(REACT_TRANSLATION, translation.getString("postNodeData"));
            assertEquals(0, annotationsForText(initial, REACT_TRANSLATION).length());
            assertFalse("中文译文不得出现注音", containsAnnotationForText(initial, REACT_TRANSLATION));

            setReactMode(scenario, "original");
            scrollIntoView(scenario, "react-current");
            awaitJavascriptCondition(scenario,
                    "window.reactFixture.inspect().mode==='original'"
                            + " && window.reactFixture.inspect().postText==='" + REACT_ORIGINAL + "'"
                            + " && window.reactFixture.inspect().postNodeIdentityPreserved"
                            + " && window.reactFixture.inspect().postNodeParentPreserved"
                            + " && window.reactFixture.inspect().postNodeData==='" + REACT_ORIGINAL + "'"
                            + " && " + hostHasAnnotationCondition(REACT_ORIGINAL, "今日"));
            JSONObject reactOriginal = reactSnapshot(scenario);
            assertTrue("切换原文后 React 必须保留它管理的同一个 Text 节点",
                    reactOriginal.getBoolean("postNodeIdentityPreserved"));
            assertTrue("切换原文后 Text 节点必须仍属于原来的段落",
                    reactOriginal.getBoolean("postNodeParentPreserved"));
            assertEquals(1, reactOriginal.getInt("postChildCount"));
            assertEquals(REACT_ORIGINAL, reactOriginal.getString("postNodeData"));
            JSONArray reactAnnotations = annotationsForText(inspect(scenario), REACT_ORIGINAL);
            assertMatchesProductionEngine(REACT_ORIGINAL, reactAnnotations);
            assertClearLayout(reactAnnotations);
            JSONObject reactOriginalLayout = elementLayout(scenario, "react-current");
            assertEquals("Ruby 预留空间不得改变宿主段落宽度",
                    reactTranslationLayout.getDouble("width"), reactOriginalLayout.getDouble("width"), 0.01);
            assertEquals("Ruby 预留空间不得改变宿主字距",
                    reactTranslationLayout.getString("letterSpacing"),
                    reactOriginalLayout.getString("letterSpacing"));
            JSONObject originalReservation = reservationFor(inspectDetailed(scenario), "react-current");
            assertReservedSpace(originalReservation);
            JSONObject pixelEvidence = captureRubyPixels(scenario, reactAnnotations,
                    inspect(scenario).getDouble("devicePixelRatio"));

            awaitJavascript(scenario,
                    "(function(){var style=document.createElement('style');style.id='host-css-update';"
                            + "style.textContent='#react-current.e2e-host-style{font-size:23px;"
                            + "color:rgb(12,34,56);letter-spacing:1.25px;line-height:58px;padding-top:18px}';document.head.appendChild(style);"
                            + "document.getElementById('react-current').classList.add('e2e-host-style');return true})()");
            awaitJavascriptCondition(scenario,
                    "(function(){var e=document.getElementById('react-current'),s=getComputedStyle(e),"
                            + "a=JSON.parse(window.FuriHook102.inspect(true)).layoutReservations.filter(function(x){"
                            + "return x.elementId==='react-current'}).pop();return s.lineHeight==='58px'&&a"
                            + "&&a.status==='reserved'&&a.baseline.lineHeight==='58px'"
                            + "&&a.baseline.paddingTop==='18px'})()");
            JSONObject hostCssJapaneseLayout = elementLayout(scenario, "react-current");
            assertEquals("23px", hostCssJapaneseLayout.getString("fontSize"));
            assertEquals("rgb(12, 34, 56)", hostCssJapaneseLayout.getString("color"));
            assertEquals("1.25px", hostCssJapaneseLayout.getString("letterSpacing"));
            assertEquals("58px", hostCssJapaneseLayout.getString("lineHeight"));
            assertEquals(reactTranslationLayout.getDouble("width"),
                    hostCssJapaneseLayout.getDouble("width"), 0.01);
            JSONObject classReservation = reservationFor(inspectDetailed(scenario), "react-current");
            assertEquals("reserved", classReservation.getString("status"));
            assertEquals("58px", classReservation.getJSONObject("computed").getString("lineHeight"));
            assertTrue("Ruby 预留必须保留宿主 class 顶部间距",
                    parseCssPixels(hostCssJapaneseLayout.getString("paddingTop")) >= 18);

            setReactMode(scenario, "translation");
            awaitJavascriptCondition(scenario,
                    "window.reactFixture.inspect().mode==='translation'"
                            + " && window.reactFixture.inspect().postNodeData==='" + REACT_TRANSLATION + "'");
            JSONObject classRestoredLayout = elementLayout(scenario, "react-current");
            JSONObject classRestoredReservation = reservationFor(
                    inspectDetailed(scenario), "react-current");
            assertEquals("58px", classRestoredLayout.getString("lineHeight"));
            assertEquals("18px", classRestoredLayout.getString("paddingTop"));
            assertEquals("restored", classRestoredReservation.getString("status"));

            setReactMode(scenario, "original");
            awaitJavascriptCondition(scenario,
                    "window.reactFixture.inspect().mode==='original'"
                            + " && window.reactFixture.inspect().postText==='" + REACT_ORIGINAL + "'"
                            + " && " + hostHasAnnotationCondition(REACT_ORIGINAL, "今日"));
            JSONObject classReappliedReservation = reservationFor(
                    inspectDetailed(scenario), "react-current");
            assertEquals("58px", classReappliedReservation.getJSONObject("baseline").getString("lineHeight"));
            assertEquals("18px", classReappliedReservation.getJSONObject("baseline").getString("paddingTop"));

            awaitJavascript(scenario,
                    "(function(){var e=document.getElementById('react-current');"
                            + "e.style.lineHeight='62px';e.style.paddingTop='24px';return true})()");
            awaitJavascriptCondition(scenario,
                    "(function(){var e=document.getElementById('react-current'),s=getComputedStyle(e),"
                            + "a=JSON.parse(window.FuriHook102.inspect(true)).layoutReservations.filter(function(x){"
                            + "return x.elementId==='react-current'}).pop();return s.lineHeight==='62px'"
                            + "&&parseFloat(s.paddingTop)>=24&&a&&a.status==='reserved'"
                            + "&&a.baseline.lineHeight==='62px'&&a.baseline.paddingTop==='24px'})()");
            JSONObject hostInlineJapaneseLayout = elementLayout(scenario, "react-current");
            JSONObject inlineReservation = reservationFor(inspectDetailed(scenario), "react-current");
            assertEquals("62px", hostInlineJapaneseLayout.getString("lineHeight"));
            assertTrue(parseCssPixels(hostInlineJapaneseLayout.getString("paddingTop")) >= 24);
            assertEquals("62px", inlineReservation.getJSONObject("computed").getString("lineHeight"));
            assertEquals("62px", inlineReservation.getJSONObject("baseline").getString("lineHeight"));
            assertEquals("24px", inlineReservation.getJSONObject("baseline").getString("paddingTop"));

            setReactMode(scenario, "translation");
            awaitJavascriptCondition(scenario,
                    "window.reactFixture.inspect().mode==='translation'"
                            + " && window.reactFixture.inspect().postNodeIdentityPreserved"
                            + " && window.reactFixture.inspect().postNodeParentPreserved"
                            + " && window.reactFixture.inspect().postNodeData==='" + REACT_TRANSLATION + "'"
                            + " && !" + hostHasAnnotationCondition(REACT_TRANSLATION, "今日"));
            JSONObject translatedAgain = reactSnapshot(scenario);
            assertTrue("返回译文时 React 仍须保留同一个 Text 节点",
                    translatedAgain.getBoolean("postNodeIdentityPreserved"));
            assertTrue("返回译文时 React 文本节点仍须位于原段落中",
                    translatedAgain.getBoolean("postNodeParentPreserved"));
            assertEquals(REACT_TRANSLATION, translatedAgain.getString("postNodeData"));
            assertFalse("中文译文更新后不得留下日语注音", containsAnnotationForText(
                    inspect(scenario), REACT_TRANSLATION));
            JSONObject translatedLayout = elementLayout(scenario, "react-current");
            assertEquals("译文提交后必须保留宿主最新顶部间距", "24px",
                    translatedLayout.getString("paddingTop"));
            assertEquals("译文提交后必须保留宿主最新行高", "62px",
                    translatedLayout.getString("lineHeight"));
            assertEquals("1.25px", translatedLayout.getString("letterSpacing"));
            assertEquals("23px", translatedLayout.getString("fontSize"));
            assertEquals("rgb(12, 34, 56)", translatedLayout.getString("color"));
            JSONObject restoredReservation = reservationFor(inspectDetailed(scenario), "react-current");
            assertTrue("inspect(true) 必须记录宿主样式恢复结果",
                    restoredReservation != null && "restored".equals(restoredReservation.getString("status")));

            setReactMode(scenario, "original");
            awaitJavascriptCondition(scenario,
                    "window.reactFixture.inspect().mode==='original'"
                            + " && window.reactFixture.inspect().postText==='" + REACT_ORIGINAL + "'"
                            + " && " + hostHasAnnotationCondition(REACT_ORIGINAL, "今日"));
            JSONObject reannotatedLayout = elementLayout(scenario, "react-current");
            JSONObject reannotatedReservation = reservationFor(inspectDetailed(scenario), "react-current");
            assertEquals("62px", reannotatedLayout.getString("lineHeight"));
            assertTrue(parseCssPixels(reannotatedLayout.getString("paddingTop")) >= 24);
            assertEquals("62px", reannotatedReservation.getJSONObject("baseline").getString("lineHeight"));
            assertEquals("24px", reannotatedReservation.getJSONObject("baseline").getString("paddingTop"));
            setReactMode(scenario, "translation");
            awaitJavascriptCondition(scenario,
                    "window.reactFixture.inspect().mode==='translation'"
                            + " && window.reactFixture.inspect().postNodeData==='" + REACT_TRANSLATION + "'"
                            + " && getComputedStyle(document.getElementById('react-current')).lineHeight==='62px'"
                            + " && getComputedStyle(document.getElementById('react-current')).paddingTop==='24px'");

            String protectedText = "日本語学校。";
            awaitJavascript(scenario,
                    "(function(){var t='" + protectedText + "',fixed=document.createElement('p');"
                            + "fixed.id='fixed-japanese';fixed.textContent=t;fixed.style.cssText='position:fixed;"
                            + "right:12px;bottom:12px;z-index:10;margin:0;padding:0;font:20px sans-serif';"
                            + "var control=document.createElement('button');control.id='japanese-control';"
                            + "control.textContent=t;document.body.append(fixed,control);window.protectedStyles={"
                            + "fixed:fixed.getAttribute('style'),control:control.getAttribute('style'),"
                            + "fixedPosition:getComputedStyle(fixed).position,controlDisplay:getComputedStyle(control).display};return true})()");
            awaitJavascriptCondition(scenario,
                    "JSON.parse(window.FuriHook102.inspect(true)).layoutReservations.some(function(item){"
                            + "return item.elementId==='fixed-japanese'&&item.status==='excluded'})");
            JSONObject protectedInspection = inspect(scenario);
            assertFalse("固定正文和交互控件不得显示 Ruby",
                    containsAnnotationForText(protectedInspection, protectedText));
            JSONObject protectedStylesAfter = new JSONObject(awaitJavascript(scenario,
                    "(function(){var f=document.getElementById('fixed-japanese'),c=document.getElementById('japanese-control');"
                            + "return JSON.stringify({fixed:f.getAttribute('style'),control:c.getAttribute('style'),"
                            + "fixedPosition:getComputedStyle(f).position,controlDisplay:getComputedStyle(c).display})})()"));
            JSONObject protectedStyleBaseline = new JSONObject(awaitJavascript(scenario,
                    "JSON.stringify(window.protectedStyles)"));
            assertEquals(protectedStyleBaseline.getString("fixed"), protectedStylesAfter.getString("fixed"));
            assertEquals(protectedStyleBaseline.getString("control"), protectedStylesAfter.getString("control"));
            assertEquals(protectedStyleBaseline.getString("fixedPosition"),
                    protectedStylesAfter.getString("fixedPosition"));
            assertEquals(protectedStyleBaseline.getString("controlDisplay"),
                    protectedStylesAfter.getString("controlDisplay"));

            String beforeMutationId = awaitJavascript(scenario,
                    "String(window.reactFixture.inspect().schoolNodeIdentityPreserved)");
            assertEquals("true", beforeMutationId);
            awaitJavascript(scenario,
                    "(function(){window.reactFixture.reconcileList();return true})()");
            awaitJavascriptCondition(scenario,
                    "window.reactFixture.inspect().addedRenderedByReact"
                            + " && window.reactFixture.inspect().addedText==='" + REACT_INSERTED_ROW + "'");
            scrollIntoView(scenario, "react-row-added");
            awaitJavascriptCondition(scenario,
                    "window.reactFixture.inspect().schoolText==='" + REACT_RETAINED_ROW + "'"
                            + " && window.reactFixture.inspect().schoolNodeIdentityPreserved"
                            + " && window.reactFixture.inspect().schoolElementIdentityPreserved"
                            + " && window.reactFixture.inspect().addedText==='" + REACT_INSERTED_ROW + "'"
                            + " && window.reactFixture.inspect().addedRenderedByReact"
                            + " && window.reactFixture.inspect().removedRowDisconnected"
                            + " && " + hostHasAnyAnnotationCondition(REACT_RETAINED_ROW)
                            + " && " + hostHasAnyAnnotationCondition(REACT_INSERTED_ROW));
            JSONObject afterList = reactSnapshot(scenario);
            assertTrue("React 列表复用时保留 keyed 段落节点",
                    afterList.getBoolean("schoolElementIdentityPreserved"));
            assertTrue("React 列表更新时保留段落的同一个 Text 节点",
                    afterList.getBoolean("schoolNodeIdentityPreserved"));
            assertTrue("React 删除的旧列表节点必须脱离文档",
                    afterList.getBoolean("removedRowDisconnected"));
            assertEquals(REACT_RETAINED_ROW, afterList.getString("schoolText"));
            assertEquals(REACT_INSERTED_ROW, afterList.getString("addedText"));
            assertTrue("新增列表项必须由真实 React 提交为文本节点",
                    afterList.getBoolean("addedRenderedByReact"));
            JSONObject afterListInspection = inspect(scenario);
            assertMatchesProductionEngine(REACT_RETAINED_ROW,
                    annotationsForText(afterListInspection, REACT_RETAINED_ROW));
            assertMatchesProductionEngine(REACT_INSERTED_ROW,
                    annotationsForText(afterListInspection, REACT_INSERTED_ROW));

            tap(scenario, "link");
            awaitJavascriptCondition(scenario,
                    "document.getElementById('link-result').textContent==='clicked'");
            String linkClickResult = awaitJavascript(scenario,
                    "document.getElementById('link-result').textContent");
            assertEquals("clicked", linkClickResult);
            tap(scenario, "dynamic-button");
            awaitJavascriptCondition(scenario,
                    "window.fixtureState.dynamicRevision===1"
                            + " && document.getElementById('dynamic').textContent==='" + UPDATED + "'"
                            + " && " + hostHasAnyAnnotationCondition(UPDATED));
            JSONObject afterDynamic = pageSnapshot(scenario);
            JSONObject afterDynamicInspection = inspect(scenario);
            saveEvidence(scenario, new JSONObject()
                    .put("stage", "dynamic-text-update")
                    .put("page", afterDynamic)
                    .put("inspection", afterDynamicInspection));
            assertEquals(UPDATED, afterDynamic.getString("dynamicText"));
            assertTrue("宿主动态更新必须写入同一个原始 Text 节点",
                    afterDynamic.getBoolean("dynamicNodeIdentityPreserved"));
            assertMatchesProductionEngine(UPDATED,
                    annotationsForText(afterDynamicInspection, UPDATED));

            awaitJavascript(scenario,
                    "(function(){var e=document.getElementById('editable');e.firstChild.data='編集可能な漢字';return true})()");
            awaitJavascriptCondition(scenario,
                    "document.getElementById('editable').textContent==='編集可能な漢字'"
                            + "&&document.getElementById('editable').firstChild===window.__e2eEditableNode");
            JSONObject excluded = pageSnapshot(scenario);
            assertEquals("編集可能な漢字", excluded.getString("editableText"));
            assertTrue("宿主更新 contenteditable 文本时，Hook 必须保留同一个 Text 节点",
                    excluded.getBoolean("editableNodeIdentityPreserved"));
            assertEquals("password", excluded.getString("passwordType"));
            assertEquals(ORIGINAL, excluded.getString("passwordValue"));
            JSONObject afterExcluded = inspect(scenario);
            assertFalse("contenteditable 内部不得接受注音记录",
                    containsAnnotationForText(afterExcluded, "編集可能な漢字"));
            assertFalse("宿主更新的 contenteditable 节点仍不得接受注音",
                    hostTextHasRuby(scenario, "editable"));
            assertEquals(1, excluded.getInt("fixtureRubyCount"));
            assertEquals("かん", excluded.getString("fixtureOriginalReading"));

            clipboard.clearPrimaryClip();
            tap(scenario, "copy-button");
            awaitJavascriptCondition(scenario, "window.fixtureState.copyEvents>0");
            CharSequence copied = awaitClipboard(clipboard);
            assertEquals("复制后剪贴板必须保留宿主选择的原文", COPY_TEXT, copied.toString());

            JSONObject longSkipBaseline = inspect(scenario);
            String longResult = awaitJavascript(scenario,
                    "(function(){var p=document.createElement('p');p.id='long-single-node';"
                            + "var prefix='A'.repeat(1800);var source='" + LONG_JAPANESE + "';"
                            + "var node=document.createTextNode(prefix+source);p.appendChild(node);"
                            + "document.body.appendChild(p);window.fixtureState.longNode=node;"
                            + "return JSON.stringify({prefixLength:prefix.length,totalLength:node.data.length})})()");
            JSONObject longCreated = new JSONObject(longResult);
            assertEquals(1800, longCreated.getInt("prefixLength"));
            scrollIntoView(scenario, "long-single-node");
            awaitJavascriptCondition(scenario,
                    "Array.from(window.__FuriHook102.records.values()).some(function(record){"
                            + "return record.node===window.fixtureState.longNode&&record.segments!==null})",
                    30_000L);
            JSONObject longDom = new JSONObject(awaitJavascript(scenario,
                    "JSON.stringify({text:window.fixtureState.longNode.data,"
                            + "connected:window.fixtureState.longNode.isConnected,"
                            + "parent:window.fixtureState.longNode.parentNode.id})"));
            String fullLongText = repeat("A", 1800) + LONG_JAPANESE;
            assertEquals(fullLongText, longDom.getString("text"));
            assertTrue("分块扫描不得拆卸长文本原始节点", longDom.getBoolean("connected"));
            assertEquals("long-single-node", longDom.getString("parent"));
            JSONObject naturalLong = new JSONObject(awaitJavascript(scenario, longNodeSnapshotExpression("natural")));
            saveEvidence(scenario, new JSONObject().put("stage", "long-node-natural-layout")
                    .put("analysis", naturalLong).put("inspection", inspect(scenario)));
            assertInternalLongAnalysis(fullLongText, naturalLong);
            assertTrue("自然换行阶段必须保留原始 Text 节点", naturalLong.getBoolean("sameNode"));
            assertTrue("自然换行应将日文段落绘制在真实范围内", naturalLong.getBoolean("hasViewportRange"));
            JSONObject naturalInspection = inspect(scenario);
            JSONArray naturalAnnotations = annotationsForHostContaining(naturalInspection, fullLongText);
            assertAnnotationsAreProductionSubset(fullLongText, naturalAnnotations);
            assertClearLayoutWhenPresent(naturalAnnotations);
            JSONObject naturalSkips = new JSONObject()
                    .put("overlap", naturalInspection.getInt("skippedOverlapCount")
                            - longSkipBaseline.getInt("skippedOverlapCount"))
                    .put("clipped", naturalInspection.getInt("skippedClippedCount")
                            - longSkipBaseline.getInt("skippedClippedCount"))
                    .put("occluded", naturalInspection.getInt("skippedOccludedCount")
                            - longSkipBaseline.getInt("skippedOccludedCount"));

            JSONObject readerArea = new JSONObject(awaitJavascript(scenario,
                    longNodeReaderAreaExpression()));
            assertTrue("宿主阅读区域必须产生真实水平滚动范围",
                    readerArea.getInt("scrollWidth") > readerArea.getInt("clientWidth"));
            assertTrue("横向滚动前尾部必须位于宿主裁剪区域之外",
                    readerArea.getBoolean("tailInitiallyOutside"));
            assertTrue("修改宿主样式后必须保留原始 Text 节点", readerArea.getBoolean("sameNode"));
            assertEquals(fullLongText, readerArea.getString("nodeData"));
            JSONObject readerAreaCheckpoint = new JSONObject().put("stage", "reader-area-before-scroll")
                    .put("analysis", naturalLong).put("naturalInspection", naturalInspection)
                    .put("naturalAnnotations", naturalAnnotations).put("naturalSkips", naturalSkips)
                    .put("readerArea", readerArea);
            saveEvidence(scenario, readerAreaCheckpoint);
            JSONObject scrollResult = new JSONObject(awaitJavascript(scenario, longNodeScrollTailExpression()));
            JSONObject afterScrollBeforeLayout = new JSONObject(awaitJavascript(scenario,
                    longNodeSnapshotExpression("visible")));
            JSONObject inspectionBeforeLayout = inspect(scenario);
            saveEvidence(scenario, new JSONObject().put("stage", "long-node-scroll-before-layout")
                    .put("readerAreaBeforeScroll", readerArea).put("scrollResult", scrollResult)
                    .put("stateAfterScroll", afterScrollBeforeLayout)
                    .put("inspectionBeforeLayout", inspectionBeforeLayout));
            awaitJavascriptCondition(scenario, longAllRangesInsideHostClipCondition(), 30_000L);
            awaitJavascriptCondition(scenario, "window.fixtureState.longNode===window.fixtureState.longNode.parentElement.firstChild");
            awaitJavascriptCondition(scenario, "window.fixtureState.longNode.parentElement.scrollLeft>0");
            awaitJavascriptCondition(scenario, hostHasAnyAnnotationCondition(fullLongText), 20_000L);
            JSONObject longVisible = new JSONObject(awaitJavascript(scenario, longNodeSnapshotExpression("visible")));
            JSONObject longVisibleInspection = inspect(scenario);
            JSONArray longAnnotations = annotationsForHostContaining(longVisibleInspection, fullLongText);
            JSONObject longCheckpoint = new JSONObject();
            longCheckpoint.put("stage", "long-node-horizontal-scroll");
            longCheckpoint.put("analysis", naturalLong);
            longCheckpoint.put("naturalInspection", naturalInspection);
            longCheckpoint.put("naturalAnnotations", naturalAnnotations);
            longCheckpoint.put("naturalSkips", naturalSkips);
            longCheckpoint.put("readerAreaBeforeScroll", readerArea);
            longCheckpoint.put("scrollResult", scrollResult);
            longCheckpoint.put("stateAfterScroll", afterScrollBeforeLayout);
            longCheckpoint.put("inspectionBeforeLayout", inspectionBeforeLayout);
            longCheckpoint.put("visibleState", longVisible);
            longCheckpoint.put("visibleInspection", longVisibleInspection);
            longCheckpoint.put("visibleAnnotations", longAnnotations);
            saveEvidence(scenario, longCheckpoint);
            assertAnnotationsAreProductionSubset(fullLongText, longAnnotations);
            assertClearLayout(longAnnotations);

            int segmentsBeforeBurst = inspect(scenario).getInt("analyzedSegmentCount");
            JSONObject largeDocument = new JSONObject(awaitJavascript(scenario,
                    "(function(){var root=document.createElement('div');root.id='large-document';"
                            + "var fragment=document.createDocumentFragment();for(var i=0;i<340;i++){"
                            + "var span=document.createElement('span');span.appendChild(document.createTextNode('item '+i));"
                            + "fragment.appendChild(span)}root.appendChild(fragment);document.body.appendChild(root);"
                            + "return JSON.stringify({textNodes:root.querySelectorAll('*').length})})()"));
            assertEquals(340, largeDocument.getInt("textNodes"));
            awaitJavascriptCondition(scenario,
                    "JSON.parse(window.FuriHook102.inspect()).scan.initialDone"
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.dirtyRoots===0"
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.dirtyNodes===0"
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.pendingBatches===0"
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.awaitingReadings===0");

            JSONObject burstCreated = new JSONObject(awaitJavascript(scenario,
                    "(function(){var root=document.createElement('div');root.id='dynamic-burst';"
                            + "var fragment=document.createDocumentFragment();for(var i=0;i<240;i++){"
                            + "var p=document.createElement('p');p.appendChild(document.createTextNode('学校'));"
                            + "fragment.appendChild(p)}root.appendChild(fragment);document.body.appendChild(root);"
                            + "return JSON.stringify({paragraphs:root.childNodes.length,textNodes:"
                            + "Array.from(root.childNodes).filter(function(p){return p.firstChild.nodeType===3}).length})})()"));
            assertEquals(240, burstCreated.getInt("paragraphs"));
            assertEquals(240, burstCreated.getInt("textNodes"));
            awaitJavascriptCondition(scenario,
                    "JSON.parse(window.FuriHook102.inspect()).analyzedSegmentCount>="
                            + (segmentsBeforeBurst + 240)
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.dirtyRoots===0"
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.dirtyNodes===0"
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.pendingBatches===0"
                            + " && JSON.parse(window.FuriHook102.inspect()).scan.awaitingReadings===0",
                    60_000L);
            JSONObject burstState = inspect(scenario);
            assertEquals(repeat("学校", 240), awaitJavascript(scenario,
                    "document.getElementById('dynamic-burst').textContent"));
            assertTrue("全部 240 个段落都必须完成实际词典分析",
                    burstState.getInt("analyzedSegmentCount") >= segmentsBeforeBurst + 240);
            assertTrue("同时可见的注音数量必须遵守布局上限",
                    burstState.getInt("annotationCount") <= 128);
            assertEquals(0, burstState.getJSONObject("scan").getInt("awaitingReadings"));

            long oldGeneration = burstState.getLong("generation");
            String oldPageId = awaitJavascript(scenario, "window.fixtureState.instanceId");
            AtomicReference<WebView> reloadView = new AtomicReference<>();
            scenario.onActivity(activity -> reloadView.set(activity.webView()));
            scenario.onActivity(activity -> reloadView.get().reload());
            awaitJavascriptCondition(scenario,
                    "window.fixtureState && window.fixtureState.instanceId!=='" + oldPageId + "'"
                            + " && window.FuriHook102 && JSON.parse(window.FuriHook102.inspect()).generation>"
                            + oldGeneration + " && " + hostHasAnnotationCondition(ORIGINAL, "今日"));
            JSONObject afterReload = inspect(scenario);
            assertTrue("重新加载必须建立更高的文档代次",
                    afterReload.getLong("generation") > oldGeneration);
            assertMatchesProductionEngine(ORIGINAL, annotationsForText(afterReload, ORIGINAL));

            JSONObject evidence = new JSONObject();
            evidence.put("test", "realWebViewKeepsReactDomAndDrawsDictionaryRubyWithoutChangingHostText");
            evidence.put("reactProductionVersion", translation.getString("reactProduction"));
            evidence.put("translationTextNodePreserved", translation.getBoolean("postNodeIdentityPreserved"));
            evidence.put("translationTextUpdatedBack", translatedAgain.getString("postNodeData"));
            evidence.put("reactOriginalTextNodePreserved", reactOriginal.getBoolean("postNodeIdentityPreserved"));
            evidence.put("reactOriginalSegments", annotationEvidence(reactAnnotations));
            evidence.put("reactTranslationLayout", reactTranslationLayout);
            evidence.put("reactOriginalLayout", reactOriginalLayout);
            evidence.put("reactOriginalReservation", originalReservation);
            evidence.put("reactHostCssJapaneseLayout", hostCssJapaneseLayout);
            evidence.put("reactTranslatedLayoutAfterRestoration", translatedLayout);
            evidence.put("reactRestoredReservation", restoredReservation);
            evidence.put("fixedAndControlText", protectedText);
            evidence.put("fixedReservation", reservationFor(inspectDetailed(scenario), "fixed-japanese"));
            evidence.put("reactListStableNode", afterList.getBoolean("schoolNodeIdentityPreserved"));
            evidence.put("reactListRemovedNodeDisconnected", afterList.getBoolean("removedRowDisconnected"));
            evidence.put("reactListSegments", annotationEvidence(annotationsForText(
                    afterListInspection, REACT_RETAINED_ROW)));
            evidence.put("reactInsertedSegments", annotationEvidence(annotationsForText(
                    afterListInspection, REACT_INSERTED_ROW)));
            evidence.put("rubyLayoutPixels", pixelEvidence);
            evidence.put("staticBodyTextNodePreserved", staticDom.getBoolean("plainNodeIdentityPreserved"));
            evidence.put("editableInitialText", initialPage.getString("editableText"));
            evidence.put("editableHostUpdatedText", excluded.getString("editableText"));
            evidence.put("editableHostTextNodePreserved", excluded.getBoolean("editableNodeIdentityPreserved"));
            evidence.put("linkClickPreserved", "clicked".equals(linkClickResult));
            evidence.put("clipboardText", copied.toString());
            evidence.put("longNodePrefixLength", longCreated.getInt("prefixLength"));
            evidence.put("longNodePreserved", longDom.getBoolean("connected"));
            evidence.put("longNodeAnalysis", naturalLong.getJSONArray("segments"));
            evidence.put("longNodeNaturalRanges", naturalLong.getJSONArray("ranges"));
            evidence.put("longNodeNaturalSkips", naturalSkips);
            evidence.put("longNodeReaderAreaBeforeScroll", readerArea);
            evidence.put("longNodeHorizontalScrollX", longVisible.getDouble("scrollLeft"));
            evidence.put("longNodeVisibleRanges", longVisible.getJSONArray("ranges"));
            evidence.put("longNodeVisibleAnnotations", annotationEvidence(longAnnotations));
            evidence.put("largeDocumentTextNodes", largeDocument.getInt("textNodes"));
            evidence.put("burstParagraphsAnalyzed", burstState.getInt("analyzedSegmentCount")
                    - segmentsBeforeBurst);
            evidence.put("burstPendingReadings", burstState.getJSONObject("scan")
                    .getInt("awaitingReadings"));
            evidence.put("reloadGenerationBefore", oldGeneration);
            evidence.put("reloadGenerationAfter", afterReload.getLong("generation"));
            File report = saveEvidence(scenario, evidence);
            System.out.println("FURIHOOK_WEBVIEW_E2E_REPORT=" + report.getAbsolutePath());
        }
    }

    private static JSONObject inspect(ActivityScenario<WebViewRubyFixtureActivity> scenario)
            throws Exception {
        return new JSONObject(awaitJavascript(scenario,
                "JSON.parse(window.FuriHook102.inspect())"));
    }

    private static JSONObject inspectDetailed(ActivityScenario<WebViewRubyFixtureActivity> scenario)
            throws Exception {
        return new JSONObject(awaitJavascript(scenario,
                "JSON.parse(window.FuriHook102.inspect(true))"));
    }

    private static JSONObject elementLayout(ActivityScenario<WebViewRubyFixtureActivity> scenario,
                                            String elementId) throws Exception {
        return new JSONObject(awaitJavascript(scenario,
                "(function(){var e=document.getElementById('" + elementId + "'),s=getComputedStyle(e),r=e.getBoundingClientRect();"
                        + "return JSON.stringify({width:r.width,letterSpacing:s.letterSpacing,fontSize:s.fontSize,color:s.color,"
                        + "lineHeight:s.lineHeight,paddingTop:s.paddingTop,display:s.display,position:s.position})})()"));
    }

    private static boolean hostTextHasRuby(ActivityScenario<WebViewRubyFixtureActivity> scenario,
                                           String elementId) throws Exception {
        String value = awaitJavascript(scenario,
                "(function(){var n=document.getElementById('" + elementId + "').firstChild;"
                        + "return Array.from(window.__FuriHook102.records.values()).some(function(record){"
                        + "return record.node===n&&Array.isArray(record.segments)&&record.segments.length>0})})()");
        assertTrue("读音记录检查必须返回明确的布尔值", "true".equals(value) || "false".equals(value));
        return Boolean.parseBoolean(value);
    }

    private static JSONObject reservationFor(JSONObject inspection, String elementId)
            throws JSONException {
        JSONArray reservations = inspection.getJSONArray("layoutReservations");
        JSONObject latest = null;
        for (int index = 0; index < reservations.length(); index++) {
            JSONObject reservation = reservations.getJSONObject(index);
            if (elementId.equals(reservation.optString("elementId"))) latest = reservation;
        }
        return latest;
    }

    private static void assertReservedSpace(JSONObject reservation) throws JSONException {
        assertTrue("inspect(true) 必须报告已实施的 Ruby 布局预留",
                reservation != null && "reserved".equals(reservation.getString("status")));
        JSONObject baseline = reservation.getJSONObject("baseline");
        JSONObject applied = reservation.getJSONObject("applied");
        double baselinePadding = parseCssPixels(baseline.getString("paddingTop"));
        double appliedPadding = parseCssPixels(applied.getString("paddingTop"));
        double baselineLineHeight = parseCssPixels(baseline.getString("lineHeight"));
        double appliedLineHeight = parseCssPixels(applied.getString("lineHeight"));
        assertTrue("日语正文必须增加顶部空间或行高",
                appliedPadding > baselinePadding
                        || (Double.isFinite(baselineLineHeight) && Double.isFinite(appliedLineHeight)
                        && appliedLineHeight > baselineLineHeight));
    }

    private static double parseCssPixels(String value) {
        if (!value.endsWith("px")) return Double.NaN;
        return Double.parseDouble(value.substring(0, value.length() - 2));
    }

    private static JSONArray annotationsForText(JSONObject inspection, String text)
            throws JSONException {
        Set<Integer> nodeIds = new HashSet<>();
        JSONArray hostNodes = inspection.getJSONArray("hostNodes");
        for (int index = 0; index < hostNodes.length(); index++) {
            JSONObject node = hostNodes.getJSONObject(index);
            if (node.getBoolean("connected") && text.equals(node.getString("data"))) {
                nodeIds.add(node.getInt("nodeId"));
            }
        }
        JSONArray result = new JSONArray();
        JSONArray annotations = inspection.getJSONArray("annotations");
        for (int index = 0; index < annotations.length(); index++) {
            JSONObject annotation = annotations.getJSONObject(index);
            if (nodeIds.contains(annotation.getInt("nodeId"))) result.put(annotation);
        }
        return result;
    }

    private static boolean containsAnnotationForText(JSONObject inspection, String text)
            throws JSONException {
        return annotationsForText(inspection, text).length() > 0;
    }

    private static JSONArray annotationsForHostContaining(JSONObject inspection, String text)
            throws JSONException {
        Set<Integer> nodeIds = new HashSet<>();
        JSONArray hostNodes = inspection.getJSONArray("hostNodes");
        for (int index = 0; index < hostNodes.length(); index++) {
            JSONObject node = hostNodes.getJSONObject(index);
            if (node.getBoolean("connected") && node.getString("data").contains(text)) {
                nodeIds.add(node.getInt("nodeId"));
            }
        }
        JSONArray result = new JSONArray();
        JSONArray annotations = inspection.getJSONArray("annotations");
        for (int index = 0; index < annotations.length(); index++) {
            JSONObject annotation = annotations.getJSONObject(index);
            if (nodeIds.contains(annotation.getInt("nodeId"))) result.put(annotation);
        }
        return result;
    }

    private static String hostHasAnnotationCondition(String text, String baseText) {
        return "(function(){var i=JSON.parse(window.FuriHook102.inspect());"
                + "var h=i.hostNodes.filter(function(n){return n.connected&&n.data==='" + text + "'});"
                + "return i.annotations.some(function(a){return a.baseText==='" + baseText
                + "'&&h.some(function(n){return n.nodeId===a.nodeId})})})()";
    }

    private static String hostHasAnyAnnotationCondition(String text) {
        return "(function(){var i=JSON.parse(window.FuriHook102.inspect());"
                + "var h=i.hostNodes.filter(function(n){return n.connected&&n.data==='" + text + "'});"
                + "return i.annotations.some(function(a){return h.some(function(n){return n.nodeId===a.nodeId})})})()";
    }

    private static String longNodeSnapshotExpression(String phase) {
        return "(function(){var state=window.__FuriHook102;var node=window.fixtureState.longNode;"
                + "var paragraph=node.parentElement;"
                + ("reader-area".equals(phase) ? "paragraph.style.cssText='box-sizing:border-box;width:100%;"
                        + "overflow-x:auto;white-space:nowrap;padding:32px 0 4px;margin:14px 0';" : "")
                + "var records=Array.from(state.records.values()).filter(function(record){"
                + "return record.node===node&&record.segments!==null}).sort(function(a,b){return a.offset-b.offset});"
                + "var segments=[],ranges=[];records.forEach(function(record){record.segments.forEach(function(segment){"
                + "segments.push({startUtf16:record.offset+segment[0],endUtf16:record.offset+segment[1],"
                + "baseText:record.text.slice(segment[0],segment[1]),reading:segment[2]})})});"
                + "if(!records.length||!records[0].segments.length)throw new Error('No analyzed long-node Ruby segments');"
                + "segments.forEach(function(segment){var range=document.createRange();range.setStart(node,segment.startUtf16);"
                + "range.setEnd(node,segment.endUtf16);var r=range.getBoundingClientRect();segment.rect={left:r.left,"
                + "top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};ranges.push(segment)});"
                + "var p=paragraph,r=p.getBoundingClientRect(),clipLeft=r.left+p.clientLeft,clipRight=clipLeft+p.clientWidth;"
                + "var viewportRanges=ranges.filter(function(x){return x.rect.right>0&&x.rect.left<innerWidth;});"
                + "var result={nodeData:node.data,nodeConnected:node.isConnected,sameNode:node===p.firstChild,parentId:p.id,"
                + "scrollLeft:p.scrollLeft,scrollWidth:p.scrollWidth,clientWidth:p.clientWidth,"
                + "clip:{left:clipLeft,right:clipRight,top:r.top+p.clientTop,bottom:r.top+p.clientTop+p.clientHeight},"
                + "viewportWidth:innerWidth,hasViewportRange:viewportRanges.length>0,records:records.map(function(record){"
                + "return {offset:record.offset,text:record.text,segmentCount:record.segments.length}}),segments:segments,ranges:ranges};"
                + ("reader-area".equals(phase) ? "r=p.getBoundingClientRect();"
                        + "clipLeft=r.left+p.clientLeft;clipRight=clipLeft+p.clientWidth;var tail=ranges[ranges.length-1];"
                        + "result.scrollWidth=p.scrollWidth;result.clientWidth=p.clientWidth;result.clip={left:clipLeft,"
                        + "right:clipRight,top:r.top+p.clientTop,bottom:r.top+p.clientTop+p.clientHeight};"
                        + "result.tailInitiallyOutside=tail.rect.left>=clipRight||tail.rect.right<=clipLeft;" : "")
                + "return JSON.stringify(result)})()";
    }

    private static String longNodeReaderAreaExpression() {
        return longNodeSnapshotExpression("reader-area");
    }

    private static String longNodeScrollTailExpression() {
        return "(function(){var state=window.__FuriHook102;var node=window.fixtureState.longNode;"
                + "var p=node.parentElement,maxScrollLeft=p.scrollWidth-p.clientWidth;"
                + "p.scrollLeft=maxScrollLeft;var r=p.getBoundingClientRect(),left=r.left+p.clientLeft;"
                + "return JSON.stringify({scrollLeft:p.scrollLeft,maxScrollLeft:maxScrollLeft,scrollWidth:p.scrollWidth,"
                + "clientWidth:p.clientWidth,clip:{left:left,right:left+p.clientWidth,top:r.top+p.clientTop,"
                + "bottom:r.top+p.clientTop+p.clientHeight}})})()";
    }

    private static String longAllRangesInsideHostClipCondition() {
        return "(function(){var node=window.fixtureState.longNode,p=node.parentElement,state=window.__FuriHook102;"
                + "var records=Array.from(state.records.values()).filter(function(x){return x.node===node&&x.segments})"
                + ".sort(function(a,b){return a.offset-b.offset});"
                + "var ranges=[];records.forEach(function(record){record.segments.forEach(function(segment){var range=document.createRange();"
                + "range.setStart(node,record.offset+segment[0]);range.setEnd(node,record.offset+segment[1]);"
                + "ranges.push(range.getBoundingClientRect())})});var r=p.getBoundingClientRect(),left=r.left+p.clientLeft,right=left+p.clientWidth;"
                + "return p.scrollLeft>0&&ranges.length>0&&ranges.every(function(x){return x.left>=left&&x.right<=right"
                + "&&x.top>=0&&x.bottom<=innerHeight})})()";
    }

    private static void assertAnnotationsAreProductionSubset(String source, JSONArray actual)
            throws JSONException {
        List<RubySegment> expected = ENGINE.analyze(source);
        Set<String> keys = new HashSet<>();
        for (RubySegment segment : expected) {
            keys.add(segment.getStartUtf16() + ":" + segment.getEndUtf16() + ":"
                    + source.substring(segment.getStartUtf16(), segment.getEndUtf16()) + ":" + segment.getRubyText());
        }
        for (int index = 0; index < actual.length(); index++) {
            JSONObject annotation = actual.getJSONObject(index);
            String key = annotation.getInt("segmentStart") + ":" + annotation.getInt("segmentEnd") + ":"
                    + annotation.getString("baseText") + ":" + annotation.getString("reading");
            assertTrue("自然布局注音必须来自生产 Kuromoji 分段：" + annotation, keys.contains(key));
        }
    }

    private static void assertInternalLongAnalysis(String source, JSONObject analysis)
            throws JSONException {
        assertEquals(source, analysis.getString("nodeData"));
        assertTrue("长文本原始 Text 节点必须保持连接", analysis.getBoolean("nodeConnected"));
        assertEquals("long-single-node", analysis.getString("parentId"));
        JSONArray records = analysis.getJSONArray("records");
        assertTrue("生产 state.records 必须保留长文本的分块分析记录", records.length() > 0);
        JSONArray actual = analysis.getJSONArray("segments");
        List<RubySegment> expected = ENGINE.analyze(source);
        List<JSONObject> ordered = new ArrayList<>();
        for (int index = 0; index < actual.length(); index++) {
            ordered.add(actual.getJSONObject(index));
        }
        ordered.sort(Comparator.comparingInt(value -> value.optInt("startUtf16")));
        assertEquals("长文本 state.records 的 UTF-16 分段数量必须与生产 Kuromoji 一致",
                expected.size(), ordered.size());
        for (int index = 0; index < expected.size(); index++) {
            RubySegment segment = expected.get(index);
            JSONObject entry = ordered.get(index);
            assertEquals(segment.getStartUtf16(), entry.getInt("startUtf16"));
            assertEquals(segment.getEndUtf16(), entry.getInt("endUtf16"));
            assertEquals(source.substring(segment.getStartUtf16(), segment.getEndUtf16()),
                    entry.getString("baseText"));
            assertEquals(segment.getRubyText(), entry.getString("reading"));
        }
    }

    private static void assertMatchesProductionEngine(String source, JSONArray actual)
            throws JSONException {
        List<RubySegment> expected = ENGINE.analyze(source);
        List<JSONObject> ordered = new ArrayList<>();
        for (int index = 0; index < actual.length(); index++) {
            ordered.add(actual.getJSONObject(index));
        }
        ordered.sort(Comparator.comparingInt(value -> value.optInt("segmentStart")));
        assertEquals("WebView 注音数量必须与生产 Kuromoji 引擎一致：" + source,
                expected.size(), ordered.size());
        for (int index = 0; index < expected.size(); index++) {
            RubySegment segment = expected.get(index);
            JSONObject annotation = ordered.get(index);
            assertEquals(segment.getStartUtf16(), annotation.getInt("segmentStart"));
            assertEquals(segment.getEndUtf16(), annotation.getInt("segmentEnd"));
            assertEquals(source.substring(segment.getStartUtf16(), segment.getEndUtf16()),
                    annotation.getString("baseText"));
            assertEquals(segment.getRubyText(), annotation.getString("reading"));
        }
    }

    private static JSONArray annotationEvidence(JSONArray annotations) throws JSONException {
        JSONArray result = new JSONArray();
        for (int index = 0; index < annotations.length(); index++) {
            JSONObject source = annotations.getJSONObject(index);
            result.put(new JSONObject().put("startUtf16", source.getInt("segmentStart"))
                    .put("endUtf16", source.getInt("segmentEnd"))
                    .put("baseText", source.getString("baseText"))
                    .put("reading", source.getString("reading"))
                    .put("visible", source.getBoolean("visible"))
                    .put("clearance", source.getString("clearance"))
                    .put("baseRect", source.getJSONObject("baseRect"))
                    .put("readingRect", source.getJSONObject("readingRect")));
        }
        return result;
    }

    private static void assertClearLayout(JSONArray annotations) throws JSONException {
        assertTrue("日语原文必须至少生成一条有效注音", annotations.length() > 0);
        assertClearLayoutWhenPresent(annotations);
    }

    private static void assertClearLayoutWhenPresent(JSONArray annotations) throws JSONException {
        for (int index = 0; index < annotations.length(); index++) {
            JSONObject annotation = annotations.getJSONObject(index);
            if (!annotation.getBoolean("visible")) continue;
            JSONObject reading = annotation.getJSONObject("readingRect");
            JSONObject base = annotation.getJSONObject("baseRect");
            assertEquals("注音不得与宿主汉字文字碰撞", "clear",
                    annotation.getString("clearance"));
            assertTrue("注音必须位于汉字上方",
                    reading.getDouble("bottom") <= base.getDouble("top"));
            JSONArray baseRects = annotation.getJSONArray("textRects");
            for (int rectIndex = 0; rectIndex < baseRects.length(); rectIndex++) {
                assertFalse("注音区域不得覆盖任何原文字形范围",
                        intersects(reading, baseRects.getJSONObject(rectIndex)));
            }
        }
    }

    private static boolean intersects(JSONObject first, JSONObject second) throws JSONException {
        return first.getDouble("left") < second.getDouble("right")
                && first.getDouble("right") > second.getDouble("left")
                && first.getDouble("top") < second.getDouble("bottom")
                && first.getDouble("bottom") > second.getDouble("top");
    }

    private static JSONObject captureRubyPixels(
            ActivityScenario<WebViewRubyFixtureActivity> scenario, JSONArray annotations,
            double devicePixelRatio)
            throws Exception {
        JSONObject selected = null;
        for (int index = 0; index < annotations.length(); index++) {
            JSONObject annotation = annotations.getJSONObject(index);
            if (annotation.getBoolean("visible")) {
                selected = annotation;
                break;
            }
        }
        assertTrue("至少一条注音必须在真实 WebView 视口内绘制", selected != null);

        AtomicReference<Bitmap> bitmapReference = new AtomicReference<>();
        AtomicReference<Rect> sourceReference = new AtomicReference<>();
        AtomicReference<Window> windowReference = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView view = activity.webView();
            int[] location = new int[2];
            view.getLocationInWindow(location);
            Rect source = new Rect(location[0], location[1],
                    location[0] + view.getWidth(), location[1] + view.getHeight());
            bitmapReference.set(Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                    Bitmap.Config.ARGB_8888));
            sourceReference.set(source);
            windowReference.set(activity.getWindow());
        });

        Bitmap bitmap = bitmapReference.get();
        CountDownLatch copied = new CountDownLatch(1);
        int[] copyStatus = new int[1];
        PixelCopy.request(windowReference.get(), sourceReference.get(), bitmap, status -> {
            copyStatus[0] = status;
            copied.countDown();
        }, new Handler(Looper.getMainLooper()));
        assertTrue("WebView 实际窗口像素复制必须完成", copied.await(10, TimeUnit.SECONDS));
        assertEquals("PixelCopy 必须成功", PixelCopy.SUCCESS, copyStatus[0]);

        JSONObject readingRect = selected.getJSONObject("readingRect");
        JSONObject baseRect = selected.getJSONObject("baseRect");
        int left = clamp((int) Math.floor(readingRect.getDouble("left") * devicePixelRatio), 0,
                bitmap.getWidth());
        int top = clamp((int) Math.floor(readingRect.getDouble("top") * devicePixelRatio), 0,
                bitmap.getHeight());
        int right = clamp((int) Math.ceil(readingRect.getDouble("right") * devicePixelRatio), 0,
                bitmap.getWidth());
        int bottom = clamp((int) Math.ceil(readingRect.getDouble("bottom") * devicePixelRatio), 0,
                bitmap.getHeight());
        int inkPixels = countNonWhite(bitmap, left, top, right, bottom);
        assertTrue("Ruby 注音范围内必须存在真实绘制像素", inkPixels > 0);

        int baseTop = clamp((int) Math.floor(baseRect.getDouble("top") * devicePixelRatio), 0,
                bitmap.getHeight());
        int baseBottom = clamp((int) Math.ceil(baseRect.getDouble("bottom") * devicePixelRatio), 0,
                bitmap.getHeight());
        int basePixels = countNonWhite(bitmap, 0, baseTop, bitmap.getWidth(), baseBottom);
        assertTrue("基础汉字范围也必须存在真实绘制像素", basePixels > 0);

        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null);
        File image = new File(directory, "webview-ruby-pixel-copy.png");
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        try (FileOutputStream output = new FileOutputStream(image)) {
            assertTrue("WebView PixelCopy 证据必须保存为 PNG",
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        return new JSONObject().put("image", image.getAbsolutePath())
                .put("rubyText", selected.getString("reading"))
                .put("readingRectCssPx", readingRect)
                .put("baseRectCssPx", baseRect)
                .put("rubyInkPixelCount", inkPixels)
                .put("baseInkPixelCount", basePixels)
                .put("devicePixelRatio", devicePixelRatio)
                .put("bitmapWidth", bitmapWidth)
                .put("bitmapHeight", bitmapHeight);
    }

    private static int countNonWhite(Bitmap bitmap, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (bitmap.getPixel(x, y) != Color.WHITE) count++;
            }
        }
        return count;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    private static JSONObject pageSnapshot(ActivityScenario<WebViewRubyFixtureActivity> scenario)
            throws Exception {
        return new JSONObject(awaitJavascript(scenario,
                "(function(){var f=window.fixtureState;var p=document.getElementById('plain');"
                        + "var d=document.getElementById('dynamic');var e=document.getElementById('editable');"
                        + "var m=document.querySelector('[data-fixture-existing]');"
                        + "return JSON.stringify({plainText:p.textContent,"
                        + "plainNodeIdentityPreserved:f.plainNode===p.firstChild&&p.childNodes.length===1&&f.plainNode.parentNode===p,"
                        + "dynamicText:d.textContent,"
                        + "dynamicNodeIdentityPreserved:f.dynamicNode===d.firstChild&&d.childNodes.length===1&&f.dynamicNode.parentNode===d,"
                        + "editableText:e.textContent,editableNodeIdentityPreserved:e.firstChild===window.__e2eEditableNode,"
                        + "passwordType:document.getElementById('password').type,"
                        + "passwordValue:document.getElementById('password').value,"
                        + "linkText:document.getElementById('link').textContent,"
                        + "linkHref:document.getElementById('link').getAttribute('href'),"
                        + "linkResult:document.getElementById('link-result').textContent,"
                        + "copyText:document.getElementById('copy-source').textContent,"
                        + "fixtureRubyCount:document.querySelectorAll('ruby[data-fixture-existing]').length,"
                        + "fixtureOriginalReading:m.querySelector('rt').textContent,"
                        + "copyEvents:f.copyEvents})})()"));
    }

    private static JSONObject reactSnapshot(ActivityScenario<WebViewRubyFixtureActivity> scenario)
            throws Exception {
        return new JSONObject(awaitJavascript(scenario, "window.reactFixture.inspect()"));
    }

    private static void setReactMode(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            String mode) throws Exception {
        awaitJavascript(scenario,
                "(function(){window.reactFixture.setMode('" + mode + "');return true})()");
    }

    private static void scrollIntoView(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            String elementId) throws Exception {
        awaitJavascript(scenario,
                "(function(){document.getElementById('" + elementId
                        + "').scrollIntoView({block:'center'});return true})()");
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static CharSequence awaitClipboard(ClipboardManager clipboard) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(
                        InstrumentationRegistry.getInstrumentation().getTargetContext());
                if (COPY_TEXT.contentEquals(text)) return text;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("系统剪贴板未收到选择的原文");
    }

    private static File saveEvidence(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            JSONObject evidence) throws Exception {
        AtomicReference<File> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                result.set(activity.saveEvidence(evidence));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError("无法保存 WebView E2E 证据", failure.get());
        return result.get();
    }

    private static void tap(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            String elementId) throws Exception {
        JSONObject bounds = new JSONObject(awaitJavascript(scenario,
                "(function(){var e=document.getElementById('" + elementId + "');"
                        + "e.scrollIntoView({block:'center'});var r=e.getBoundingClientRect();"
                        + "return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,"
                        + "devicePixelRatio:window.devicePixelRatio})})()"));
        float scale = (float) bounds.getDouble("devicePixelRatio");
        AtomicReference<WebView> view = new AtomicReference<>();
        scenario.onActivity(activity -> view.set(activity.webView()));
        float x = (float) bounds.getDouble("x") * scale;
        float y = (float) bounds.getDouble("y") * scale;
        scenario.onActivity(activity -> {
            WebView webView = view.get();
            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime,
                    MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 45,
                    MotionEvent.ACTION_UP, x, y, 0);
            try {
                webView.dispatchTouchEvent(down);
                webView.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
        });
    }

    private static String awaitJavascript(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scenario.onActivity(activity -> {
            if (!activity.pageLoaded()) {
                failure.set(new IllegalStateException("WebView 页面尚未加载完成"));
                latch.countDown();
                return;
            }
            activity.webView().evaluateJavascript(expression, value -> {
                try {
                    Object decoded = new JSONTokener(value).nextValue();
                    result.set(String.valueOf(decoded));
                } catch (JSONException error) {
                    failure.set(error);
                }
                latch.countDown();
            });
        });
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("evaluateJavascript 超时");
        if (failure.get() != null) throw new AssertionError("evaluateJavascript 失败", failure.get());
        return result.get();
    }

    private static void awaitPageLoaded(ActivityScenario<WebViewRubyFixtureActivity> scenario)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 15_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            boolean[] loaded = new boolean[1];
            scenario.onActivity(activity -> loaded[0] = activity.pageLoaded());
            if (loaded[0]) return;
            Thread.sleep(50L);
        }
        throw new AssertionError("WebView 页面在 15 秒内没有完成加载");
    }

    private static void awaitJavascriptCondition(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            String condition) throws Exception {
        awaitJavascriptCondition(scenario, condition, 20_000L);
    }

    private static void awaitJavascriptCondition(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            String condition, long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            String value = awaitJavascript(scenario, "Boolean(" + condition + ")");
            if (Boolean.parseBoolean(value)) return;
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(100L);
        }
        throw new AssertionError("真实 WebView 状态在 " + timeoutMillis
                + " 毫秒内未达到条件：" + condition);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
