package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class RuntimeLogBufferTest {
  private JSONObject event(int number) throws Exception {
    return new JSONObject().put("timestamp", "2026-10-04T12:00:00.000Z")
        .put("level", "INFO").put("component", "runtime.apps").put("event", "app.started")
        .put("message", "App " + number).put("pid", 123).put("operation_id", "operation-1");
  }

  private JSONObject page(JSONArray events, String cursor, boolean reset) throws Exception {
    return new JSONObject().put("events", events).put("cursor", cursor).put("reset", reset)
        .put("has_more", false).put("scope", "Shared ORC log store");
  }

  @Test public void retainsOnlyTheNewestTwoHundredEvents() throws Exception {
    RuntimeLogBuffer buffer = new RuntimeLogBuffer();
    for (int start = 0; start < 400; start += 100) {
      JSONArray events = new JSONArray();
      for (int i = start; i < start + 100; i++) events.put(event(i));
      buffer.append(page(events, "1:2:" + start + ":0", false));
    }
    assertEquals(200, buffer.rows().size());
    assertTrue(buffer.rows().get(0).contains("App 200"));
    assertTrue(buffer.rows().get(199).contains("App 399"));
    assertTrue(buffer.text().contains("operation-1"));
    assertEquals("Shared ORC log store", buffer.scope());
  }

  @Test public void boundsHistoryAndIndividualDisplayRows() throws Exception {
    RuntimeLogBuffer buffer = new RuntimeLogBuffer();
    JSONArray events = new JSONArray();
    for (int i = 0; i < 200; i++) events.put(event(i).put("message", "x".repeat(20000)));
    buffer.append(page(events, "1:2:200:0", false));
    assertTrue(buffer.text().length() <= RuntimeLogBuffer.MAX_CHARACTERS);
    assertTrue(buffer.rows().size() < 200);
    assertTrue(buffer.text().contains("display truncated"));
  }

  @Test public void rotationResetAndFilterClearReplaceHistory() throws Exception {
    RuntimeLogBuffer buffer = new RuntimeLogBuffer();
    buffer.append(page(new JSONArray().put(event(1)), "1:2:3:0", false));
    buffer.append(page(new JSONArray().put(event(2)), "1:4:5:0", true));
    assertEquals(1, buffer.rows().size());
    assertTrue(buffer.text().contains("App 2"));
    buffer.clear();
    assertEquals("", buffer.cursor());
    assertTrue(buffer.rows().isEmpty());
  }

  @Test public void malformedPageCannotPartiallyAdvanceHistoryOrCursor() throws Exception {
    RuntimeLogBuffer buffer = new RuntimeLogBuffer();
    buffer.append(page(new JSONArray().put(event(1)), "1:2:3:0", false));
    JSONObject bad = event(3).put("pid", "123");
    try {
      buffer.append(page(new JSONArray().put(event(2)).put(bad), "1:2:100:0", true));
      fail("Malformed event accepted");
    } catch (IllegalArgumentException expected) { }
    assertEquals("1:2:3:0", buffer.cursor());
    assertEquals(1, buffer.rows().size());
    assertTrue(buffer.text().contains("App 1"));
  }

  @Test public void emptyPagesRetainHistoryAndOversizedPagesAreRejected() throws Exception {
    RuntimeLogBuffer buffer = new RuntimeLogBuffer();
    buffer.append(page(new JSONArray().put(event(1)), "1:2:3:0", false));
    buffer.append(page(new JSONArray(), "1:2:4:0", false));
    assertEquals(1, buffer.rows().size());
    JSONArray events = new JSONArray();
    for (int i = 0; i < 201; i++) events.put(event(i));
    try {
      buffer.append(page(events, "1:2:5:0", false));
      fail("Unbounded page accepted");
    } catch (IllegalArgumentException expected) { }
    assertEquals("1:2:4:0", buffer.cursor());
  }

  @Test public void treatsMarkupAsTextAndRemovesControlCodes() throws Exception {
    RuntimeLogBuffer buffer = new RuntimeLogBuffer();
    JSONObject item = event(1).put("message", "<b>plain</b>\u001b[31m");
    buffer.append(page(new JSONArray().put(item), "1:2:3:0", false));
    assertTrue(buffer.text().contains("<b>plain</b>"));
    assertFalse(buffer.text().contains("\u001b"));
  }
}
