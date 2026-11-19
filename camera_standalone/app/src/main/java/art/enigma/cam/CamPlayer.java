package art.enigma.cam;

import android.app.*;
import android.net.*;
import android.view.*;
import android.widget.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.*;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.PlayerView;
import java.util.Locale;

final class CamPlayer {
    interface Listener{void state(String state,String detail);}
    private Dialog dialog;private ExoPlayer player;
    void close(){if(dialog!=null){Dialog old=dialog;dialog=null;old.dismiss();}else if(player!=null){player.release();player=null;}}
    void show(Activity activity,Network network,String address,String title,boolean live,Listener listener) {
        close();if(activity.isFinishing())return;
        Uri uri=Uri.parse(address);boolean remote="http".equals(uri.getScheme())||"rtsp".equals(uri.getScheme());
        if(remote&&(network==null||!CameraNetwork.safePrivateIpv4(uri.getHost()))) {listener.state("error","Connect to the camera Wi-Fi");return;}
        if(!live&&(address+" "+title).toLowerCase(Locale.ROOT).matches(".*\\.jpe?g(?:$| .*| )")){showImage(activity,network,uri,title,listener);return;}
        dialog=new Dialog(activity,android.R.style.Theme_Material_NoActionBar_Fullscreen);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(1);root.setBackgroundColor(0xff080c13);
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(16,12,12,12);
        TextView heading=new TextView(activity);heading.setText(title);heading.setTextColor(0xffe9f0fa);heading.setTextSize(15);heading.setMaxLines(2);bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button exit=new Button(activity);exit.setText("Close");exit.setOnClickListener(v->close());bar.addView(exit);root.addView(bar);
        PlayerView view=new PlayerView(activity);view.setUseController(!live);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
        TextView status=new TextView(activity);status.setTextColor(0xffa9c3df);status.setPadding(20,12,20,20);status.setText(live?"Opening camera live stream…":"Buffering recording directly from camera…");root.addView(status);
        Button retry=new Button(activity);retry.setText("Retry playback");retry.setVisibility(View.GONE);root.addView(retry);
        Button download=new Button(activity);download.setText("Download recording and play locally");download.setVisibility(View.GONE);root.addView(download);
        final ExoPlayer current=new ExoPlayer.Builder(activity,new DefaultRenderersFactory(activity).setEnableDecoderFallback(true)).build();player=current;view.setPlayer(current);
        final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.atomic.AtomicBoolean firstFrame=new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean forceTcp=new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable[] runSource=new Runnable[1];
        final Runnable[] watchdog=new Runnable[1];
        final Runnable cancelWatchdog=()->{if(watchdog[0]!=null)handler.removeCallbacks(watchdog[0]);};
        final Dialog activeDialog=dialog;
        current.addListener(new Player.Listener(){
            @Override public void onRenderedFirstFrame(){
                firstFrame.set(true);cancelWatchdog.run();
                status.setText(live?"LIVE · "+(forceTcp.get()?"TCP":"UDP"):"PLAYING · "+title);
                listener.state("playing",live?"Live frame rendered ("+(forceTcp.get()?"TCP":"UDP")+")":title);
            }
            @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_BUFFERING){status.setText("Buffering…");listener.state("buffering",title);}else if(state==Player.STATE_ENDED){status.setText("Recording ended");listener.state("ended",title);}}
            @Override public void onPlayerError(PlaybackException error){
                String detail=error.getErrorCodeName();if(error.getCause()!=null)detail+=" · "+error.getCause().getClass().getSimpleName()+": "+error.getCause().getMessage();
                if(live&&!firstFrame.get()&&!forceTcp.get()&&dialog==activeDialog&&runSource[0]!=null){
                    forceTcp.set(true);listener.state("transport","UDP unsuccessful; retrying TCP: "+CamText.excerpt(detail));runSource[0].run();return;
                }
                cancelWatchdog.run();status.setText("Playback failed: "+CamText.excerpt(detail));retry.setVisibility(View.VISIBLE);
                if(!live&&remote&&"http".equals(uri.getScheme()))download.setVisibility(View.VISIBLE);
                listener.state("error",CamText.excerpt(detail));
            }
        });
        dialog.setOnDismissListener(d->{cancelWatchdog.run();current.release();if(player==current)player=null;listener.state("closed",title);});
        dialog.setContentView(root);dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        retry.setOnClickListener(v->{retry.setVisibility(View.GONE);download.setVisibility(View.GONE);forceTcp.set(false);runSource[0].run();});
        download.setOnClickListener(v->{
            if(live||!"http".equals(uri.getScheme())||network==null)return;
            download.setEnabled(false);status.setText("Saving original recording to phone before playback...");
            new Thread(()->{
                try{
                    Uri saved=FastDownload.download(network,activity.getContentResolver(),uri.getHost(),uri.toString(),
                        (size,total,mode)->activity.runOnUiThread(()->{
                            if(dialog==activeDialog&&total>0)status.setText("Downloading: "+Math.min(100,size*100/total)+"%");
                        }),note->{});
                    activity.runOnUiThread(()->{if(dialog==activeDialog)show(activity,null,saved.toString(),title,false,listener);});
                }catch(Exception ex){activity.runOnUiThread(()->{
                    if(dialog==activeDialog){download.setEnabled(true);status.setText("Download failed: "+CamText.excerpt(ex.toString()));listener.state("error",CamText.excerpt(ex.toString()));}
                });}
            },"enigma-camera-local-play").start();
        });
        try{
            MediaItem item=new MediaItem.Builder().setUri(uri).setMimeType(live?MimeTypes.APPLICATION_RTSP:
                (address+" "+title).toLowerCase(Locale.ROOT).contains(".ts")?MimeTypes.VIDEO_MP2T:MimeTypes.VIDEO_MP4).build();
            MediaSource cachedRecordingSource=null;
            if(!live){
                DataSource.Factory factory=remote?new CameraDataSource.Factory(network,uri.getHost()):new DefaultDataSource.Factory(activity);
                cachedRecordingSource=new ProgressiveMediaSource.Factory(factory,new DefaultExtractorsFactory().setTsExtractorFlags(9)).createMediaSource(item);
            }
            final MediaSource recordingSource=cachedRecordingSource;
            runSource[0]=()->{
                if(dialog!=activeDialog||player!=current)return;
                cancelWatchdog.run();firstFrame.set(false);retry.setVisibility(View.GONE);download.setVisibility(View.GONE);
                try{
                    MediaSource source=live?new RtspMediaSource.Factory()
                        .setSocketFactory(network.getSocketFactory())
                        .setForceUseRtpTcp(forceTcp.get()).setTimeoutMs(6500).createMediaSource(item):recordingSource;
                    current.stop();current.setMediaSource(source);current.prepare();current.play();
                    status.setText(live?"Waiting for RTSP video over "+(forceTcp.get()?"TCP":"UDP")+"...":"Buffering recording...");
                    watchdog[0]=()->{
                        if(dialog!=activeDialog||firstFrame.get())return;
                        if(live&&!forceTcp.get()){
                            forceTcp.set(true);listener.state("transport","No RTP frames over UDP; trying TCP");runSource[0].run();
                        }else{
                            current.stop();String reason=live?"RTSP stream never delivered a rendered frame (TCP/UDP attempts finished).":
                                "Camera clip did not produce decodable frames. Download and play locally instead.";
                            status.setText(reason);retry.setVisibility(View.VISIBLE);
                            if(!live&&remote&&"http".equals(uri.getScheme()))download.setVisibility(View.VISIBLE);
                            listener.state("error",reason);
                        }
                    };
                    handler.postDelayed(watchdog[0],live?18000:20000);
                }catch(Exception ex){status.setText(CamText.excerpt(ex.toString()));retry.setVisibility(View.VISIBLE);listener.state("error",CamText.excerpt(ex.toString()));}
            };
            runSource[0].run();
        }catch(Exception e){status.setText(CamText.excerpt(e.toString()));retry.setVisibility(View.VISIBLE);listener.state("error",CamText.excerpt(e.toString()));}
    }
    private void showImage(Activity activity,Network network,Uri uri,String title,Listener listener) {
        final Dialog imageDialog=new Dialog(activity,android.R.style.Theme_Material_NoActionBar_Fullscreen);dialog=imageDialog;
        LinearLayout root=new LinearLayout(activity);root.setOrientation(1);root.setBackgroundColor(0xff080c13);
        Button done=new Button(activity);done.setText("Close photo");done.setOnClickListener(v->close());root.addView(done);
        ImageView image=new ImageView(activity);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        TextView status=new TextView(activity);status.setTextColor(0xffe9f0fa);status.setPadding(20,15,20,20);status.setText("Loading "+title);root.addView(status);imageDialog.setContentView(root);imageDialog.show();
        final java.util.concurrent.ExecutorService loader=java.util.concurrent.Executors.newSingleThreadExecutor();
        loader.execute(()->{
            java.net.HttpURLConnection connection=null;
            try {
                InputStream raw;
                if("http".equals(uri.getScheme())) {
                    if(CamText.sdLink(uri.getHost(),"sd/",uri.toString()).isEmpty())throw new IOException("Invalid camera image");
                    connection=CameraProtocol.open(network,uri.toString());if(connection.getResponseCode()!=200)throw new IOException("Camera image HTTP "+connection.getResponseCode());raw=connection.getInputStream();
                }else raw=activity.getContentResolver().openInputStream(uri);
                if(raw==null)throw new IOException("Image unavailable");
                byte[] data;
                try(InputStream in=raw;ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[32768];int n;
                    while((n=in.read(buffer))!=-1){if(bytes.size()+n>30000000)throw new IOException("Image exceeds preview limit");bytes.write(buffer,0,n);}
                    data=bytes.toByteArray();
                }
                BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,opts);
                int sample=1;while(opts.outWidth/sample>2560||opts.outHeight/sample>2560)sample*=2;opts.inJustDecodeBounds=false;opts.inSampleSize=sample;
                Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,opts);if(bitmap==null)throw new IOException("Unsupported image data");
                activity.runOnUiThread(()->{if(imageDialog.isShowing()){image.setImageBitmap(bitmap);status.setText(title);listener.state("playing",title);}else bitmap.recycle();});
            }catch(Exception e){activity.runOnUiThread(()->{status.setText(e.toString());listener.state("error",e.toString());});}
            finally{if(connection!=null)connection.disconnect();loader.shutdown();}
        });
    }
}
