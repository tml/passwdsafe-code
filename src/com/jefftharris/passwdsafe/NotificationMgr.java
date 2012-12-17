/*
 * Copyright (©) 2012 Jeff Harris <jefftharris@gmail.com>
 * All rights reserved. Use of the code is allowed under the
 * Artistic License 2.0 terms, as specified in the LICENSE file
 * distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
package com.jefftharris.passwdsafe;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;

import org.pwsafe.lib.file.PwsRecord;

import com.jefftharris.passwdsafe.file.PasswdExpiration;
import com.jefftharris.passwdsafe.file.PasswdFileData;
import com.jefftharris.passwdsafe.file.PasswdFileDataObserver;
import com.jefftharris.passwdsafe.file.PasswdRecord;
import com.jefftharris.passwdsafe.file.PasswdRecordFilter;
import com.jefftharris.passwdsafe.view.AbstractDialogClickListener;
import com.jefftharris.passwdsafe.view.DialogUtils;
import com.jefftharris.passwdsafe.view.GuiUtils;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

/**
 * The NotificationMgr class encapsulates the notifications provided by the app
 */
public class NotificationMgr implements PasswdFileDataObserver
{
    private static final String TAG = "NotificationMgr";

    private static final String DB_TABLE_URIS = "uris";
    private static final String DB_COL_URIS_ID = BaseColumns._ID;
    private static final String DB_COL_URIS_URI = "uri";
    private static final String DB_MATCH_URIS_ID = DB_COL_URIS_ID + " = ?";
    private static final String DB_MATCH_URIS_URI = DB_COL_URIS_URI + " = ?";

    private static final String DB_TABLE_EXPIRYS = "expirations";
    private static final String DB_COL_EXPIRYS_ID = BaseColumns._ID;
    private static final String DB_COL_EXPIRYS_URI = "uri";
    private static final String DB_COL_EXPIRYS_UUID = "rec_uuid";
    private static final String DB_COL_EXPIRYS_TITLE = "rec_title";
    private static final String DB_COL_EXPIRYS_GROUP = "rec_group";
    private static final String DB_COL_EXPIRYS_USER = "rec_username";
    private static final String DB_COL_EXPIRYS_EXPIRE = "rec_expire";
    private static final String DB_MATCH_EXPIRYS_URI =
        DB_COL_EXPIRYS_URI + " = ?";

    private static final DateFormat SQL_DATE_FMT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    static {
        SQL_DATE_FMT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private final Context itsCtx;
    private final NotificationManager itsNotifyMgr;
    private final DbHelper itsDbHelper;
    private final HashMap<Long, UriNotifInfo> itsUriNotifs =
        new HashMap<Long, UriNotifInfo>();
    private int itsNextNotifId = 1;

    /** Constructor */
    public NotificationMgr(Context ctx)
    {
        itsCtx = ctx;
        itsNotifyMgr = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        itsDbHelper = new DbHelper(ctx);
        PasswdFileData.addObserver(this);

        // TODO: start app on android startup
        try {
            SQLiteDatabase db = itsDbHelper.getReadableDatabase();
            loadEntries(db);
        } catch (SQLException e) {
            Log.e(TAG, "Database error", e);
        }
    }


    /** Are notifications enabled for a URI */
    public boolean hasPasswdExpiryNotif(Uri uri)
    {
        try {
            if (uri == null) {
                return false;
            }
            SQLiteDatabase db = itsDbHelper.getReadableDatabase();
            Long uriId = getDbUriId(uri.toString(), db);
            return uriId != null;
            // TODO: cache flag in memory?
        } catch (SQLException e) {
            Log.e(TAG, "Database error", e);
            return false;
        }
    }


    /** Toggle whether notifications are enabled for a password file */
    public void togglePasswdExpiryNotif(final PasswdFileData fileData,
                                        Activity act)
    {
        try {
            if (fileData == null) {
                return;
            }

            String uristr = fileData.getUri().toString();
            SQLiteDatabase db = itsDbHelper.getWritableDatabase();
            Long uriId = getDbUriId(uristr, db);
            if (uriId != null) {
                String[] idarg = new String[] { uriId.toString() };
                db.delete(DB_TABLE_EXPIRYS, DB_MATCH_EXPIRYS_URI, idarg);
                db.delete(DB_TABLE_URIS, DB_MATCH_URIS_ID, idarg);
                loadEntries(db);
            } else {
                DialogUtils.DialogData dlgData =
                    DialogUtils.createConfirmPrompt(
                        act,
                        new AbstractDialogClickListener()
                        {
                            @Override
                            public void onOkClicked(DialogInterface dialog)
                            {
                                enablePasswdExpiryNotif(fileData);
                            }
                        },
                        act.getString(R.string.expiration_notifications),
                        act.getString(R.string.expiration_notifications_warning));
                dlgData.itsDialog.show();
                dlgData.itsValidator.validate();
            }
        } catch (SQLException e) {
            Log.e(TAG, "Database error", e);
        }
    }


    /* (non-Javadoc)
     * @see com.jefftharris.passwdsafe.file.PasswdFileDataObserver#passwdFileDataChanged(com.jefftharris.passwdsafe.file.PasswdFileData)
     */
    public void passwdFileDataChanged(PasswdFileData fileData)
    {
        // TODO: only update if necessary
        try {
            SQLiteDatabase db = itsDbHelper.getWritableDatabase();
            try {
                db.beginTransaction();
                Long id = getDbUriId(fileData.getUri().toString(), db);
                if (id != null) {
                    doUpdatePasswdFileData(id, fileData, db);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (SQLException e) {
            Log.e(TAG, "Database error", e);
        }
    }


    /** Clear all notifications in the database */
    public void clearAllNotifications(Activity act)
    {
        AbstractDialogClickListener listener =
            new AbstractDialogClickListener()
            {
                @Override
                public void onOkClicked(DialogInterface dialog)
                {
                    try {
                        SQLiteDatabase db = itsDbHelper.getWritableDatabase();
                        try {
                            db.beginTransaction();
                            db.delete(DB_TABLE_EXPIRYS, null, null);
                            db.delete(DB_TABLE_URIS, null, null);
                            loadEntries(db);
                            db.setTransactionSuccessful();
                        } finally {
                            db.endTransaction();
                        }
                    } catch (SQLException e) {
                        Log.e(TAG, "Database error", e);
                    }
                }
            };

        DialogUtils.DialogData dlgData = DialogUtils.createConfirmPrompt(
            act, listener,
            act.getString(R.string.clear_password_notifications),
            act.getString(R.string.erase_all_expiration_notifications));
        dlgData.itsDialog.show();
        dlgData.itsValidator.validate();
    }

    /** Enable notifications for the password file */
    private void enablePasswdExpiryNotif(PasswdFileData fileData)
    {
        try {
            SQLiteDatabase db = itsDbHelper.getWritableDatabase();
            try {
                db.beginTransaction();
                ContentValues values = new ContentValues(1);
                values.put(DB_COL_URIS_URI, fileData.getUri().toString());
                long id = db.insertOrThrow(DB_TABLE_URIS, null, values);
                doUpdatePasswdFileData(id, fileData, db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (SQLException e) {
            Log.e(TAG, "Database error", e);
        }
    }


    /** Update the notification expirations for a password file */
    private void doUpdatePasswdFileData(long uriId,
                                        PasswdFileData fileData,
                                        SQLiteDatabase db)
        throws SQLException
    {
        PasswdSafeApp.dbginfo(TAG, "Update " + fileData.getUri() + ", id: " +
                              uriId);

        db.delete(DB_TABLE_EXPIRYS, DB_MATCH_EXPIRYS_URI,
                  new String[] { Long.toString(uriId) });
        ContentValues values = new ContentValues();
        values.put(DB_COL_EXPIRYS_URI, uriId);
        for (PasswdRecord rec: fileData.getPasswdRecords()) {
            PasswdExpiration expiry = rec.getPasswdExpiry();
            if (expiry != null) {
                PwsRecord pwsrec = rec.getRecord();
                values.put(DB_COL_EXPIRYS_UUID, rec.getUUID());
                values.put(DB_COL_EXPIRYS_TITLE, fileData.getTitle(pwsrec));
                values.put(DB_COL_EXPIRYS_GROUP, fileData.getGroup(pwsrec));
                values.put(DB_COL_EXPIRYS_USER, fileData.getUsername(pwsrec));
                values.put(DB_COL_EXPIRYS_EXPIRE,
                           dateToSqlDate(expiry.itsExpiration));
                db.insertOrThrow(DB_TABLE_EXPIRYS, null, values);
            }
        }

        loadEntries(db);
    }


    /** Load the expiration entries from the database */
    private void loadEntries(SQLiteDatabase db)
    {
        // TODO: use pref
        PasswdRecordFilter.ExpiryFilter filter =
            PasswdRecordFilter.ExpiryFilter.IN_TWO_WEEKS;
        long expiration = filter.getExpiryFromNow(null);
        long nextExpiration = Long.MAX_VALUE;

        HashSet<Long> uris = new HashSet<Long>();
        Cursor uriCursor =
            db.query(DB_TABLE_URIS,
                     new String[] { DB_COL_URIS_ID, DB_COL_URIS_URI },
                     null, null, null, null, null);
        while (uriCursor.moveToNext()) {
            long id = uriCursor.getLong(0);
            String uri = uriCursor.getString(1);
            PasswdSafeApp.dbginfo(TAG, "Load " + uri);

            TreeSet<ExpiryEntry> expired = new TreeSet<ExpiryEntry>();
            Cursor expirysCursor =
                db.query(DB_TABLE_EXPIRYS,
                         new String[] { DB_COL_EXPIRYS_UUID,
                                        DB_COL_EXPIRYS_TITLE,
                                        DB_COL_EXPIRYS_GROUP,
                                        DB_COL_EXPIRYS_USER,
                                        DB_COL_EXPIRYS_EXPIRE },
                         DB_MATCH_EXPIRYS_URI,
                         new String[] { Long.toString(id) },
                         null, null, null);
            while (expirysCursor.moveToNext()) {
                String uuid = expirysCursor.getString(0);
                String expiryStr = expirysCursor.getString(4);
                Date expiry;
                try {
                    expiry = sqlDateToDate(expiryStr);
                } catch (Exception e) {
                    Log.e(TAG,
                          String.format("Invalid expiry date for %1$s: %2$s",
                                        uuid, expiryStr), e);
                    continue;
                }

                long entryExpiry = expiry.getTime();
                if (entryExpiry <= expiration) {
                    String name = expirysCursor.getString(1) + "/" +
                        expirysCursor.getString(2) + "/" +
                        expirysCursor.getString(3);
                    ExpiryEntry entry = new ExpiryEntry(name, uri,
                                                        uuid, expiry);
                    PasswdSafeApp.dbginfo(TAG,
                                          "expired entry: " + entry.itsName);
                    expired.add(entry);
                }
                else if (entryExpiry < nextExpiration) {
                    nextExpiration = entryExpiry;
                }
            }

            if (expired.isEmpty()) {
                continue;
            }

            uris.add(id);
            UriNotifInfo info = itsUriNotifs.get(id);
            if (info == null) {
                info = new UriNotifInfo(itsNextNotifId++);
                itsUriNotifs.put(id, info);
            }

            // Skip the notification if the entries are the same
            if (info.getEntries().equals(expired))
            {
                PasswdSafeApp.dbginfo(TAG, "No expiry changes");
                continue;
            }

            info.setEntries(expired);
            int numExpired = info.getEntries().size();
            ArrayList<String> strs = new ArrayList<String>(numExpired);
            for (ExpiryEntry entry: info.getEntries()) {
                strs.add(entry.itsName);
            }

            String record = null;
            if (numExpired == 1) {
                ExpiryEntry entry = info.getEntries().first();
                record = entry.itsUuid;
            }

            PendingIntent intent = PendingIntent.getActivity(
                itsCtx, 0,
                AbstractFileListActivity.createOpenIntent(
                    Uri.parse(uri), record),
                    PendingIntent.FLAG_UPDATE_CURRENT);

            // TODO: resource strings
            // TODO: sort expiry entries by date so last to expire is shown first
            // TODO: better text for expiry name
            String content = String.format("%d expired passwords", numExpired);
            GuiUtils.showNotification(itsNotifyMgr, itsCtx,
                                      R.drawable.ic_stat_app,
                                      "Password expiration", uri, content,
                                      content, uri, strs,
                                      intent, info.getNotifId());
        }

        Iterator<HashMap.Entry<Long, UriNotifInfo>> iter =
            itsUriNotifs.entrySet().iterator();
        while (iter.hasNext()) {
            HashMap.Entry<Long, UriNotifInfo> entry = iter.next();
            if (!uris.contains(entry.getKey())) {
                itsNotifyMgr.cancel(entry.getValue().getNotifId());
                iter.remove();
            }
        }
        PasswdSafeApp.dbginfo(TAG,
                              "nextExpiration: " + new Date(nextExpiration));
    }


    /** Get the id for a URI or null if not found */
    private Long getDbUriId(String uristr, SQLiteDatabase db)
        throws SQLException
    {
        Cursor cursor = db.query(DB_TABLE_URIS, new String[] { DB_COL_URIS_ID },
                                 DB_MATCH_URIS_URI, new String[] { uristr },
                                 null, null, null);
        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return cursor.getLong(0);
        } finally {
            cursor.close();
        }
    }


    /** Convert a Date to a string for the database */
    private static synchronized String dateToSqlDate(Date date)
    {
        if (date == null) {
            return "";
        }
        return SQL_DATE_FMT.format(date);
    }


    /** Convert a date string in the database to a Date
     * @throws ParseException */
    private static synchronized Date sqlDateToDate(String str)
        throws ParseException
    {
        return SQL_DATE_FMT.parse(str);
    }
    // TODO: not all URIs should support notifications

    /** Database helper class to manage the database tables */
    private static final class DbHelper extends SQLiteOpenHelper
    {
        private static final String DB_NAME = "notifications.db";
        private static final int DB_VERSION = 1;

        /** Constructor */
        public DbHelper(Context context)
        {
            super(context, DB_NAME, null, DB_VERSION);
        }


        /* (non-Javadoc)
         * @see android.database.sqlite.SQLiteOpenHelper#onCreate(android.database.sqlite.SQLiteDatabase)
         */
        @Override
        public void onCreate(SQLiteDatabase db)
        {
            enableForeignKey(db);
            db.execSQL("CREATE TABLE " + DB_TABLE_URIS + " (" +
                       DB_COL_URIS_ID + " INTEGER PRIMARY KEY," +
                       DB_COL_URIS_URI + " TEXT NOT NULL" +
                       ");");
            db.execSQL("CREATE TABLE " + DB_TABLE_EXPIRYS + " (" +
                       DB_COL_EXPIRYS_ID + " INTEGER PRIMARY KEY, " +
                       DB_COL_EXPIRYS_URI + " INTEGER REFERENCES " +
                           DB_TABLE_URIS + "(" + DB_COL_URIS_ID +") NOT NULL, " +
                       DB_COL_EXPIRYS_UUID + " TEXT NOT NULL, " +
                       DB_COL_EXPIRYS_TITLE + " TEXT NOT NULL, " +
                       DB_COL_EXPIRYS_GROUP + " TEXT, " +
                       DB_COL_EXPIRYS_USER + " TEXT, " +
                       DB_COL_EXPIRYS_EXPIRE + " TEXT NOT NULL" +
                       ");");
        }


        /* (non-Javadoc)
         * @see android.database.sqlite.SQLiteOpenHelper#onUpgrade(android.database.sqlite.SQLiteDatabase, int, int)
         */
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
        {
            enableForeignKey(db);
        }


        /* (non-Javadoc)
         * @see android.database.sqlite.SQLiteOpenHelper#onOpen(android.database.sqlite.SQLiteDatabase)
         */
        @Override
        public void onOpen(SQLiteDatabase db)
        {
            enableForeignKey(db);
            super.onOpen(db);
        }


        /** Enable support for foreign keys on the open database connection */
        private void enableForeignKey(SQLiteDatabase db)
            throws SQLException
        {
            if (!db.isReadOnly()) {
                db.execSQL("PRAGMA foreign_keys = ON;");
            }
        }
    }


    /** The ExpiryEntry class represents an expiration entry for notifications */
    private static final class ExpiryEntry implements Comparable<ExpiryEntry>
    {
        public final String itsName;
        public final String itsUri;
        public final String itsUuid;
        public final Date itsExpiry;

        /** Constructor */
        public ExpiryEntry(String name, String uri, String uuid, Date expiry)
        {
            itsName = name;
            itsUri = uri;
            itsUuid = uuid;
            itsExpiry = expiry;
        }


        /* (non-Javadoc)
         * @see java.lang.Comparable#compareTo(java.lang.Object)
         */
        public int compareTo(ExpiryEntry another)
        {
            int rc = itsExpiry.compareTo(another.itsExpiry);
            if (rc == 0) {
                rc = itsName.compareTo(another.itsName);
                if (rc == 0) {
                    rc = itsUuid.compareTo(another.itsUuid);
                    if (rc == 0) {
                        rc = itsUri.compareTo(another.itsUri);
                    }
                }
            }
            return rc;
        }
    }


    /** The UriNotifInfo contains the parsed notification data for a URI */
    private static final class UriNotifInfo
    {
        private final int itsNotifId;
        private final TreeSet<ExpiryEntry> itsEntries =
            new TreeSet<ExpiryEntry>();

        /** Constructor */
        public UriNotifInfo(int notifId)
        {
            itsNotifId = notifId;
        }

        /** Get the notification id */
        public int getNotifId()
        {
            return itsNotifId;
        }

        /** Get the expired entries */
        public SortedSet<ExpiryEntry> getEntries()
        {
            return itsEntries;
        }

        /** Set the expired entries */
        public void setEntries(Set<ExpiryEntry> entries)
        {
            itsEntries.clear();
            itsEntries.addAll(entries);
        }
    }
}
