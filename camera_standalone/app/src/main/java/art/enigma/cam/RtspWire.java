package art.enigma.cam;

import javax.net.SocketFactory;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class RtspWire {
    static final class Reply {
        final int code; final String header, body, error;
        Reply(int c,String h,String b,String e) { code=c; header=h; body=b; error=e; }
        boolean hasVideoSdp() {
            String s=body.replace("\r\n","\n").replace('\r','\n').replace("\uFEFF","");
            return code==200 &&
                java.util.regex.Pattern.compile("(?m)^\\s*v\\s*=\\s*0\\s*$").matcher(s).find() &&
                java.util.regex.Pattern.compile("(?mi)^\\s*m\\s*=\\s*video\\s+\\d+\\s+").matcher(s).find();
        }
        String summary() {
            if(!error.isEmpty()) return error;
            String first=header.split("\\r?\\n",2)[0];
            return first+"; body="+body.length()+" bytes"+(hasVideoSdp()?"; video SDP":"; NOT validated video SDP")+(code==401?"; authentication required":"");
        }
    }
    static boolean portOpen(SocketFactory factory,String host,int port) {
        try(Socket s=factory.createSocket()) {s.connect(new InetSocketAddress(host,port),450);return true;}
        catch(Exception e) {return false;}
    }
    static Reply request(SocketFactory factory,String host,int port,String path,String method,int cseq,boolean relative) {
        try(Socket socket=factory.createSocket()) {
            socket.connect(new InetSocketAddress(host,port),650); socket.setSoTimeout(1100);
            String target=path.equals("*")?"*":relative?path:"rtsp://"+host+":"+port+path;
            String req=method+" "+target+" RTSP/1.0\r\nCSeq: "+cseq+"\r\nUser-Agent: ENiGMA-CAM/0.5\r\n"+
                (method.equals("DESCRIBE")?"Accept: application/sdp\r\n":"")+"\r\n";
            socket.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            return read(socket.getInputStream());
        } catch(Exception e) {return new Reply(0,"","",e.getClass().getSimpleName());}
    }
    static Reply read(InputStream raw) throws IOException {
        BufferedInputStream in=new BufferedInputStream(raw);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        String header="";
        while(out.size()<16384) {
            int b=in.read();if(b<0)break;out.write(b);
            byte[] a=out.toByteArray();int n=a.length;
            if(n>=4 && a[n-4]==13&&a[n-3]==10&&a[n-2]==13&&a[n-1]==10) {header=new String(a,StandardCharsets.US_ASCII);break;}
        }
        if(header.isEmpty())throw new IOException("Incomplete RTSP headers");
        String[] lines=header.split("\\r\\n"); int code=0,len=-1;
        if(!lines[0].startsWith("RTSP/1.0 "))throw new IOException("Not an RTSP response");
        try {code=Integer.parseInt(lines[0].split(" ")[1]);}catch(Exception e){throw new IOException("Invalid RTSP status");}
        for(String line:lines) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
            try {len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());}catch(Exception e){throw new IOException("Invalid RTSP length");}
        }
        if(len>65536)throw new IOException("RTSP body exceeds limit");
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        if (len >= 0) {
            int pos = 0;
            while (pos < len) {
                int n = in.read(buffer, 0, Math.min(buffer.length, len - pos));
                if (n < 0) throw new EOFException("Truncated RTSP body");
                bodyBytes.write(buffer, 0, n); pos += n;
            }
        } else if (code == 200) {
            // Some embedded RTSP servers omit Content-Length in DESCRIBE replies.
            // Socket timeout bounds the read if the server keeps its TCP connection open.
            try {
                int n;
                while (bodyBytes.size() < 65536 && (n = in.read(buffer, 0, Math.min(buffer.length, 65536 - bodyBytes.size()))) >= 0) {
                    bodyBytes.write(buffer, 0, n);
                }
            } catch (java.net.SocketTimeoutException ignored) { }
        }
        return new Reply(code,header,bodyBytes.toString(StandardCharsets.UTF_8.name()),"");
    }
}
