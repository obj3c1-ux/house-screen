package au.house.screen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** opens the house screen by itself after the tablet restarts */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent go = new Intent(c, MainActivity.class);
        go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { c.startActivity(go); } catch (Exception ignored) { }
    }
}
