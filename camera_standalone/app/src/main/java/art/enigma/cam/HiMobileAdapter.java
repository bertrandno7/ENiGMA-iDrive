package art.enigma.cam;

import android.net.Network;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Supports the assignment-text and JSON variants of the camera's read API. */
final class HiMobileAdapter {
    private static final String[] BASES={"/cgi-bin/hisnet/","/cgi-bin/hi3510/","/cgi-bin/"};
    private static final Pattern LINK=Pattern.compile("(?i)href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    static boolean fingerprint(Network net,String host,StringBuilder log) {
        boolean confirmed=false;
        for(String base:BASES) {
            String body=fetch(net,host,base+"getdeviceattr.cgi",log);
            Map<String,String> vars=CamText.variables(body);
            boolean match=CamText.deviceSignature(vars);
            if(!match && body.trim().startsWith("{")) {
                try {
                    JSONObject j=new JSONObject(body);JSONObject result=j.optJSONObject("Result");
                    match=j.optInt("ResultCode",-1)==0 && result!=null &&
                        (result.has("softversion")||result.has("model")||result.has("name"));
                } catch(Exception ignored){}
            }
            if(match) {
                confirmed=true; log.append("Detected HiSilicon/HiMobileCam ").append(vars.isEmpty()?"JSON":"assignment-text").append(" API at ").append(base).append('\n');
                fetch(net,host,base+"getsdstatus.cgi",log);
                fetch(net,host,base+"getfilecount.cgi",log);
                fetch(net,host,base+"getworkmode.cgi",log);
                break;
            }
        }
        log.append("\nSD directory inspection (bounded; media files are not downloaded):\n");
        try {walk(net,host,log,8,7000);} catch(Exception e) {log.append("SD: ").append(CameraProtocol.shortError(e)).append('\n');}
        return confirmed;
    }
    private static String fetch(Network net,String host,String path,StringBuilder log) {
        try {
            String body=CameraProtocol.getText(net,"http://"+host+path,110000);
            log.append("GET ").append(path).append(" — HTTP 200\n");
            Map<String,String> vars=CamText.variables(body);
            if(vars.containsKey("svrfuncresult")) log.append("Camera result code: ").append(vars.get("svrfuncresult")).append(" (not JSON)\n");
            else if(!vars.isEmpty()) log.append("Parsed ").append(vars.size()).append(" text assignments\n");
            log.append(CamText.excerpt(body)).append("\n\n");return body;
        } catch(Exception e) {log.append("GET ").append(path).append(": ").append(CameraProtocol.shortError(e)).append('\n');return "";}
    }
    static void dirSummary(Network net,String host,StringBuilder log) {
        try {walk(net,host,log,8,7000);}catch(Exception e){log.append(CameraProtocol.shortError(e)).append('\n');}
    }
    static JSONArray list(Network net,String host) throws Exception {
        // Directory access works independently of the exact firmware's CGI parameters.
        JSONArray files=listDirectory(net,host);
        if(files.length()>0)return files;
        // Only query the JSON file-list variant when a successful JSON device reply identifies it.
        for(String base:BASES) {
            try {
                String body=CameraProtocol.getText(net,"http://"+host+base+"getdeviceattr.cgi",110000);
                if(!body.trim().startsWith("{"))continue;
                JSONObject device=new JSONObject(body);
                if(device.optInt("ResultCode",-1)!=0 || device.optJSONObject("Result")==null)continue;
                JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
                for(int type:new int[]{0,1,3,6,8,2,4,5,7,9}) {
                    String endpoint="getfilelist.cgi?&-start=1&-end=40&-type="+type;
                    JSONObject result=new JSONObject(CameraProtocol.getText(net,"http://"+host+base+endpoint,500000));
                    if(result.optInt("ResultCode",-1)!=0)continue;
                    JSONArray group=result.optJSONArray("Result");if(group==null)continue;
                    for(int i=0;i<group.length()&&out.length()<12000;i++) {
                        JSONObject item=group.optJSONObject(i);if(item==null)continue;
                        String path=item.optString("path","");String name=item.optString("name","");
                        String safe=CamText.sdLink(host,"sd/",path+(path.endsWith("/")?"":"/")+name);
                        if(!safe.isEmpty()&&CamText.media(safe)&&seen.add(safe))out.put(file(safe,item.optLong("size",0)));
                    }
                }
                if(out.length()>0)return out;
            }catch(Exception ignored){}
        }
        throw new IOException("No clips exposed by the read-only SD listing. Run diagnostics and share the folder and API replies.");
    }
    static JSONArray listDirectory(Network net,String host) throws Exception {
        JSONArray all=walk(net,host,null,64,30000);
        java.util.ArrayList<JSONObject> sorted=new java.util.ArrayList<>();
        for(int i=0;i<all.length();i++)if(all.optJSONObject(i)!=null)sorted.add(all.optJSONObject(i));
        java.util.Collections.sort(sorted,(a,b)->b.optString("name").compareToIgnoreCase(a.optString("name")));
        JSONArray out=new JSONArray();for(JSONObject file:sorted)out.put(file);return out;
    }
    private static JSONArray walk(Network net,String host,StringBuilder log,int maxPages,int budgetMs) throws Exception {
        long deadline=System.nanoTime()+budgetMs*1000000L;
        ArrayDeque<String> queue=new ArrayDeque<>();queue.add("sd/");
        Set<String> queued=new HashSet<>();queued.add("sd/");Set<String> seen=new HashSet<>();
        JSONArray out=new JSONArray();int pages=0;
        while(!queue.isEmpty()&&pages<maxPages&&out.length()<12000&&System.nanoTime()<deadline) {
            String dir=queue.removeFirst();pages++;
            try {
                String body=CameraProtocol.getText(net,"http://"+host+"/"+dir,4000000);
                if(log!=null)log.append('/').append(dir).append(" — HTTP 200; ");
                Matcher m=LINK.matcher(body);int links=0,children=0,media=0,shown=0;
                StringBuilder names=new StringBuilder();
                while(m.find()&&++links<=50000&&out.length()<12000) {
                    String href=m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3);
                    String safe=CamText.sdLink(host,dir,href);
                    if(safe.isEmpty())continue;
                    if(shown++<10)names.append("  ").append(CamText.excerpt(href)).append('\n');
                    if(safe.endsWith("/")) {
                        if(safe.split("/").length<=7&&queued.size()<96&&queued.add(safe)){queue.add(safe);children++;}
                    } else if(CamText.media(safe)&&seen.add(safe)) {out.put(file(safe,0));media++;}
                }
                if(log!=null)log.append(links).append(" links; ").append(children).append(" new folders; ").append(media).append(" media files\n").append(names);
            } catch(Exception e) {if(log!=null)log.append('/').append(dir).append(": ").append(CameraProtocol.shortError(e)).append('\n');}
        }
        if(log!=null)log.append("SD summary: ").append(pages).append(" pages inspected; ").append(out.length()).append(" media links; ").append(queue.size()).append(" folders left within scan limits.\n\n");
        return out;
    }
    private static JSONObject file(String path,long bytes) throws Exception {
        String upper=path.toUpperCase(Locale.ROOT);
        return new JSONObject().put("name",path.substring(path.lastIndexOf('/')+1)).put("path",path).put("bytes",Math.max(0,bytes))
            .put("date",displayDate(path)).put("category",upper.contains("/PARKING/")?"parking":upper.contains("/PHOTO/")?"photo":upper.contains("/EVENT/")?"event":"video").put("camera",upper.matches(".*_R\\.[A-Z0-9]+$")?"Rear":"Front").put("emergency",upper.contains("EMR")||upper.contains("SOS")||upper.contains("EVENT")||upper.contains("/RO/"));
    }
    private static String displayDate(String path) {
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(20[0-9]{2})_([0-9]{2})_([0-9]{2})_([0-9]{2})([0-9]{2})([0-9]{2})").matcher(path);
        return match.find()?match.group(1)+"-"+match.group(2)+"-"+match.group(3)+" · "+match.group(4)+":"+match.group(5)+":"+match.group(6):"";
    }

}
