package org.openroadcode.androidbridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-private JSON Lines, 3 × 1 MiB on disk and at most 200 events in memory. */
final class BridgeLogStore {
  static final int MAX_EVENT_BYTES = 16 * 1024, MAX_EVENTS = 200, FILE_BYTES = 1024 * 1024;
  private static final int RESTORE_BYTES = 256 * 1024;
  private static final AtomicLong GENERATIONS = new AtomicLong(System.currentTimeMillis());
  private final File file;
  private final int fileBytes;
  private final long generation = GENERATIONS.incrementAndGet();
  private final ArrayDeque<Entry> entries = new ArrayDeque<>();
  private long sequence;
  private int memoryBytes;
  private boolean restoreFailed;

  BridgeLogStore(File directory) { this(directory, FILE_BYTES); }
  BridgeLogStore(File directory, int fileBytes) {
    this.file = new File(directory, "bridge.jsonl"); this.fileBytes = fileBytes;
    try { restore(); } catch (Exception ignored) { restoreFailed = true; }
  }
  boolean restoreFailed() { return restoreFailed; }

  synchronized boolean append(JSONObject value) {
    byte[] line = (value.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    if (line.length > Math.min(MAX_EVENT_BYTES, fileBytes) || !valid(value)) return true;
    remember(value.toString());
    try {
      Files.createDirectories(file.getParentFile().toPath());
      boolean partial = false;
      if (file.length() > 0) {
        try (RandomAccessFile existing = new RandomAccessFile(file, "r")) {
          existing.seek(existing.length() - 1); partial = existing.read() != '\n';
        }
      }
      if (file.length() + line.length + (partial ? 1 : 0) > fileBytes) {
        for (int i = 2; i >= 1; i--) {
          File from = i == 1 ? file : new File(file + ".1");
          if (from.exists()) Files.move(from.toPath(), new File(file + "." + i).toPath(),
              StandardCopyOption.REPLACE_EXISTING);
        }
        partial = false;
      }
      try (FileOutputStream out = new FileOutputStream(file, true)) {
        if (partial) out.write('\n');
        out.write(line);
      }
      return true;
    } catch (Exception ignored) { return false; }
  }
  synchronized void memoryOnly(JSONObject value) {
    if (valid(value) && value.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_EVENT_BYTES)
      remember(value.toString());
  }
  private void remember(String value) {
    entries.addLast(new Entry(++sequence, value));
    memoryBytes += value.getBytes(StandardCharsets.UTF_8).length;
    while (entries.size() > MAX_EVENTS || memoryBytes > RESTORE_BYTES)
      memoryBytes -= entries.removeFirst().json.getBytes(StandardCharsets.UTF_8).length;
  }
  synchronized JSONObject read(String cursor, String level, String component) throws Exception {
    int threshold = severity(level);
    if (threshold < 0 || component.length() > 128 || (!component.isEmpty() && !component.matches("[\\w.-]+")))
      throw new IllegalArgumentException("Invalid log filter");
    long after = 0;
    boolean reset = false;
    if (!cursor.isEmpty()) {
      if (!cursor.matches("0:\\d{1,20}:\\d{1,20}:0")) throw new IllegalArgumentException("Invalid cursor");
      String[] parts = cursor.split(":");
      long previousGeneration = Long.parseLong(parts[1]);
      after = Long.parseLong(parts[2]);
      if (previousGeneration != generation || after > sequence
          || (!entries.isEmpty() && after < entries.getFirst().sequence - 1)) {
        reset = true; after = 0;
      }
    }
    JSONArray events = new JSONArray();
    for (Entry entry : entries) {
      if (entry.sequence <= after) continue;
      JSONObject item = new JSONObject(entry.json);
      String name = item.getString("component");
      if (severity(item.getString("level")) >= threshold
          && (component.isEmpty() || name.equals(component) || name.startsWith(component + ".")))
        events.put(item);
    }
    return new JSONObject().put("events", events).put("cursor", "0:" + generation + ":" + sequence + ":0")
        .put("reset", reset).put("has_more", false).put("scope", "Android bridge • this phone");
  }
  private void restore() throws Exception {
    int budget = RESTORE_BYTES;
    ArrayDeque<byte[]> tails = new ArrayDeque<>();
    for (int i = 0; i <= 2 && budget > 0; i++) {
      File source = i == 0 ? file : new File(file + "." + i);
      if (!source.exists()) continue;
      try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
        int count = (int) Math.min(budget, input.length());
        long offset = input.length() - count;
        input.seek(offset);
        byte[] bytes = new byte[count]; input.readFully(bytes); budget -= count;
        if (offset > 0) {
          int newline = 0;
          while (newline < bytes.length && bytes[newline] != '\n') newline++;
          bytes = java.util.Arrays.copyOfRange(bytes, Math.min(bytes.length, newline + 1), bytes.length);
        }
        tails.addFirst(bytes);
      }
    }
    for (byte[] tail : tails) {
      int start = 0;
      for (int end = 0; end < tail.length; end++) {
        if (tail[end] != '\n') continue;
        if (end - start <= MAX_EVENT_BYTES) {
          try {
            JSONObject value = new JSONObject(new String(tail, start, end - start, StandardCharsets.UTF_8));
            if (valid(value)) remember(value.toString());
          } catch (Exception ignored) { }
        }
        start = end + 1;
      }
    }
  }
  private static boolean valid(JSONObject value) {
    try {
      for (String key : new String[] {"timestamp", "level", "component", "event", "message", "operation_id"})
        if (!(value.get(key) instanceof String)) return false;
      Object pid = value.get("pid");
      return (pid instanceof Integer || pid instanceof Long) && ((Number) pid).longValue() > 0
          && severity(value.getString("level")) >= 0 && !value.getString("component").isEmpty()
          && !value.getString("event").isEmpty()
          && value.getString("timestamp").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    } catch (Exception ignored) { return false; }
  }
  private static int severity(String level) {
    switch (level) {
      case "DEBUG": return 10; case "INFO": return 20; case "WARNING": return 30;
      case "ERROR": return 40; case "CRITICAL": return 50; default: return -1;
    }
  }
  private static final class Entry {
    final long sequence; final String json;
    Entry(long sequence, String json) { this.sequence = sequence; this.json = json; }
  }
}
