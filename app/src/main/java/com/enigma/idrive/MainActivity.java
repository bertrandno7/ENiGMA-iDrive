package com.enigma.idrive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

public final class MainActivity extends Activity {
 private static final int REQUEST=900;
 private EditText host,port;
 private Spinner rate;
 private TextView status;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable update=new Runnable(){public void run(){if(status!=null)status.setText(StreamService.status);handler.postDelayed(this,500);}};
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView label(LinearLayout root,String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(10),0,dp(10));root.addView(t);return t;}
 private Button button(LinearLayout root,String s,Runnable action){Button b=new Button(this);b.setText(s);root.addView(b);b.setOnClickListener(v->action.run());return b;}
 @Override public void onCreate(Bundle saved){super.onCreate(saved);
  ScrollView outer=new ScrollView(this);outer.setBackgroundColor(Color.rgb(12,20,27));
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(30),dp(24),dp(24));outer.addView(root);setContentView(outer);
  label(root,"ENiGMA iDRIVE",30,Color.rgb(61,217,221));
  label(root,"LIVE H.264 STREAM LAB  |  v0.2",15,Color.WHITE);
  label(root,"This app sends live screen video to a trusted laptop. It does not add a receiver to your factory BMW head unit.",14,Color.LTGRAY);
  label(root,"Receiver IP address",14,Color.LTGRAY);
  host=new EditText(this);host.setSingleLine(true);host.setTextColor(Color.WHITE);host.setHintTextColor(Color.GRAY);host.setHint("192.168.1.100");host.setText(getPreferences(MODE_PRIVATE).getString("host",""));root.addView(host);
  label(root,"Port",14,Color.LTGRAY);
  port=new EditText(this);port.setSingleLine(true);port.setText("5567");port.setTextColor(Color.WHITE);port.setInputType(2);root.addView(port);
  rate=new Spinner(this);String[] options={"30 fps / 4 Mbps","60 fps / 8 Mbps"};ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);rate.setAdapter(adapter);root.addView(rate);
  button(root,"START LIVE VIDEO",this::start);
  button(root,"STOP VIDEO",()->{Intent i=new Intent(this,StreamService.class);i.setAction(StreamService.STOP);startService(i);});
  button(root,"OPEN GOOGLE MAPS",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="))));
  button(root,"OPEN WIFI SETTINGS",()->startActivity(new Intent("android.settings.WIFI_SETTINGS")));
  status=label(root,"Idle",16,Color.rgb(61,217,221));
  label(root,"Consent-based screen capture. Use only when parked, on a trusted Wi-Fi network. Video transport is not encrypted; do not expose the receiver to the public internet.",13,Color.LTGRAY);
 }
 private void error(String s){new AlertDialog.Builder(this).setTitle("ENiGMA iDrive").setMessage(s).setPositiveButton("OK",null).show();}
 private void start(){String h=host.getText().toString().trim(); if(!h.matches("[A-Za-z0-9.\\-]{1,180}")){error("Enter a valid IP or local hostname.");return;} int p;try{p=Integer.parseInt(port.getText().toString());}catch(Exception e){error("Invalid port.");return;} if(p<1024||p>65535){error("Port must be between 1024 and 65535.");return;}getPreferences(MODE_PRIVATE).edit().putString("host",h).apply(); MediaProjectionManager m=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);if(m==null){error("Screen capture unsupported.");return;}startActivityForResult(m.createScreenCaptureIntent(),REQUEST);}
 @Override protected void onActivityResult(int req,int code,Intent data){super.onActivityResult(req,code,data);if(req!=REQUEST)return;if(code!=RESULT_OK||data==null){StreamService.status="Screen capture cancelled";return;}Intent i=new Intent(this,StreamService.class);i.setAction(StreamService.START);i.putExtra("resultCode",code);i.putExtra("resultData",data);i.putExtra("host",host.getText().toString().trim());i.putExtra("port",Integer.parseInt(port.getText().toString()));i.putExtra("fps",rate.getSelectedItemPosition()==0?30:60);startForegroundService(i);}
 @Override protected void onResume(){super.onResume();handler.post(update);}
 @Override protected void onPause(){handler.removeCallbacks(update);super.onPause();}
}
