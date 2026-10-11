package dev.furihook.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Exposes the production reading engine to browser verification over newline-delimited JSON. */
public final class ReadingEngineJsonBridge {
    private final Gson gson = new Gson();
    private final ReadingEngine engine = new KuromojiReadingEngine();

    private ReadingEngineJsonBridge() {
    }

    public static void main(String[] args) throws Exception {
        ReadingEngineJsonBridge bridge = new ReadingEngineJsonBridge();
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                System.out.println(bridge.analyze(line));
                System.out.flush();
            }
        }
    }

    private String analyze(String requestJson) {
        JsonObject request = JsonParser.parseString(requestJson).getAsJsonObject();
        JsonArray texts = request.getAsJsonArray("texts");
        if (texts == null) throw new IllegalArgumentException("请求必须包含 texts 数组");

        JsonObject response = new JsonObject();
        JsonArray results = new JsonArray();
        for (var textValue : texts) {
            String text = textValue.getAsString();
            List<RubySegment> segments = engine.analyze(text);
            JsonObject result = new JsonObject();
            result.addProperty("text", text);
            JsonArray segmentResults = new JsonArray();
            for (RubySegment segment : segments) {
                JsonObject entry = new JsonObject();
                entry.addProperty("startUtf16", segment.getStartUtf16());
                entry.addProperty("endUtf16", segment.getEndUtf16());
                entry.addProperty("baseText", text.substring(
                        segment.getStartUtf16(), segment.getEndUtf16()));
                entry.addProperty("reading", segment.getRubyText());
                entry.addProperty("fullReading", segment.getReading());
                segmentResults.add(entry);
            }
            result.add("segments", segmentResults);
            results.add(result);
        }
        response.add("results", results);
        return gson.toJson(response);
    }
}
