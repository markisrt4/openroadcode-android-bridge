package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings.Target;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Visible-screen-only live viewing; never starts or controls a runtime service. */
final class RuntimeLogsScreen {
  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final RuntimeLogBuffer buffer = new RuntimeLogBuffer();
  private final RuntimeLogPolling polling = new RuntimeLogPolling();
  private final LinearLayout root;
  private final TextView status;
  private final TextView scope;
  private final TextView empty;
  private final ListView list;
  private final ArrayAdapter<String> adapter;
  private final Button pause;
  private final EditText componentInput;
  private Target target;
  private String level = "INFO";
  private String component = "";
  private boolean bridgeSource;
  private boolean visible;
  private boolean paused;
  private long retryDelay = 1000;
  private RuntimeLogClient client;
  private final Runnable poll = this::readPage;

  RuntimeLogsScreen(Activity activity) {
    this.activity = activity;
    settings = new RuntimeServiceManagerSettings(activity);
    target = settings.target();
    root = UiTheme.card(activity);
    TextView introduction = UiTheme.text(activity,
        "Recent logs from the selected runtime. Live updates run only while this screen is open.",
        12, UiTheme.MUTED);
    root.addView(introduction);

    Spinner targets = spinner(new String[] {"Termux", "Remote Linux", "Android bridge"});
    targets.setSelection(target == Target.TERMUX ? 0 : 1);
    root.addView(targets);
    TextView targetHelp = UiTheme.text(activity,
        "Android bridge reads this app's private logs. Remote Linux uses saved pairing.", 11, UiTheme.MUTED);
    root.addView(targetHelp);

    Spinner levels = spinner(new String[] {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"});
    levels.setSelection(1);
    root.addView(UiTheme.text(activity, "Minimum severity", 12, UiTheme.SILVER));
    root.addView(levels);
    componentInput = new EditText(activity);
    componentInput.setSingleLine(true);
    componentInput.setTextColor(UiTheme.TEXT);
    componentInput.setHintTextColor(UiTheme.MUTED);
    componentInput.setHint("Component prefix (optional), e.g. runtime");
    componentInput.setTextSize(12);
    root.addView(componentInput);
    root.addView(UiTheme.actionButton(activity, "Apply component filter", UiTheme.SURFACE_RAISED,
        v -> applyComponent()));

    LinearLayout actions = new LinearLayout(activity);
    pause = UiTheme.actionButton(activity, "Pause", UiTheme.BLUE, v -> togglePause());
    addAction(actions, pause);
    addAction(actions, UiTheme.actionButton(activity, "Copy", UiTheme.SURFACE_RAISED,
        v -> copy()));
    addAction(actions, UiTheme.actionButton(activity, "Share", UiTheme.SURFACE_RAISED,
        v -> share()));
    root.addView(actions);

    status = UiTheme.text(activity, "Ready to connect", 12, UiTheme.MUTED);
    status.setPadding(0, dp(10), 0, dp(4));
    root.addView(status);
    scope = UiTheme.text(activity, "", 11, UiTheme.MUTED);
    root.addView(scope);
    root.addView(UiTheme.text(activity,
        "Keeps up to 200 events. Each source is separate; Linux shows the manager's private store. Copy/share includes "
            + "the displayed history; review it before sending.", 10, UiTheme.MUTED));

    adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1) {
      @Override public View getView(int position, View convertView, ViewGroup parent) {
        TextView text = (TextView) super.getView(position, convertView, parent);
        text.setTextColor(UiTheme.TEXT);
        text.setTextSize(11);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        return text;
      }
    };
    list = new ListView(activity);
    list.setAdapter(adapter);
    list.setBackgroundColor(UiTheme.BG);
    // The activity has an outer ScrollView; let history handle its own scroll.
    list.setOnTouchListener((v, event) -> {
      v.getParent().requestDisallowInterceptTouchEvent(true);
      return false;
    });
    root.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
    empty = UiTheme.text(activity, "No matching events yet.", 12, UiTheme.MUTED);
    root.addView(empty);
    list.setEmptyView(empty);

    targets.setOnItemSelectedListener(selected(position -> {
      boolean chosenBridge = position == 2;
      Target chosen = position == 0 ? Target.TERMUX : Target.REMOTE_PI;
      if (chosenBridge != bridgeSource || (!chosenBridge && chosen != target)) {
        bridgeSource = chosenBridge;
        if (!chosenBridge) target = chosen;
        reload();
      }
    }));
    levels.setOnItemSelectedListener(selected(position -> {
      String chosen = new String[] {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}[position];
      if (!chosen.equals(level)) { level = chosen; reload(); }
    }));
  }

  View view() { return root; }

  void start() {
    visible = true;
    if (!paused) connect();
  }

  void stop() {
    visible = false;
    cancel();
  }

  private void cancel() {
    polling.stop();
    handler.removeCallbacks(poll);
    if (client != null) client.close();
    client = null;
  }

  private void connect() {
    cancel();
    if (!visible || paused) return;
    if (!bridgeSource && target == Target.REMOTE_PI && !settings.hasRemotePiConfiguration()) {
      setStatus("Configure and pair Remote Linux on the Runtime screen.", UiTheme.AMBER);
      return;
    }
    client = bridgeSource ? null : target == Target.TERMUX
        ? new RuntimeLogClient(TermuxServiceManagerClient.BASE_URL, null)
        : new RuntimeLogClient(settings.piBaseUrl(), settings.piToken());
    polling.start();
    retryDelay = 1000;
    setStatus("Connecting to " + targetLabel() + "…", UiTheme.BLUE);
    handler.post(poll);
  }

  private void readPage() {
    if (!visible || paused || (!bridgeSource && client == null)) return;
    long requestGeneration = polling.begin();
    if (requestGeneration < 0) return;
    RuntimeLogClient requestClient = client;
    boolean local = bridgeSource;
    String cursor = buffer.cursor(), requestLevel = level, requestComponent = component;
    new Thread(() -> {
      JSONObject page = null;
      Exception failure = null;
      try { page = local ? BridgeLog.read(cursor, requestLevel, requestComponent)
                         : requestClient.read(cursor, requestLevel, requestComponent); }
      catch (Exception error) { failure = error; }
      final JSONObject result = page;
      final Exception error = failure;
      handler.post(() -> {
        if (!polling.complete(requestGeneration)) return;
        if (error instanceof RuntimeLogClient.LogAccessException) {
          setStatus(error.getMessage(), UiTheme.AMBER);
          cancel();
          return;
        }
        if (error != null) { retry(); return; }
        try {
          boolean reset = result.getBoolean("reset");
          boolean more = buffer.append(result);
          adapter.clear();
          adapter.addAll(buffer.rows());
          adapter.notifyDataSetChanged();
          if (result.getJSONArray("events").length() > 0 && adapter.getCount() > 0)
            list.setSelection(adapter.getCount() - 1);
          scope.setText(targetLabel() + " • " + buffer.scope());
          setStatus(reset ? "Live • older history rotated out; showing recent logs"
                          : "Live • " + adapter.getCount() + " recent events", UiTheme.GREEN);
          retryDelay = 1000;
          handler.postDelayed(poll, more ? 250 : 1000);
        } catch (Exception invalidPage) {
          retry();
        }
      });
    }, "orc-live-logs").start();
  }

  private void retry() {
    setStatus("Disconnected • retrying in " + (retryDelay / 1000) + "s", UiTheme.AMBER);
    handler.postDelayed(poll, retryDelay);
    retryDelay = Math.min(15000, retryDelay * 2);
  }

  private void reload() {
    cancel();
    buffer.clear();
    adapter.clear();
    scope.setText("");
    if (paused) setStatus("Paused • resume to load this filter", UiTheme.MUTED);
    else if (visible) connect();
  }

  private void applyComponent() {
    String value = componentInput.getText().toString().trim();
    if (value.length() > 128 || (!value.isEmpty() && !value.matches("[\\w.-]+"))) {
      componentInput.setError("Use a component name or dotted prefix");
      return;
    }
    component = value;
    reload();
  }

  private void togglePause() {
    paused = !paused;
    pause.setText(paused ? "Resume" : "Pause");
    if (paused) { cancel(); setStatus("Paused • history retained", UiTheme.MUTED); }
    else connect();
  }

  private void copy() {
    if (buffer.rows().isEmpty()) { noHistory(); return; }
    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("ORC logs", buffer.text()));
    Toast.makeText(activity, "Displayed logs copied", Toast.LENGTH_SHORT).show();
  }

  private void share() {
    if (buffer.rows().isEmpty()) { noHistory(); return; }
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_SUBJECT, "ORC logs • " + targetLabel());
    intent.putExtra(Intent.EXTRA_TEXT, buffer.text());
    activity.startActivity(Intent.createChooser(intent, "Share displayed logs"));
  }

  private void noHistory() {
    Toast.makeText(activity, "No displayed logs to copy or share", Toast.LENGTH_SHORT).show();
  }

  private String targetLabel() {
    return bridgeSource ? "Android bridge" : target == Target.TERMUX ? "Termux" : "Remote Linux";
  }
  private int dp(int value) { return UiTheme.dp(activity, value); }
  private void setStatus(String value, int color) { status.setText(value); status.setTextColor(color); }
  private void addAction(LinearLayout row, Button button) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
    params.setMargins(dp(2), dp(8), dp(2), 0);
    row.addView(button, params);
  }
  private Spinner spinner(String[] labels) {
    Spinner spinner = new Spinner(activity);
    ArrayAdapter<String> items = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, labels);
    items.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(items);
    return spinner;
  }
  private AdapterView.OnItemSelectedListener selected(java.util.function.IntConsumer listener) {
    return new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        listener.accept(position);
      }
      @Override public void onNothingSelected(AdapterView<?> parent) { }
    };
  }
}
