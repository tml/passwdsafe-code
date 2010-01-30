/*
 * Copyright (©) 2009-2010 Jeff Harris <jefftharris@gmail.com>
 * All rights reserved. Use of the code is allowed under the
 * Artistic License 2.0 terms, as specified in the LICENSE file
 * distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
package com.jefftharris.passwdsafe;

import java.util.Map;
import java.util.WeakHashMap;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

public class PasswdSafeApp extends Application
    implements SharedPreferences.OnSharedPreferenceChangeListener
{
    public class AppActivityPasswdFile extends ActivityPasswdFile
    {
        public AppActivityPasswdFile(PasswdFileData fileData, Activity activity)
        {
            super(fileData, activity);
        }

        @Override
        protected void doSetFileData(PasswdFileData fileData)
        {
            PasswdSafeApp.this.setFileData(fileData, itsActivity);
        }

        @Override
        public void touch()
        {
            touchFileData(itsActivity);
        }

        @Override
        protected void doClose()
        {
            PasswdSafeApp.this.setFileData(null, itsActivity);
        }
    }

    public static class FileTimeoutReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "File timeout");
            PasswdSafeApp app = (PasswdSafeApp)context.getApplicationContext();
            app.closeFileData();
        }

    }

    public static final boolean DEBUG = false;

    public static final String VIEW_INTENT =
        "com.jefftharris.passwdsafe.action.VIEW";
    public static final String FILE_TIMEOUT_INTENT =
        "com.jefftharris.passwdsafe.action.FILE_TIMEOUT";

    public static final String PREF_FILE_DIR = "fileDirPref";
    public static final String PREF_FILE_DIR_DEF =
        Environment.getExternalStorageDirectory().toString();

    public static final String PREF_FILE_CLOSE_TIMEOUT = "fileCloseTimeoutPref";
    public static final String PREF_FILE_CLOSE_TIMEOUT_DEF = "300";
    public static final String[] PREF_FILE_CLOSE_ENTRIES =
    {
        "None", "30 seconds", "5 minutes", "15 minutes", "1 hour"
    };
    public static final String[] PREF_FILE_CLOSE_ENTRY_VALUES =
    {
        "", "30", "300", "900", "3600"
    };

    private PasswdFileData itsFileData = null;
    private WeakHashMap<Activity, Object> itsFileDataActivities =
        new WeakHashMap<Activity, Object>();
    private AlarmManager itsAlarmMgr;
    private PendingIntent itsCloseIntent;
    private int itsFileCloseTimeout = 300*1000;

    private static final Intent FILE_TIMEOUT_INTENT_OBJ =
        new Intent(FILE_TIMEOUT_INTENT);
    private static final String TAG = "PasswdSafeApp";

    public PasswdSafeApp()
    {
    }

    /* (non-Javadoc)
     * @see android.app.Application#onCreate()
     */
    @Override
    public void onCreate()
    {
        super.onCreate();
        itsAlarmMgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);

        SharedPreferences prefs =
            PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        // Move the fileDirPref from the FileList class to the preferences
        String dirPrefName = "dir";
        SharedPreferences fileListPrefs = getSharedPreferences("FileList",
                                                               MODE_PRIVATE);
        if ((fileListPrefs != null) && fileListPrefs.contains(dirPrefName)) {
            String dirPref = fileListPrefs.getString(dirPrefName, "");
            dbginfo(TAG, "Moving dir pref \"" + dirPref + "\" to main");

            SharedPreferences.Editor fileListEdit = fileListPrefs.edit();
            SharedPreferences.Editor prefsEdit = prefs.edit();
            fileListEdit.remove(dirPrefName);
            prefsEdit.putString(PREF_FILE_DIR, dirPref);
            fileListEdit.commit();
            prefsEdit.commit();
        }

        updateFileCloseTimeoutPref(prefs);
    }

    /* (non-Javadoc)
     * @see android.app.Application#onTerminate()
     */
    @Override
    public void onTerminate()
    {
        closeFileData();
        super.onTerminate();
    }

    /* (non-Javadoc)
     * @see android.content.SharedPreferences.OnSharedPreferenceChangeListener#onSharedPreferenceChanged(android.content.SharedPreferences, java.lang.String)
     */
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key)
    {
        dbginfo(TAG, "Preference change: " + key);
        if (key.equals(PREF_FILE_CLOSE_TIMEOUT)) {
            updateFileCloseTimeoutPref(sharedPreferences);
        }
    }

    public synchronized ActivityPasswdFile accessPasswdFile(String fileName,
                                                            Activity activity)
    {
        if ((itsFileData == null) || (itsFileData.itsFileName == null) ||
            (!itsFileData.itsFileName.equals(fileName))) {
            closeFileData();
        }

        dbginfo(TAG, "access file name:" + fileName + ", data:" + itsFileData);
        return new AppActivityPasswdFile(itsFileData, activity);
    }

    public static String getFileCloseTimeoutPref(SharedPreferences prefs)
    {
        return prefs.getString(PREF_FILE_CLOSE_TIMEOUT,
                               PREF_FILE_CLOSE_TIMEOUT_DEF);
    }

    public static void showFatalMsg(String msg, final Activity activity)
    {
        new AlertDialog.Builder(activity)
        .setMessage(msg)
        .setCancelable(false)
        .setPositiveButton("Close", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                activity.finish();
            }
        })
        .show();
    }

    public static void dbginfo(String tag, String msg)
    {
        if (DEBUG)
            Log.i(tag, msg);
    }

    private synchronized final
    void updateFileCloseTimeoutPref(SharedPreferences prefs)
    {
        String timeoutStr = getFileCloseTimeoutPref(prefs);
        dbginfo(TAG, "new file close timeout: " + timeoutStr);
        if (timeoutStr.length() == 0) {
            cancelFileDataTimer();
            itsFileCloseTimeout = 0;
        } else {
            try {
                itsFileCloseTimeout = Integer.parseInt(timeoutStr) * 1000;
                touchFileDataTimer();
            } catch (NumberFormatException e) {
            }
        }
    }

    private synchronized final void cancelFileDataTimer()
    {
        if (itsCloseIntent != null) {
            itsAlarmMgr.cancel(itsCloseIntent);
            itsCloseIntent = null;
        }
    }

    private synchronized final void touchFileDataTimer()
    {
        dbginfo(TAG, "touch timer timeout: " + itsFileCloseTimeout);
        if ((itsFileData != null) && (itsFileCloseTimeout != 0)) {
            if (itsCloseIntent == null) {
                itsCloseIntent =
                    PendingIntent.getBroadcast(this, 0,
                                               FILE_TIMEOUT_INTENT_OBJ, 0);
            }
            dbginfo(TAG, "register adding timer");
            itsAlarmMgr.set(AlarmManager.ELAPSED_REALTIME,
                            SystemClock.elapsedRealtime() + itsFileCloseTimeout,
                            itsCloseIntent);
        }
    }

    private synchronized final void touchFileData(Activity activity)
    {
        dbginfo(TAG, "touch activity:" + activity + ", data:" + itsFileData);
        if (itsFileData != null) {
            itsFileDataActivities.put(activity, null);
            touchFileDataTimer();
        }
    }

    private synchronized final void setFileData(PasswdFileData fileData,
                                                Activity activity)
    {
        closeFileData();
        itsFileData = fileData;
        touchFileData(activity);
    }

    private synchronized final void closeFileData()
    {
        dbginfo(TAG, "closeFileData data:" + itsFileData);
        if (itsFileData != null) {
            itsFileData.close();
            itsFileData = null;
        }

        for (Map.Entry<Activity, Object> entry :
            itsFileDataActivities.entrySet()) {
            dbginfo(TAG, "closeFileData activity:" + entry.getKey());
            entry.getKey().finish();
        }
        itsFileDataActivities.clear();
        cancelFileDataTimer();
    }
}