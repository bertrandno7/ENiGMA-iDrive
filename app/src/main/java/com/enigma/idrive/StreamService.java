package com.enigma.idrive;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.Log;
import android.view.Surface;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

/** Consent-based hardware-encoded local-lab video sender. NOT an Android Auto implementation. */
public final class StreamService extends Service {
 public static final String START="com.enigma.idrive.START", STOP="com.enigma.idrive.STOP";
 public static volatile String status="Idle";
 private static final String CHANNEL="stream"; private static final int NOTIF=207;
 private volatile boolean running=false; private MediaProjection projection; private MediaProjection.Callback callback;
 private MediaCodec encoder; private VirtualDisplay display; private Surface surface; private Socket socket;
 private Thread worker;
 private final Object lock=new Object();
 private void channel(){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm!=null)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"ENiGMA live stream",NotificationManager.IMPORTANCE_LOW));}
 private Notification notif(){Intent i=new Intent(this,StreamService.class).setAction(STOP);PendingIntent pi=PendingIntent.getService(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,CHANNEL).setContentTitle("ENiGMA iDrive").setContentText("Screen sharing active — tap Stop to end").setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).addAction(android.R.drawable.ic_media_pause,"Stop",pi).build();}
 @Override public int onStartCommand(Intent i,int flags,int startId){
  if(i==null)return START_NOT_STICKY;
  if(STOP.equals(i.getAction())){shutdown();stopSelf();return START_NOT_STICKY;}
  if(!START.equals(i.getAction()))return START_NOT_STICKY;
  synchronized(lock){if(running)return START_NOT_STICKY;running=true;}
  try{
   channel();
   if(Build.VERSION.SDK_INT>=29)startForeground(NOTIF,notif(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
   else startForeground(NOTIF,notif());
   Intent token=(Intent)i.getParcelableExtra("resultData");int code=i.getIntExtra("resultCode",0);
   String host=i.getStringExtra("host");int port=i.getIntExtra("port",5567),fps=i.getIntExtra("fps",30);
   if(token==null||host==null)throw new IllegalArgumentException("Projection permission missing");
   MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
   projection=manager.getMediaProjection(code,token);
   if(projection==null)throw new IllegalStateException("Permission denied");
   callback=new MediaProjection.Callback(){@Override public void onStop(){status="Projection stopped by Android";shutdown();stopSelf();}};
   projection.registerCallback(callback,new Handler(Looper.getMainLooper()));
   int width=1280,height=720;
   MediaFormat fmt=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
   fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
   fmt.setInteger(MediaFormat.KEY_BIT_RATE,fps>=60?8000000:4000000);
   fmt.setInteger(MediaFormat.KEY_FRAME_RATE,fps>=60?60:30);
   fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
   encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
   encoder.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
   surface=encoder.createInputSurface();
   encoder.start();
   display=projection.createVirtualDisplay("ENiGMA",width,height,getResources().getDisplayMetrics().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null);
   if(display==null)throw new IllegalStateException("Cannot create virtual display");
   worker=new Thread(()->loop(host,port,fps),"enigma-encoder");
   worker.start();status="Streaming service ready";
  }catch(Exception e){status="Start failed: "+e.getMessage();Log.e("ENiGMA","start",e);shutdown();stopSelf();}
  return START_NOT_STICKY;
 }
 private static void packet(DataOutputStream o,ByteBuffer data,MediaCodec.BufferInfo info)throws IOException {
  if(info.size<=0||info.size>4000000)return;
  byte[] b=new byte[info.size];data.position(info.offset);data.limit(info.offset+info.size);data.get(b);
  o.writeInt(b.length);o.writeByte(((info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0?1:0)|((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0?2:0));o.writeLong(info.presentationTimeUs);o.write(b);
 }
 private static void csd(DataOutputStream o,MediaFormat fmt)throws IOException {
  if(fmt==null)return;
  for(int k=0;k<2;k++){ByteBuffer b=fmt.getByteBuffer("csd-"+k);if(b==null)continue;byte[] buf=new byte[b.remaining()];b.get(buf);o.writeInt(buf.length);o.writeByte(2);o.writeLong(0);o.write(buf);}
 }
 private void keyframe(){try{Bundle b=new Bundle();b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);MediaCodec c=encoder;if(c!=null)c.setParameters(b);}catch(Exception ignored){}}
 private void disconnect(){Socket s=socket;socket=null;if(s!=null)try{s.close();}catch(IOException ignored){}}
 private void loop(String host,int port,int fps){
  MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();MediaFormat output=null;DataOutputStream out=null;
  long nextTry=0;boolean gotKey=false;long frames=0;
  try {
   while(running){
    MediaCodec codec=encoder;if(codec==null)break;
    int idx=codec.dequeueOutputBuffer(info,20000);
    if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){output=codec.getOutputFormat();continue;}
    if(idx<0)continue;
    try{
     ByteBuffer data=codec.getOutputBuffer(idx);if(data==null||info.size==0)continue;
     if(out==null&&System.currentTimeMillis()>nextTry){
      nextTry=System.currentTimeMillis()+1800;
      try{Socket s=new Socket();s.setTcpNoDelay(true);s.connect(new InetSocketAddress(host,port),1800);socket=s;out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream(),65536));out.write(new byte[]{'E','N','I','G','M','A','0','1'});out.writeInt(1280);out.writeInt(720);out.writeInt(fps);csd(out,output);out.flush();gotKey=false;keyframe();status="Connected to "+host+":"+port;}
      catch(IOException e){disconnect();out=null;status="Waiting for receiver "+host+":"+port;}
     }
     if(out==null)continue;
     if((info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0)gotKey=true;
     if(!gotKey&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0)continue;
     try{packet(out,data,info);out.flush();frames++;if(frames%150==0)status="Streaming H.264 — "+frames+" frames sent";}
     catch(IOException e){disconnect();out=null;gotKey=false;status="Connection lost — reconnecting";}
    }finally{codec.releaseOutputBuffer(idx,false);}
   }
  }catch(Exception e){Log.e("ENiGMA","stream",e);status="Stream error: "+e.getMessage();}
  finally{disconnect();if(running){shutdown();stopSelf();}}
 }
 private void shutdown(){
  synchronized(lock){running=false;}
  disconnect();
  if(display!=null){try{display.release();}catch(Exception ignored){}display=null;}
  if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}try{encoder.release();}catch(Exception ignored){}encoder=null;}
  if(surface!=null){try{surface.release();}catch(Exception ignored){}surface=null;}
  MediaProjection p=projection;projection=null;
  if(p!=null){try{if(callback!=null)p.unregisterCallback(callback);}catch(Exception ignored){}try{p.stop();}catch(Exception ignored){}}
  callback=null;stopForeground(STOP_FOREGROUND_REMOVE);
  if(!status.startsWith("Stream error")&&!status.startsWith("Start failed"))status="Stopped";
 }
 @Override public void onDestroy(){shutdown();super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}
