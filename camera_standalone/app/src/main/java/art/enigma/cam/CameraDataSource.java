package art.enigma.cam;

import android.net.Network;
import android.net.Uri;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;

/** Camera HTTP media reads remain on the camera Network, including range seeks. */
final class CameraDataSource extends BaseDataSource {
    static final class Factory implements DataSource.Factory {
        private final Network network;private final String host;
        Factory(Network n,String h){network=n;host=h;}
        @Override public DataSource createDataSource(){return new CameraDataSource(network,host);}
    }
    private final Network network;private final String host;
    private HttpURLConnection connection;private InputStream input;private Uri uri;
    private long remaining;private boolean started;
    CameraDataSource(Network n,String h){super(true);network=n;host=h;}
    @Override public long open(DataSpec spec)throws IOException {
        if(network==null)throw new IOException("Camera Wi-Fi disconnected");
        String address=spec.uri.toString();
        if(!"http".equals(spec.uri.getScheme())||!host.equals(spec.uri.getHost())||spec.uri.getUserInfo()!=null||spec.uri.getQuery()!=null
            ||spec.uri.getFragment()!=null||!CamText.media(spec.uri.getPath())||CamText.sdLink(host,"sd/",address).isEmpty())throw new IOException("Invalid camera recording URL");
        transferInitializing(spec);uri=spec.uri;
        try {
            connection=CameraProtocol.open(network,address);connection.setReadTimeout(16000);
            connection.setRequestProperty("Accept-Encoding","identity");
            if(spec.position>0||spec.length!=-1) {
                String range="bytes="+spec.position+"-"+(spec.length==-1?"":Long.toString(spec.position+spec.length-1));
                connection.setRequestProperty("Range",range);
            }
            int status=connection.getResponseCode();
            if(status!=200&&status!=206)throw new IOException("Camera media HTTP "+status);
            String type=connection.getContentType();
            if(type!=null&&(type.contains("html")||type.contains("json")||type.contains("xml")))throw new IOException("Camera returned a document instead of video");
            input=connection.getInputStream();long content=connection.getContentLengthLong();
            if(status==200&&spec.position>0) {
                long skip=spec.position;byte[] buffer=new byte[32768];
                while(skip>0){int n=input.read(buffer,0,(int)Math.min(skip,buffer.length));if(n<0)throw new EOFException("Seek past recording end");skip-=n;}
                if(content>=0)content=Math.max(0,content-spec.position);
            }
            remaining=spec.length!=-1?spec.length:content;
            started=true;transferStarted(spec);return remaining;
        }catch(Exception e){close();throw e instanceof IOException?(IOException)e:new IOException(e);}
    }
    @Override public int read(byte[] bytes,int offset,int length)throws IOException {
        if(length==0)return 0;if(remaining==0)return -1;
        if(input==null)throw new IOException("Media source not open");
        int n=input.read(bytes,offset,remaining<0?length:(int)Math.min(length,remaining));
        if(n<0){if(remaining>0)throw new EOFException("Camera recording transfer ended early");return -1;}
        if(remaining>0)remaining-=n;bytesTransferred(n);return n;
    }
    @Override public Uri getUri(){return uri;}
    @Override public Map<String,List<String>> getResponseHeaders(){return connection==null?Collections.emptyMap():connection.getHeaderFields();}
    @Override public void close()throws IOException {
        try{if(input!=null)input.close();}finally{input=null;if(connection!=null)connection.disconnect();connection=null;uri=null;if(started){started=false;transferEnded();}}
    }
}
