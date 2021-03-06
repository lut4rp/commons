package org.wikimedia.commons;

import java.io.*;
import java.util.Date;

import org.mediawiki.api.*;
import org.wikimedia.commons.media.Media;

import de.mastacode.http.ProgressListener;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import android.provider.MediaStore;
import android.support.v4.app.NotificationCompat;
import android.text.method.DateTimeKeyListener;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;
import android.net.*;

public class UploadService extends IntentService {

    private static final String EXTRA_PREFIX = "org.wikimedia.commons.uploader";
    public static final String EXTRA_MEDIA_URI = EXTRA_PREFIX + ".media_uri";
    public static final String EXTRA_TARGET_FILENAME = EXTRA_PREFIX + ".filename";
    public static final String EXTRA_DESCRIPTION = EXTRA_PREFIX + ".description";
    public static final String EXTRA_EDIT_SUMMARY = EXTRA_PREFIX + ".summary";
   
    private NotificationManager notificationManager;
    private CommonsApplication app;
    
    public UploadService(String name) {
        super(name);
    }

    public UploadService() {
        super("UploadService");
    }
    // DO NOT HAVE NOTIFICATION ID OF 0 FOR ANYTHING
    // See http://stackoverflow.com/questions/8725909/startforeground-does-not-show-my-notification
    // Seriously, Android?
    public static final int NOTIFICATION_DOWNLOAD_IN_PROGRESS = 1;
    public static final int NOTIFICATION_DOWNLOAD_COMPLETE = 2;
    public static final int NOTIFICATION_UPLOAD_FAILED = 3;
    
    private class NotificationUpdateProgressListener implements ProgressListener {

        Notification curNotification;
        String notificationTag;
        boolean notificationTitleChanged;
       
        String notificationProgressTitle;
        String notificationFinishingTitle;
        
        private int lastPercent = 0;
        
        public NotificationUpdateProgressListener(Notification curNotification, String notificationTag, String notificationProgressTitle, String notificationFinishingTitle) {
            this.curNotification = curNotification;
            this.notificationTag = notificationTag;
            this.notificationProgressTitle = notificationProgressTitle;
            this.notificationFinishingTitle = notificationFinishingTitle;
        }
        @Override
        public void onProgress(long transferred, long total) {
            RemoteViews curView = curNotification.contentView;
            if(!notificationTitleChanged) {
                curView.setTextViewText(R.id.uploadNotificationTitle, notificationProgressTitle);
                notificationTitleChanged = false;
                startForeground(NOTIFICATION_DOWNLOAD_IN_PROGRESS, curNotification);
            }
            int percent =(int) ((double)transferred / (double)total * 100);
            if(percent > lastPercent) {
                curNotification.contentView.setProgressBar(R.id.uploadNotificationProgress, 100, percent, false); 
                startForeground(NOTIFICATION_DOWNLOAD_IN_PROGRESS, curNotification);
                lastPercent = percent;
            }
            if(percent == 100) {
                // Completed!
                curView.setTextViewText(R.id.uploadNotificationTitle, notificationFinishingTitle);
                startForeground(NOTIFICATION_DOWNLOAD_IN_PROGRESS, curNotification);
            }
        }

    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("Commons", "ZOMG I AM BEING KILLED HALP!");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        app = (CommonsApplication)this.getApplicationContext();
    }

    
    @Override
    protected void onHandleIntent(Intent intent) {
       MWApi api = app.getApi();
       InputStream file;
       long length;
       ApiResult result;
       RemoteViews notificationView;
       
       Bundle extras = intent.getExtras();
       Uri mediaUri = (Uri)extras.getParcelable(EXTRA_MEDIA_URI);
       String filename = intent.getStringExtra(EXTRA_TARGET_FILENAME);
       String description = intent.getStringExtra(EXTRA_DESCRIPTION);
       String editSummary = intent.getStringExtra(EXTRA_EDIT_SUMMARY);
       String notificationTag = mediaUri.toString();
       Date dateCreated = null;
               
       try {
           file =  this.getContentResolver().openInputStream(mediaUri);
           length = this.getContentResolver().openAssetFileDescriptor(mediaUri, "r").getLength();
           Cursor cursor = this.getContentResolver().query(mediaUri,
                new String[] { MediaStore.Images.ImageColumns.DATE_TAKEN }, null, null, null);
           if(cursor.getCount() != 0) {
               cursor.moveToFirst();
               dateCreated = new Date(cursor.getInt(0));
           }
       } catch (FileNotFoundException e) {
           throw new RuntimeException(e);
       }
            
       notificationView = new RemoteViews(getPackageName(), R.layout.layout_upload_progress);
       notificationView.setTextViewText(R.id.uploadNotificationTitle, String.format(getString(R.string.upload_progress_notification_title_start), filename));
       notificationView.setProgressBar(R.id.uploadNotificationProgress, 100, 0, false);
       
       Log.d("Commons", "Before execution!");
       Notification progressNotification = new NotificationCompat.Builder(this).setAutoCancel(true)
               .setSmallIcon(R.drawable.ic_launcher)
               .setAutoCancel(true)
               .setContent(notificationView)
               .setOngoing(true)
               .setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, new Intent(), 0))
               .setTicker(String.format(getString(R.string.upload_progress_notification_title_in_progress), filename))
               .getNotification();
     
       this.startForeground(NOTIFICATION_DOWNLOAD_IN_PROGRESS, progressNotification);
       
       Log.d("Commons", "Just before");
       NotificationUpdateProgressListener notificationUpdater = new NotificationUpdateProgressListener(progressNotification, notificationTag, 
                                                                    String.format(getString(R.string.upload_progress_notification_title_in_progress), filename), 
                                                                    String.format(getString(R.string.upload_progress_notification_title_finishing), filename)
                                                                );
       try {
           if(!api.validateLogin()) {
               // Need to revalidate! 
               if(app.revalidateAuthToken()) {
                   Log.d("Commons", "Successfully revalidated token!");
               } else {
                   Log.d("Commons", "Unable to revalidate :(");
                   // TODO: Put up a new notification, ask them to re-login
                   stopForeground(true);
                   Toast failureToast = Toast.makeText(this, R.string.authentication_failed, Toast.LENGTH_LONG);
                   failureToast.show();
                   return;
               }
           }
           Media media = new Media(mediaUri, filename, description, editSummary, app.getCurrentAccount().name, dateCreated);
           result = api.upload(filename, file, length, media.getPageContents(), editSummary, notificationUpdater);
       } catch (IOException e) {
           Log.d("Commons", "I have a network fuckup");
           stopForeground(true);
           Notification failureNotification = new NotificationCompat.Builder(this).setAutoCancel(true)
                   .setSmallIcon(R.drawable.ic_launcher)
                   .setAutoCancel(true)
                   .setContentIntent(PendingIntent.getService(getApplicationContext(), 0, intent, 0))
                   .setTicker(String.format(getString(R.string.upload_failed_notification_title), filename))
                   .setContentTitle(String.format(getString(R.string.upload_failed_notification_title), filename))
                   .setContentText(getString(R.string.upload_failed_notification_subtitle))
                   .getNotification();
           notificationManager.notify(NOTIFICATION_UPLOAD_FAILED, failureNotification);
           return;
       }
      
       Log.d("Commons", "Response is"  + CommonsApplication.getStringFromDOM(result.getDocument()));
       stopForeground(true);
       
       String descUrl = result.getString("/api/upload/imageinfo/@descriptionurl");
       
       Intent openUploadedPageIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(descUrl));
       Notification doneNotification = new NotificationCompat.Builder(this)
               .setAutoCancel(true)
               .setSmallIcon(R.drawable.ic_launcher)
               .setContentTitle(String.format(getString(R.string.upload_completed_notification_title), filename))
               .setContentText(getString(R.string.upload_completed_notification_text))
               .setTicker(String.format(getString(R.string.upload_completed_notification_title), filename))
               .setContentIntent(PendingIntent.getActivity(this, 0, openUploadedPageIntent, 0))
               .getNotification();
       
       notificationManager.notify(notificationTag, NOTIFICATION_DOWNLOAD_COMPLETE, doneNotification);
    }
}
