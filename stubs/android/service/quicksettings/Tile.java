package android.service.quicksettings;
import android.graphics.drawable.Icon;
public final class Tile {
    public static final int STATE_UNAVAILABLE = 0, STATE_INACTIVE = 1, STATE_ACTIVE = 2;
    public void setIcon(Icon i) {} public void setLabel(CharSequence s) {}
    public void setContentDescription(CharSequence s) {} public void setState(int s) {}
    public void setSubtitle(CharSequence s) {} public void updateTile() {}
}
