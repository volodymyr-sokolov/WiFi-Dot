package android.service.quicksettings;
import android.app.Service; import android.content.*; import android.os.IBinder; import android.app.PendingIntent;
/** API 24 stub, compile-only (not included in the APK). */
public class TileService extends Service {
    public IBinder onBind(Intent i) { return null; }
    public void onTileAdded() {} public void onTileRemoved() {}
    public void onStartListening() {} public void onStopListening() {} public void onClick() {}
    public final Tile getQsTile() { return null; }
    public final boolean isLocked() { return false; }
    public final void unlockAndRun(Runnable r) {}
    public final void startActivityAndCollapse(Intent i) {}
    public final void startActivityAndCollapse(PendingIntent pi) {}
    public static final void requestListeningState(Context c, ComponentName n) {}
}
