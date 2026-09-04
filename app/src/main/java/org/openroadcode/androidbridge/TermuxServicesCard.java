package org.openroadcode.androidbridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Self-contained UI/controller for OpenRoadCode services supervised by Termux runit. */
public final class TermuxServicesCard {
    private static final int SURFACE=Color.rgb(11,24,33),SURFACE_RAISED=Color.rgb(16,34,46),BORDER=Color.rgb(36,64,79),TEXT=Color.rgb(243,247,249),MUTED=Color.rgb(147,164,174),BLUE=Color.rgb(22,139,209),GREEN=Color.rgb(132,206,31),RED=Color.rgb(241,90,22);
    private static final long REFRESH_MS=2000;
    private final Activity activity;
    private final TermuxServiceManagerClient client=new TermuxServiceManagerClient();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,TextView> serviceStates=new LinkedHashMap<>();
    private final LinearLayout root;
    private final TextView managerStatus;
    private final Runnable refreshTask=new Runnable(){@Override public void run(){refresh();handler.postDelayed(this,REFRESH_MS);}};

    public TermuxServicesCard(Activity activity){
        this.activity=activity;
        root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(16),dp(12),dp(16));root.setBackground(rounded(SURFACE,BORDER,14));
        TextView title=text("OPENROADCODE SERVICES",18,TEXT);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setLetterSpacing(.08f);root.addView(title);
        TextView subtitle=text("Termux • runit • localhost control plane",12,BLUE);subtitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);subtitle.setPadding(0,dp(2),0,dp(10));root.addView(subtitle);
        managerStatus=statusPill("Checking Termux service manager…",MUTED);root.addView(managerStatus);
        root.addView(buttonRow(actionButton("START CORE",BLUE,v->runAction(()->client.startCoreStack())),actionButton("STOP CORE",RED,v->runAction(()->client.stopCoreStack()))));
        addService("openroadcode-broker","Message broker",false);
        addService("openroadcode-navigation","Navigation",false);
        addService("openroadcode-automotive","Automotive",false);
        addService("openroadcode-adsb","ADS-B",true);
    }

    public View view(){return root;}
    public void start(){handler.removeCallbacks(refreshTask);handler.post(refreshTask);}
    public void stop(){handler.removeCallbacks(refreshTask);}

    private void addService(String id,String label,boolean controls){
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(2),dp(8),dp(2),controls?dp(2):dp(8));
        TextView name=text(label,14,TEXT);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView state=text("● UNKNOWN",12,MUTED);state.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);state.setGravity(Gravity.END);row.addView(state,new LinearLayout.LayoutParams(0,-2,1));serviceStates.put(id,state);root.addView(row);
        if(controls){root.addView(buttonRow(actionButton("START ADS-B",BLUE,v->runAction(()->client.startService(id))),actionButton("STOP ADS-B",RED,v->runAction(()->client.stopService(id)))));}
    }

    private void refresh(){new Thread(()->{try{JSONObject result=client.getServices();activity.runOnUiThread(()->render(result));}catch(Exception e){activity.runOnUiThread(()->renderUnavailable());}},"orc-service-status").start();}
    private void render(JSONObject result){
        managerStatus.setText("●  Termux service manager available");managerStatus.setTextColor(GREEN);
        JSONArray services=result.optJSONArray("services");if(services==null)return;
        for(int i=0;i<services.length();i++){JSONObject service=services.optJSONObject(i);if(service==null)continue;String id=service.optString("name",service.optString("service",""));TextView view=serviceStates.get(id);if(view==null)continue;boolean running=service.optBoolean("running",false);String state=service.optString("state",running?"running":"stopped");view.setText("● "+state.toUpperCase(Locale.US));view.setTextColor(running?GREEN:MUTED);}
    }
    private void renderUnavailable(){managerStatus.setText("●  Termux service manager unavailable");managerStatus.setTextColor(RED);for(TextView state:serviceStates.values()){state.setText("● UNKNOWN");state.setTextColor(MUTED);}}
    private void runAction(Action action){managerStatus.setText("●  Applying service change…");managerStatus.setTextColor(BLUE);new Thread(()->{try{action.run();activity.runOnUiThread(this::refresh);}catch(Exception e){activity.runOnUiThread(()->{managerStatus.setText("●  "+e.getMessage());managerStatus.setTextColor(RED);});}},"orc-service-action").start();}
    private interface Action{JSONObject run() throws Exception;}
    private Button actionButton(String label,int color,View.OnClickListener listener){Button b=new Button(activity);b.setText(label);b.setTextColor(TEXT);b.setTextSize(11);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setLetterSpacing(.08f);b.setAllCaps(false);b.setBackground(rounded(color,color,9));b.setOnClickListener(listener);return b;}
    private LinearLayout buttonRow(Button...buttons){LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER);row.setPadding(0,dp(8),0,dp(8));for(Button b:buttons){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(3),0,dp(3),0);row.addView(b,p);}return row;}
    private TextView statusPill(String value,int color){TextView v=text("●  "+value,13,color);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(dp(10),dp(8),dp(10),dp(8));v.setBackground(rounded(SURFACE_RAISED,BORDER,9));return v;}
    private TextView text(String value,float size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private GradientDrawable rounded(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
