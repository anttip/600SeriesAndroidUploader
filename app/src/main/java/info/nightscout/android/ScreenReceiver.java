package info.nightscout.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

//Ref: https://thinkandroid.wordpress.com/2010/01/24/handling-screen-off-and-screen-on-intents/
public class ScreenReceiver extends BroadcastReceiver {
 
    private boolean screenOn;
 
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
            screenOn = false;
        } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
            screenOn = true;
        }
        Intent i = new Intent(context, LockScreenNotification.class);
        i.putExtra("screenOn", screenOn);
        context.startService(i);
    }
 
}