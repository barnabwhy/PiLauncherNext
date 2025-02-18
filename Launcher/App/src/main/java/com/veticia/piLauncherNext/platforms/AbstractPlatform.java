package com.veticia.piLauncherNext.platforms;

import static com.veticia.piLauncherNext.MainActivity.DEFAULT_STORE_ICON_PRIORITY;
import static com.veticia.piLauncherNext.MainActivity.DEFAULT_STYLE;
import static com.veticia.piLauncherNext.MainActivity.STYLES;
import static com.veticia.piLauncherNext.MainActivity.sharedPreferences;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.ImageView;

import androidx.core.content.res.ResourcesCompat;

import com.veticia.piLauncherNext.MainActivity;
import com.veticia.piLauncherNext.SettingsProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;

public abstract class AbstractPlatform {
    final int style = sharedPreferences.getInt(SettingsProvider.KEY_CUSTOM_STYLE, DEFAULT_STYLE);
    private final String ICONS1_URL = "https://raw.githubusercontent.com/Veticia/binaries/main/"+STYLES[style]+"/";
    private static final String ICONS_FALLBACK_URL = "https://pilauncher.lwiczka.pl/get_icon.php?id=";
    protected static final HashMap<String, Drawable> iconCache = new HashMap<>();
    protected static final HashSet<String> ignoredIcons = new HashSet<>();

    private void downloadIcon(final Activity activity, String pkg, @SuppressWarnings("unused") String name, final Runnable callback) {
        final File file = pkg2path(activity, STYLES[style] + "." + pkg);
        new Thread(() -> {
            try {
                synchronized (pkg) {
                    //if (ignoredIcons.contains(STYLES[style] + "." + file.getName())) {
                        //ignored icon
                    //} else
                    if(sharedPreferences.getBoolean(SettingsProvider.KEY_STORE_ICON_PRIORITY, DEFAULT_STORE_ICON_PRIORITY) && downloadIconFromStore(pkg, file, style)) {
                        activity.runOnUiThread(callback);
                    } else if (downloadIconFromUrl(ICONS1_URL + pkg + ".png", file)) {
                        activity.runOnUiThread(callback);
                    } else if (downloadIconFromUrl(ICONS_FALLBACK_URL + pkg + "&set=" + STYLES[style], file)) {
                        activity.runOnUiThread(callback);
                    } else if(downloadIconFromStore(pkg, file, style)) {
                        activity.runOnUiThread(callback);
                    } else {
                        Log.d("Missing icon", file.getName());
                        ignoredIcons.add(STYLES[style] + "." + file.getName());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    public void loadIcon(Activity activity, ImageView icon, ApplicationInfo app, String name) {
        String pkg = app.packageName;

        if (iconCache.containsKey(STYLES[style]+"."+pkg)) {
            icon.setImageDrawable(iconCache.get(STYLES[style]+"."+pkg));
            return;
        }else{
            PackageManager pm = activity.getPackageManager();
            Resources resources;
            try {
                resources = pm.getResourcesForApplication(app.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
                return;
            }
            int iconId = app.icon;
            if (iconId == 0) {
                iconId = android.R.drawable.sym_def_app_icon;
            }
            Drawable appIcon = ResourcesCompat.getDrawableForDensity(resources, iconId, DisplayMetrics.DENSITY_XXXHIGH, null);
            icon.setImageDrawable(appIcon);
        }

        final File file = pkg2path(activity, STYLES[style]+"."+pkg);
        if (file.exists()) {
            Log.i("Exists", file.getAbsolutePath());
            if (updateIcon(icon, file, STYLES[style]+"."+pkg)) {
                return;
            }
        }
        downloadIcon(activity, pkg, name, () -> updateIcon(icon, file, STYLES[style]+"."+pkg));
    }
    public abstract boolean runApp(Context context, ApplicationInfo app, boolean multiwindow);
    public static boolean isImageFileComplete(File imageFile) {
        boolean success = false;
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            success = (bitmap != null);
        } catch (Exception e) {
            // do nothing
        }
        if (!success) {
            Log.e("imgComplete", "Failed to read image file: " + imageFile);
        }
        return success;
    }

    public static void clearIconCache() {
        ignoredIcons.clear();
        iconCache.clear();
    }

    public static void clearAllIcons(MainActivity activity) {
        for (String pkg : iconCache.keySet()) {
            final File file = pkg2path(activity, pkg);
            Log.i("Cache file", file.getAbsolutePath() + " | Exists: " + file.exists());
            if (file.exists()) {
                file.delete();
            }
        }
        clearIconCache();
    }

    public static AbstractPlatform getPlatform(ApplicationInfo app) {
        if (app.packageName.startsWith(PSPPlatform.PACKAGE_PREFIX)) {
            return new PSPPlatform();
        } else if (isVirtualRealityApp(app)) {
            return new VRPlatform();
        } else {
            return new AndroidPlatform();
        }
    }

    public static File pkg2path(Context context, String pkg) {
        return new File(context.getCacheDir(), pkg + ".webp");
    }

    public static boolean updateIcon(ImageView icon, File file, String pkg) {
        try {
            Drawable drawable = Drawable.createFromPath(file.getAbsolutePath());
            if (drawable != null) {
                icon.setImageDrawable(drawable);
                iconCache.put(pkg, drawable);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    protected static boolean downloadIconFromUrl(String url, File outputFile) {
        try {
            return saveStream(new URL(url).openStream(), outputFile);
        } catch (Exception e) {
            return false;
        }
    }

    protected static boolean saveStream(InputStream is, File outputFile) {
        try {
            DataInputStream dis = new DataInputStream(is);

            int length;
            byte[] buffer = new byte[65536];
            FileOutputStream fos = new FileOutputStream(outputFile);
            while ((length = dis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.flush();
            fos.close();

            if (!isImageFileComplete(outputFile)) {
                return false;
            }

            Bitmap bitmap = BitmapFactory.decodeFile(outputFile.getAbsolutePath());
            if (bitmap != null) {
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                float aspectRatio = (float) width / height;
                if (width > 512) {
                    width = 512;
                    height = Math.round(width / aspectRatio);
                    bitmap = Bitmap.createScaledBitmap(bitmap, width, height, false);
                }
                try {
                    fos = new FileOutputStream(outputFile);
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 75, fos);
                    fos.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    protected static boolean downloadIconFromStore(String pkg, File outputFile, int style) {
        if(isPicoHeadset()) {
            try {
                URL url = new URL("https://appstore-us.picovr.com/api/app/v1/item/info?app_language=en&device_name=A8110&manifest_version_code=300800000&package_name=" + pkg);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.connect();

                InputStream stream = connection.getInputStream();

                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

                StringBuilder str = new StringBuilder();
                String line = "";

                while ((line = reader.readLine()) != null) {
                    str.append(line).append("\n");
                }

                JSONObject result = new JSONObject(str.toString());

                if(result.isNull("data") || result.getJSONObject("data").isNull("cover")) {
                    return false;
                }

                String imageKey = style == 0 ? "landscape" : "square";
                return downloadIconFromUrl(result.getJSONObject("data").getJSONObject("cover").getString(imageKey), outputFile);
            } catch (Exception e) {
                return false;
            }
        } else if(isOculusHeadset()) {
            return false;
        } else {
            return false;
        }
    }

    public static boolean isMagicLeapHeadset() {
        String vendor = Build.MANUFACTURER.toUpperCase();
        return vendor.startsWith("MAGIC LEAP");
    }

    public static boolean isOculusHeadset() {
        String vendor = Build.MANUFACTURER.toUpperCase();
        return vendor.startsWith("META") || vendor.startsWith("OCULUS");
    }

    public static boolean isPicoHeadset() {
        String vendor = Build.MANUFACTURER.toUpperCase();
        return vendor.startsWith("PICO") || vendor.startsWith("PİCO");
    }

    protected static boolean isVirtualRealityApp(ApplicationInfo app) {
        String[] nonVrApps = {      //move to tools category
                "com.pico4.settings",   //app that shows android settings
                "com.pico.browser",     //in-build pico web browser
                "com.ss.android.ttvr",  //pico video
                "com.pvr.mrc"           //pico's mixed reality capture
        };
        for (String nonVrApp : nonVrApps) {
            if (app.packageName.startsWith(nonVrApp)) {
                return false;
            }
        }
        if (app.metaData != null) {
            for (String key : app.metaData.keySet()) {
                if (key.startsWith("notch.config")) {
                    return true;
                }
                if (key.startsWith("com.oculus")) {
                    return true;
                }
                if (key.startsWith("pvr.")) {
                    return true;
                }
                if (key.contains("vr.application.mode")) {
                    return true;
                }
            }
        }
        return false;
    }
}
