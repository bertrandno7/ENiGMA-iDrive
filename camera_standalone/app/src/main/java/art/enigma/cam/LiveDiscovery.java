package art.enigma.cam;

import android.net.Network;

/** Live stream names documented by the vendor's HiSilicon adapter. */
final class LiveDiscovery {
    static final String[] PATHS={"/livestream/11","/livestream/12","/livestream/0","/livestream/1","/livestream/2","/livestream/3","/264_pcm_rt/XXX.hd","/264_pcm_rt/XXX.sd","/264_pcm_rt/XXX.fhd","/liveRTSP/av","/liveRTSP/v1"};
    static String find(Network network,String host,StringBuilder journal) {
        if(network==null||!CameraNetwork.safePrivateIpv4(host))return "";
        journal.append("\nLive video discovery v0.8\n");
        if(!RtspWire.portOpen(network.getSocketFactory(),host,554)){journal.append("RTSP 554 unavailable\n");return "";}
        long deadline=System.nanoTime()+24000000000L;
        for(String path:PATHS) {
            if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())break;
            RtspWire.Reply reply=RtspWire.request(network.getSocketFactory(),host,554,path,"DESCRIBE",1,false);
            journal.append(path).append(": ").append(reply.summary()).append('\n');
            if(reply.code==200&&!reply.hasVideoSdp())journal.append("200 but not recognised as video SDP: ")
                .append(CamText.excerpt(reply.body)).append('\n');
            if(reply.hasVideoSdp()) {
                journal.append("Video description confirmed (first rendered frame is checked separately).\n").append(CamText.excerpt(reply.body)).append('\n');
                return "rtsp://"+host+":554"+path;
            }
            if(reply.code==401){journal.append("Camera requires authentication; credentials are not guessed.\n");break;}
        }
        return "";
    }
}
