package info.nightscout.android;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.app.TaskStackBuilder;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.widget.RemoteViews;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import info.nightscout.android.medtronic.MainActivity;
import info.nightscout.android.model.medtronicNg.PumpStatusEvent;
import info.nightscout.android.utils.ConfigurationStore;
import info.nightscout.android.utils.DataStore;
import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;


// Updates most important values to the lock screen through a constant notification
// updates once per minute if screen is on or always if there's new info from pump
public class LockScreenNotification extends IntentService {

    private DataStore dataStore = DataStore.getInstance();
    private ConfigurationStore configurationStore = ConfigurationStore.getInstance();

    //helper for testing layouts during dev
    private boolean fakeValues = false;

    public LockScreenNotification() {
        super("Notifications");
        Log.i("Notifications", "Running Notifications Intent Service");
    }

    //Ref: https://thinkandroid.wordpress.com/2010/01/24/handling-screen-off-and-screen-on-intents/
    @Override
    public void onCreate() {
        super.onCreate();
        // REGISTER RECEIVER THAT HANDLES SCREEN ON AND SCREEN OFF LOGIC
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        BroadcastReceiver mReceiver = new ScreenReceiver();
        registerReceiver(mReceiver, filter);
    }

    //https://thinkandroid.wordpress.com/2010/01/24/handling-screen-off-and-screen-on-intents/
    @Override
    public void onStart(Intent intent, int startId) {
        if (intent.hasExtra("screenOn")) {
            dataStore.setScreenOn(intent.getBooleanExtra("screenOn", false));
        }
        Log.d("Notifications", "ScreenOn=" + dataStore.isScreenOn());
        onHandleIntent(intent);
    }


    public void updateNotification() {
        final int NOTIFICATION_ID = 1;
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        Realm mRealm = Realm.getDefaultInstance();
        // most recent sgv status
        RealmResults<PumpStatusEvent> sgv_results =
                mRealm.where(PumpStatusEvent.class)
                        .equalTo("validSGV", true)
                        .findAllSorted("cgmDate", Sort.ASCENDING);

        PumpStatusEvent lastEvent = null;
        if (sgv_results.size() > 0) {
            lastEvent = sgv_results.last();
        }
        long timeLastGoodSGV = lastEvent.getCgmDate().getTime();
        if (lastEvent.getSgv() == 0) {
            timeLastGoodSGV = 0;
        }

        long age = System.currentTimeMillis() - timeLastGoodSGV;
        if (fakeValues) {
            age = (int) (Math.random() * 20 * 1000 * 60);
            timeLastGoodSGV = 1;
        }

        boolean valid = timeLastGoodSGV > 0 && (age < TimeUnit.MINUTES.toMillis(15));

        RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.custom_notification);
        final int COLOR_WARN = getResources().getColor(R.color.md_deep_orange_800);
        final int COLOR_INVALID = getResources().getColor(R.color.md_grey_500);
        final int COLOR_OK = getResources().getColor(R.color.md_green_800);


        //default values
        String svgStr = "  --  ";
        String iobStr = " -- ";
        String timeStr = ">15";
        String trendStr = "   ";

        if (!valid) {
            //update colors
            contentView.setTextColor(R.id.blood, COLOR_INVALID);
            contentView.setTextColor(R.id.bloodtrend, COLOR_INVALID);
            contentView.setTextColor(R.id.time, COLOR_WARN);
            contentView.setTextColor(R.id.iob, COLOR_INVALID);
        } else {

            int svg = lastEvent.getSgv();
            float iob = lastEvent.getActiveInsulin();

            if (fakeValues) {
                svg = (int) (Math.random() * 20 * 18);
                iob = (float) Math.floor((float) Math.random() * 40) / 10f;
            }

            //update values
            svgStr = StringUtils.leftPad(MainActivity.strFormatSGV(svg), 5);
            trendStr = renderTrendSymbol(lastEvent.getCgmTrend());
            iobStr = StringUtils.leftPad(String.format(Locale.getDefault(), "%.2f", iob) + "U", 5);
            timeStr = StringUtils.leftPad("" + Math.round(TimeUnit.MILLISECONDS.toMinutes(age)), 2);

            //update colors
            contentView.setTextColor(R.id.time, COLOR_OK);
            contentView.setTextColor(R.id.iob, COLOR_OK);
            contentView.setTextColor(R.id.blood, COLOR_OK);
            contentView.setTextColor(R.id.bloodtrend, COLOR_OK);

            if (svg > 216 || svg < 76) {
                //high sugar (>12mmolx)
                //low sugar (<4.2mmolx)
                contentView.setTextColor(R.id.blood, COLOR_WARN);
                contentView.setTextColor(R.id.bloodtrend, COLOR_WARN);
            }
        }

        //set values on screen
        contentView.setTextViewText(R.id.blood, svgStr);
        contentView.setTextViewText(R.id.bloodtrend, trendStr);
        contentView.setTextViewText(R.id.time, timeStr);
        contentView.setTextViewText(R.id.iob, iobStr);

        if (configurationStore.isMmolxl()) {
            contentView.setTextViewText(R.id.bloodunit, getString(R.string.text_unit_mmolxl));
        } else {
            contentView.setTextViewText(R.id.bloodunit, getString(R.string.text_unit_mgxdl));
        }


        NotificationCompat.Builder mBuilder =
                (NotificationCompat.Builder) new NotificationCompat.Builder(this)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setSmallIcon(R.drawable.ic_launcher) // FIXME - this icon doesn't follow the standards (ie, it has black in it)
                        .setContent(contentView)
                        //custom big opens only on arrow(?)
                        //.setCustomBigContentView(contentView)
                        //.setContentTitle(title)
                        //.setContentText(message)
                        //.setColor(getResources().getColor(R.color.md_deep_orange_A100))
                        .setCategory(NotificationCompat.CATEGORY_STATUS);
        // Creates an explicit intent for an Activity in your app
        Intent resultIntent = new Intent(this, MainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(MainActivity.class);
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(
                        0,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        mBuilder.setContentIntent(resultPendingIntent);
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }



    private static String renderTrendSymbol(PumpStatusEvent.CGM_TREND trend) {
        // TODO - symbols used for trend arrow may vary per device, find a more robust solution
        switch (trend) {
            case DOUBLE_UP:
                return "\u21c8";
            case SINGLE_UP:
                return "\u2191";
            case FOURTY_FIVE_UP:
                return "\u2197";
            case FLAT:
                return "\u2192";
            case FOURTY_FIVE_DOWN:
                return "\u2198";
            case SINGLE_DOWN:
                return "\u2193";
            case DOUBLE_DOWN:
                return "\u21ca";
            default:
                return "\u2014";
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        //PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        //PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotificationsIntent");
        //wl.acquire();

        updateNotification();


        int delayMs = 60 * 1000;
        if (dataStore.isScreenOn()) {
            Log.d("Notifications", "Updated notification, next update in " + delayMs);
            scheduleNextUpdate(this, delayMs);
        } else {
            Log.d("Notifications", "Updated notification, screen is off, no updates scheduled");
        }
        //wl.release();
    }

    public static void scheduleNextUpdate(Context c, int delayMs) {
        Intent intent = new Intent(c, LockScreenNotification.class);
        PendingIntent pendingIntent =
                PendingIntent.getService(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        long currentTimeMillis = System.currentTimeMillis();
        long nextUpdateTimeMillis = currentTimeMillis + delayMs;

        AlarmManager alarmManager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC, nextUpdateTimeMillis, pendingIntent);
    }
}
