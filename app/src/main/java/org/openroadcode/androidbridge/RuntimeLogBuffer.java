package org.openroadcode.androidbridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded recent history; shared by screen rendering and explicit copy/share. */
final class RuntimeLogBuffer {
  static final int MAX_EVENTS = 200;
  static final int MAX_CHARACTERS = 128 * 1024;
  private static final int MAX_ROW_CHARACTERS = 8192;
  private final ArrayDeque<String> rows = new ArrayDeque<>();
  private int characters;
  private String cursor = "";
  private String scope = "Connected runtime log store";

  String cursor() { return cursor; }
  String scope() { return scope; }
  List<String> rows() { return new ArrayList<>(rows); }
  String text() { return String.join("\n\n", rows); }

  void clear() {
    rows.clear();
    characters = 0;
    cursor = "";
  }

  boolean append(JSONObject page) throws Exception {
    JSONArray events = page.getJSONArray("events");
    String nextCursor = page.getString("cursor");
    if (!nextCursor.isEmpty() && !nextCursor.matches("\\d{1,20}:\\d{1,20}:\\d{1,20}:[01]"))
      throw new IllegalArgumentException("Invalid log cursor");
    if (events.length() > MAX_EVENTS) throw new IllegalArgumentException("Too many log events");
    // Validate the complete page before changing the cursor or visible history.
    List<String> parsed = new ArrayList<>();
    for (int i = 0; i < events.length(); i++) parsed.add(format(events.getJSONObject(i)));
    boolean reset = page.getBoolean("reset");
    boolean more = page.getBoolean("has_more");
    String nextScope = page.getString("scope");
    if (nextScope.length() > 128) throw new IllegalArgumentException("Invalid log scope");
    if (reset) clear();
    for (String row : parsed) {
      rows.addLast(row);
      characters += row.length() + 2;
      while (rows.size() > MAX_EVENTS || characters > MAX_CHARACTERS)
        characters -= rows.removeFirst().length() + 2;
    }
    cursor = nextCursor;
    scope = nextScope;
    return more;
  }

  private static String format(JSONObject item) throws Exception {
    String timestamp = requiredString(item, "timestamp");
    String level = requiredString(item, "level");
    String component = requiredString(item, "component");
    String event = requiredString(item, "event");
    String message = requiredString(item, "message");
    Object pid = item.get("pid");
    if (!timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
        || !level.matches("DEBUG|INFO|WARNING|ERROR|CRITICAL")
        || component.isEmpty() || event.isEmpty()
        || !(pid instanceof Integer || pid instanceof Long) || ((Number) pid).longValue() <= 0)
      throw new IllegalArgumentException("Invalid log event");
    JSONObject context = new JSONObject(item.toString());
    for (String key : new String[] {"timestamp", "level", "component", "event", "message"})
      context.remove(key);
    String row = timestamp + "  " + level + "  " + component + "\n[" + event + "] "
        + message + "\n" + context;
    // TextView and exported text receive plain printable content, never HTML.
    row = row.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
    if (row.length() > MAX_ROW_CHARACTERS)
      row = row.substring(0, MAX_ROW_CHARACTERS - 20) + "\n[display truncated]";
    return row;
  }

  private static String requiredString(JSONObject item, String key) throws Exception {
    Object value = item.get(key);
    if (!(value instanceof String)) throw new IllegalArgumentException("Invalid log field");
    return (String) value;
  }
}
