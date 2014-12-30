/*
 * Copyright (©) 2014 Jeff Harris <jefftharris@gmail.com> All rights reserved.
 * Use of the code is allowed under the Artistic License 2.0 terms, as specified
 * in the LICENSE file distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
/**
 *
 */
package com.jefftharris.passwdsafe.sync.gdrive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.accounts.Account;
import android.content.Context;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableNotifiedException;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Changes;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;
import com.jefftharris.passwdsafe.lib.PasswdSafeContract;
import com.jefftharris.passwdsafe.lib.PasswdSafeUtil;
import com.jefftharris.passwdsafe.sync.R;
import com.jefftharris.passwdsafe.sync.SyncUpdateHandler;
import com.jefftharris.passwdsafe.sync.lib.DbFile;
import com.jefftharris.passwdsafe.sync.lib.DbProvider;
import com.jefftharris.passwdsafe.sync.lib.SyncDb;
import com.jefftharris.passwdsafe.sync.lib.SyncLogRecord;

/**
 * The Syncer class encapsulates a sync operation
 */
public class GDriveSyncer
{
    private final Drive itsDrive;
    private final String itsDriveToken;
    private final DbProvider itsProvider;
    private final SQLiteDatabase itsDb;
    private final boolean itsIsManual;
    private final SyncLogRecord itsLogrec;
    private final Context itsContext;
    private final HashMap<String, File> itsFileCache =
            new HashMap<String, File>();

    private static final HashMap<String, FolderRefs> itsFolderRefs =
            new HashMap<String, FolderRefs>();
    private static boolean itsFolderRefsInit = false;

    private static final String TAG = "GDriveSyncer";


    /** Constructor */
    public GDriveSyncer(Account acct,
                        DbProvider provider,
                        SQLiteDatabase db,
                        boolean manual,
                        SyncLogRecord logrec,
                        Context ctx)
    {
        Pair<Drive, String> drive = getDriveService(acct, ctx);
        itsDrive = drive.first;
        itsDriveToken = drive.second;
        itsProvider = provider;
        itsDb = db;
        itsIsManual = manual;
        itsLogrec = logrec;
        itsContext = ctx;
    }


    /** Sync the provider */
    public SyncUpdateHandler.GDriveState sync() throws Exception
    {
        if (itsDrive == null) {
            return SyncUpdateHandler.GDriveState.PENDING_AUTH;
        }

        SyncUpdateHandler.GDriveState syncState =
                SyncUpdateHandler.GDriveState.OK;
        try {
            List<GDriveSyncOper> opers = null;
            try {
                itsDb.beginTransaction();
                long changeId = itsProvider.itsSyncChange;
                PasswdSafeUtil.dbginfo(TAG, "largest change %d", changeId);
                Pair<Long, List<GDriveSyncOper>> syncrc;
                boolean noSyncChange = itsIsManual || (changeId == -1);
                itsLogrec.setFullSync(noSyncChange);
                if (!itsFolderRefsInit || noSyncChange) {
                    itsFolderRefsInit = true;
                    syncrc = performFullSync();
                } else {
                    syncrc = performSyncSince(changeId);
                }
                long newChangeId = syncrc.first;
                opers = syncrc.second;
                if (changeId != newChangeId) {
                    SyncDb.updateProviderSyncChange(itsProvider,
                                                    newChangeId, itsDb);
                }
                itsDb.setTransactionSuccessful();
            } finally {
                itsDb.endTransaction();
            }

            if (opers != null) {
                for (GDriveSyncOper oper: opers) {
                    try {
                        itsLogrec.addEntry(oper.getDescription(itsContext));
                        oper.doOper(itsDrive, itsContext);
                        try {
                            itsDb.beginTransaction();
                            oper.doPostOperUpdate(itsDb, itsContext);
                            itsDb.setTransactionSuccessful();
                        } finally {
                            itsDb.endTransaction();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Sync error for file " + oper.getFile(), e);
                        itsLogrec.addFailure(e);
                    }
                }
            }

            itsContext.getContentResolver().notifyChange(
                     PasswdSafeContract.CONTENT_URI, null, false);
        } catch (UserRecoverableAuthIOException e) {
            PasswdSafeUtil.dbginfo(TAG, e, "Recoverable google auth error");
            GoogleAuthUtil.invalidateToken(itsContext, itsDriveToken);
            syncState = SyncUpdateHandler.GDriveState.AUTH_REQUIRED;
        } catch (GoogleAuthIOException e) {
            Log.e(TAG, "Google auth error", e);
            GoogleAuthUtil.invalidateToken(itsContext, itsDriveToken);
            throw e;
        } catch (Exception e) {
            reset();
            throw e;
        }

        return syncState;
    }


    /** Reset the syncer's cached information */
    public static void reset()
    {
        itsFolderRefs.clear();
        itsFolderRefsInit = false;
    }


    /** Get the Google account credential */
    public static GoogleAccountCredential getAcctCredential(Context ctx)
    {
        return GoogleAccountCredential.usingOAuth2(
                ctx, Collections.singletonList(DriveScopes.DRIVE));
    }


    /** Perform a full sync of the files */
    private final Pair<Long, List<GDriveSyncOper>> performFullSync()
            throws SQLException, IOException
    {
        PasswdSafeUtil.dbginfo(TAG, "Perform full sync");
        About about = itsDrive.about().get()
                .setFields(GDriveProvider.ABOUT_FIELDS).execute();
        long largestChangeId = about.getLargestChangeId();

        HashMap<String, File> allRemFiles = new HashMap<String, File>();
        StringBuilder query = new StringBuilder();
        query.append("not trashed");
        // TODO: check other mime types
        query.append(" and ( mimeType = 'application/octet-stream' or ");
        query.append("       mimeType = 'application/psafe3' )");
        query.append(" and fullText contains '.psafe3'");
        Files.List request = itsDrive.files().list()
                .setQ(query.toString())
                .setFields("nextPageToken,items("+GDriveProvider.FILE_FIELDS+")");
        do {
            FileList files = request.execute();
            PasswdSafeUtil.dbginfo(TAG, "num files: %d",
                                   files.getItems().size());
            for (File file: files.getItems()) {
                if (!isSyncFile(file)) {
                    if (isFolderFile(file)) {
                        PasswdSafeUtil.dbginfo(TAG, "isdir %s", file);
                    }
                    continue;
                }
                PasswdSafeUtil.dbginfo(TAG, "File %s", fileToString(file));
                allRemFiles.put(file.getId(), file);
            }
            request.setPageToken(files.getNextPageToken());
        } while((request.getPageToken() != null) &&
                (request.getPageToken().length() > 0));

        List<GDriveSyncOper> opers = performSync(allRemFiles, true);
        return new Pair<Long, List<GDriveSyncOper>>(largestChangeId, opers);
    }


    /** Perform a sync of files since the given change id */
    private final Pair<Long, List<GDriveSyncOper>>
    performSyncSince(long changeId)
            throws SQLException, IOException
    {
        PasswdSafeUtil.dbginfo(TAG, "performSyncSince %d", changeId);
        HashMap<String, File> changedFiles = new HashMap<String, File>();
        Changes.List request =
            itsDrive.changes().list().setStartChangeId(changeId + 1)
            .setFields("largestChangeId,nextPageToken," +
                    "items(deleted,fileId,file("+GDriveProvider.FILE_FIELDS+"))");
        do {
            ChangeList changes = request.execute();
            long changesLargestId =
                    changes.getLargestChangeId().longValue();

            for (Change change: changes.getItems()) {
                File file = change.getFile();
                if (change.getDeleted()) {
                    file = null;
                } else if (isFolderFile(file)) {
                    PasswdSafeUtil.dbginfo(TAG, "isdir %s", file);
                    FolderRefs folderRefs = itsFolderRefs.get(file.getId());
                    if (folderRefs != null) {
                        for (String fileId: folderRefs.itsFileRefs) {
                            File refFile = getCachedFile(fileId);
                            changedFiles.put(fileId, refFile);
                        }
                    }
                    file = null;
                } else if (!isSyncFile(file)) {
                    file = null;
                }
                changedFiles.put(change.getFileId(), file);
                PasswdSafeUtil.dbginfo(TAG, "performSyncSince changed %s: %s",
                                       change.getFileId(), fileToString(file));
            }

            if (changesLargestId > changeId) {
                changeId = changesLargestId;
            }
            request.setPageToken(changes.getNextPageToken());
        } while((request.getPageToken() != null) &&
                (request.getPageToken().length() > 0));

        List<GDriveSyncOper> opers = performSync(changedFiles, false);
        return new Pair<Long, List<GDriveSyncOper>>(changeId, opers);
    }


    /** Perform a sync of the files */
    private final List<GDriveSyncOper>
    performSync(HashMap<String, File> remfiles, boolean isAllRemoteFiles)
            throws SQLException, IOException
    {
        itsFileCache.putAll(remfiles);
        Map<String, String> fileFolders = computeFilesFolders(remfiles);

        List<DbFile> dbfiles = SyncDb.getFiles(itsProvider.itsId, itsDb);
        for (DbFile dbfile: dbfiles) {
            if (remfiles.containsKey(dbfile.itsRemoteId)) {
                File remfile = remfiles.get(dbfile.itsRemoteId);
                if (remfile != null) {
                    checkRemoteFileChange(dbfile, remfile, fileFolders);
                } else {
                    PasswdSafeUtil.dbginfo(TAG, "performSync remove remote %s",
                                           dbfile);
                    SyncDb.updateRemoteFileDeleted(dbfile.itsId, itsDb);
                }
                remfiles.remove(dbfile.itsRemoteId);
            } else if (isAllRemoteFiles) {
                PasswdSafeUtil.dbginfo(TAG, "performSync remove remote %s",
                                       dbfile);
                SyncDb.updateRemoteFileDeleted(dbfile.itsId, itsDb);
            }
        }

        for (File remfile: remfiles.values()) {
            if (remfile == null) {
                continue;
            }
            String fileId = remfile.getId();
            PasswdSafeUtil.dbginfo(TAG, "performSync add remote %s", fileId);
            SyncDb.addRemoteFile(itsProvider.itsId, fileId,
                                 remfile.getTitle(),
                                 fileFolders.get(fileId),
                                 remfile.getModifiedDate().getValue(),
                                 itsDb);
        }

        List<GDriveSyncOper> opers = new ArrayList<GDriveSyncOper>();
        dbfiles = SyncDb.getFiles(itsProvider.itsId, itsDb);
        for (DbFile dbfile: dbfiles) {
            resolveSyncOper(dbfile, opers);
        }

        return opers;
    }


    /** Check for a remote file change and update */
    private final void checkRemoteFileChange(DbFile dbfile,
                                             File remfile,
                                             Map<String, String> fileFolders)
    {
        PasswdSafeUtil.dbginfo(TAG, "performSync update remote %s",
                               dbfile);

        boolean changed = true;
        do {
            // TODO: add md5 change support
            String remTitle = remfile.getTitle();
            String remFolder = fileFolders.get(dbfile.itsRemoteId);
            long remModDate = remfile.getModifiedDate().getValue();
            if (!TextUtils.equals(dbfile.itsRemoteTitle, remTitle) ||
                    !TextUtils.equals(dbfile.itsRemoteFolder, remFolder) ||
                    (dbfile.itsRemoteModDate != remModDate) ||
                    TextUtils.isEmpty(dbfile.itsLocalFile)) {
                break;
            }

            java.io.File localFile =
                    itsContext.getFileStreamPath(dbfile.itsLocalFile);
            if (!localFile.exists()) {
                break;
            }

            changed = false;
        } while(false);

        if (!changed) {
            return;
        }

        // TODO: refine metadata vs. file contents modification?
        SyncDb.updateRemoteFile(dbfile.itsId, dbfile.itsRemoteId,
                                remfile.getTitle(),
                                fileFolders.get(dbfile.itsRemoteId),
                                remfile.getModifiedDate().getValue(), itsDb);
        switch (dbfile.itsRemoteChange) {
        case NO_CHANGE:
        case REMOVED: {
            SyncDb.updateRemoteFileChange(dbfile.itsId,
                                          DbFile.FileChange.MODIFIED, itsDb);
            break;
        }
        case ADDED:
        case MODIFIED: {
            break;
        }
        }
    }


    /** Resolve the sync operations for a file */
    private final void resolveSyncOper(DbFile dbfile,
                                       List<GDriveSyncOper> opers)
            throws SQLException
    {
        if ((dbfile.itsLocalChange != DbFile.FileChange.NO_CHANGE) ||
                (dbfile.itsRemoteChange != DbFile.FileChange.NO_CHANGE)) {
            PasswdSafeUtil.dbginfo(TAG, "resolveSyncOper %s", dbfile);
        }

        // TODO: complete all cases
        switch (dbfile.itsLocalChange) {
        case ADDED: {
            switch (dbfile.itsRemoteChange) {
            case ADDED: {
                // Duplicate local change as new file with updated name; sync both
                // Show notification
                break;
            }
            case MODIFIED: {
                // Same as added/added case
                break;
            }
            case NO_CHANGE: {
                // Sync local to remote as new
                opers.add(new GDriveLocalToRemoteOper(dbfile, itsFileCache,
                                                      false));
                break;
            }
            case REMOVED: {
                // Recreate file with updated name; sync local to remote
                // Show notification
                break;
            }
            }
            break;
        }
        case MODIFIED: {
            switch (dbfile.itsRemoteChange) {
            case ADDED: {
                // Duplicate local change as new file with updated name; sync both
                // Show notification
                break;
            }
            case MODIFIED: {
                // Duplicate local change as new file with updated name; sync both
                // Show notification
                break;
            }
            case NO_CHANGE: {
                // Sync local to remote
                opers.add(new GDriveLocalToRemoteOper(dbfile, itsFileCache,
                                                      false));
                break;
            }
            case REMOVED: {
                // Recreate file with updated name; sync local to remote
                // Show notification
                break;
            }
            }
            break;
        }
        case NO_CHANGE: {
            switch (dbfile.itsRemoteChange) {
            case ADDED:
            case MODIFIED: {
                // Sync remote to local
                opers.add(new GDriveRemoteToLocalOper(dbfile, itsFileCache));
                break;
            }
            case NO_CHANGE: {
                // Nothing
                break;
            }
            case REMOVED: {
                // Remove file
                opers.add(new GDriveRmFileOper(dbfile));
                break;
            }
            }
            break;
        }
        case REMOVED: {
            switch (dbfile.itsRemoteChange) {
            case ADDED: {
                // Sync remote to local to recover file
                // Show notification
                break;
            }
            case MODIFIED: {
                // Same as removed/modified
                break;
            }
            case NO_CHANGE: {
                // Remove file
                opers.add(new GDriveRmFileOper(dbfile));
                break;
            }
            case REMOVED: {
                // Remove file
                opers.add(new GDriveRmFileOper(dbfile));
                break;
            }
            }
            break;
        }
        }
    }


    /** Compute the folders for the given files */
    private Map<String, String> computeFilesFolders(Map<String, File> remfiles)
            throws IOException
    {
        HashMap<String, String> fileFolders = new HashMap<String, String>();
        for (File remfile: remfiles.values()) {
            if (remfile == null) {
                continue;
            }

            // Remove the file from the folder refs to handle moves.
            // The file will be re-added to the correct refs
            String id = remfile.getId();
            for (FolderRefs refs: itsFolderRefs.values()) {
                refs.removeRef(id);
            }

            String folders = computeFolders(remfile);
            fileFolders.put(id, folders);
        }
        // Purge empty folder references
        Iterator<Map.Entry<String, FolderRefs>> iter =
                itsFolderRefs.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, FolderRefs> entry = iter.next();
            Set<String> fileRefs = entry.getValue().itsFileRefs;
            if (PasswdSafeUtil.DEBUG) {
                PasswdSafeUtil.dbginfo(TAG, "cached folder %s, refs [%s]",
                                       entry.getKey(),
                                       TextUtils.join(", ", fileRefs));
            }
            if (fileRefs.isEmpty()) {
                iter.remove();
            }
        }

        return fileFolders;
    }


    /** Compute the folders for a file */
    private String computeFolders(File remfile)
            throws IOException
    {
        String fileId = remfile.getId();
        ArrayList<String> folders = new ArrayList<String>();
        for (ParentReference parent: remfile.getParents()) {
            traceParentRefs(parent, "", folders, fileId);
        }
        Collections.sort(folders);
        String foldersStr = TextUtils.join(", ", folders);
        PasswdSafeUtil.dbginfo(TAG, "compFolders %s: %s",
                               remfile.getTitle(), foldersStr);
        return foldersStr;
    }


    /** Trace the parent references for a file to compute the full paths of
     *  its folders */
    private void traceParentRefs(ParentReference parent,
                                 String suffix,
                                 ArrayList<String> folders,
                                 String fileId)
            throws IOException
    {
        String parentId = parent.getId();
        File parentFile = getCachedFile(parentId);
        if (parent.getIsRoot()) {
            suffix = parentFile.getTitle() + suffix;
            folders.add(suffix);
        } else {
            FolderRefs refs = itsFolderRefs.get(parentId);
            if (refs == null) {
                refs = new FolderRefs();
                itsFolderRefs.put(parentId, refs);
            }
            refs.addRef(fileId);
            suffix = "/" + parentFile.getTitle() + suffix;
            for (ParentReference parentParent: parentFile.getParents()) {
                traceParentRefs(parentParent, suffix, folders, fileId);
            }
        }
    }


    /** Get a cached file */
    private final File getCachedFile(String id)
            throws IOException
    {
        File file = itsFileCache.get(id);
        if (file == null) {
            file = itsDrive.files().get(id).setFields(
                    GDriveProvider.FILE_FIELDS).execute();
            itsFileCache.put(id, file);
        }
        return file;
    }


    /** Should the file be synced */
    private static boolean isSyncFile(File file)
    {
        if (isFolderFile(file) || file.getLabels().getTrashed()) {
            return false;
        }
        String ext = file.getFileExtension();
        return (ext != null) && ext.equals("psafe3");
    }


    /** Is the file a folder */
    private static boolean isFolderFile(File file)
    {
        return !file.getLabels().getTrashed() &&
                GDriveProvider.FOLDER_MIME.equals(file.getMimeType());
    }


    /** Get a string form for a remote file */
    private static String fileToString(File file)
    {
        if (file == null) {
            return "{null}";
        }
        return String.format(Locale.US,
                             "{id:%s, title:%s, mime:%s, md5:%s",
                             file.getId(), file.getTitle(),
                             file.getMimeType(), file.getMd5Checksum());
    }

    /**
     * Retrieve a authorized service object to send requests to the Google
     * Drive API. On failure to retrieve an access token, a notification is
     * sent to the user requesting that authorization be granted for the
     * {@code https://www.googleapis.com/auth/drive} scope.
     *
     * @return An authorized service object and its auth token.
     */
    private static Pair<Drive, String> getDriveService(Account acct,
                                                       Context ctx)
    {
        Drive drive = null;
        String token = null;
        try {
            GoogleAccountCredential credential = getAcctCredential(ctx);
            credential.setSelectedAccountName(acct.name);

            token = GoogleAuthUtil.getTokenWithNotification(
                ctx, acct.name, credential.getScope(),
                null, PasswdSafeContract.AUTHORITY, null);

            Drive.Builder builder =
                    new Drive.Builder(AndroidHttp.newCompatibleTransport(),
                                      new GsonFactory(), credential);
            builder.setApplicationName(ctx.getString(R.string.app_name));
            drive = builder.build();
        } catch (UserRecoverableNotifiedException e) {
            // User notified
            PasswdSafeUtil.dbginfo(TAG, e, "User notified auth exception");
            GoogleAuthUtil.invalidateToken(ctx, null);
        } catch (GoogleAuthException e) {
            // Unrecoverable
            Log.e(TAG, "Unrecoverable auth exception", e);
        }
        catch (IOException e) {
            // Transient
            PasswdSafeUtil.dbginfo(TAG, e, "Transient error");
        } catch (Exception e) {
            Log.e(TAG, "Token exception", e);
        }
        return new Pair<Drive, String>(drive, token);
    }
}