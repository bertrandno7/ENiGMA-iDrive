package art.enigma.cam;
import android.app.Activity;
import android.content.Intent;
/** Entry point for the future ENiGMA Drive camera screen. */
public final class EnigmaCameraModule {
    private EnigmaCameraModule(){}
    public static void open(Activity host){host.startActivity(new Intent(host,MainActivity.class));}
}
