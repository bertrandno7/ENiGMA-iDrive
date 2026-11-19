package art.enigma.cam;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses data assignments without evaluating any camera-supplied script. */
final class CamText {
    private static final Pattern ASSIGN = Pattern.compile(
        "(?:^|[;\\r\\n])\\s*(?:var\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|(-?\\d+(?:\\.\\d+)?|true|false))\\s*(?=;|$|[\\r\\n])");
    static Map<String,String> variables(String body) {
        Map<String,String> out = new LinkedHashMap<>();
        if (body == null || body.length() > 250000 || body.trim().startsWith("<")) return out;
        Matcher m = ASSIGN.matcher(body);
        while (m.find() && out.size() < 120) {
            String value = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            out.put(m.group(1).toLowerCase(Locale.ROOT), unescape(value));
        }
        return out;
    }
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if (c=='\\' && i+1<s.length()) {
                char n=s.charAt(++i);
                if (n=='n') out.append('\n'); else if(n=='r') out.append('\r');
                else if(n=='t') out.append('\t'); else if(n=='\\'||n=='"'||n=='\''||n=='/') out.append(n);
                else { out.append('\\'); out.append(n); }
            } else out.append(c);
        }
        return out.toString();
    }
    static boolean deviceSignature(Map<String,String> vars) {
        return vars.size() >= 2 && (vars.containsKey("softversion") || vars.containsKey("hardversion") || vars.containsKey("netctrlmodel"))
            && (vars.containsKey("model") || vars.containsKey("name") || vars.containsKey("type"));
    }
    static String excerpt(String body) {
        if (body == null) return "";
        String s = body.replaceAll("(?i)((?:[A-Za-z0-9_]*(?:pass|pwd|token|secret|serial|ssid|wifikey|mac)[A-Za-z0-9_]*)\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^;\\r\\n ,}]+)", "$1[redacted]")
            .replaceAll("(?i)rtsp://[^/@\\s]+@", "rtsp://[redacted]@")
            .replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "?")
            .replace("\r", "").trim();
        return s.length()>1400 ? s.substring(0,1400)+" … [truncated]" : s;
    }
    /** Resolve only links staying inside this camera's SD media tree. */
    static String sdLink(String host, String directory, String href) {
        try {
            if (href == null || href.isEmpty() || href.length()>700 || href.startsWith("//") || href.contains("\\") || href.matches(".*[\\x00-\\x20].*")) return "";
            URI base = new URI("http://"+host+"/"+directory);
            URI link = base.resolve(href.replace("&amp;", "&"));
            if (!"http".equalsIgnoreCase(link.getScheme()) || !host.equals(link.getHost()) || (link.getPort()!=-1 && link.getPort()!=80)
                || link.getRawUserInfo()!=null || link.getRawQuery()!=null || link.getRawFragment()!=null) return "";
            String path=link.getPath();
            if (path==null || path.contains("%") || path.contains("\\") || path.contains(":") || path.contains("?") || path.contains("#") || path.matches(".*[\\x00-\\x1f\\x7f].*")) return "";
            for(String p:path.split("/")) if(p.equals("..")) return "";
            path=new URI(null,null,path,null).normalize().getPath();
            while(path.startsWith("/")) path=path.substring(1);
            if (!(path.startsWith("sd/") || path.startsWith("mnt/sd/")) || path.length()>320) return "";
            return path;
        } catch(Exception e) { return ""; }
    }
    static boolean media(String path) { return path.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mov|avi|ts|jpg|jpeg)$"); }
}
