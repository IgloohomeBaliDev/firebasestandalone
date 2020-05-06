// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.firebasemessaging;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.view.FlutterCallbackInformation;
import io.flutter.view.FlutterMain;
import io.flutter.view.FlutterNativeView;
import io.flutter.view.FlutterRunArguments;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FlutterFirebaseMessagingService extends FirebaseMessagingService {

  public static final String ACTION_REMOTE_MESSAGE =
          "io.flutter.plugins.firebasemessaging.NOTIFICATION";
  public static final String EXTRA_REMOTE_MESSAGE = "notification";

  public static final String ACTION_TOKEN = "io.flutter.plugins.firebasemessaging.TOKEN";
  public static final String EXTRA_TOKEN = "token";

  private static final String SHARED_PREFERENCES_KEY = "io.flutter.android_fcm_plugin";
  private static final String BACKGROUND_SETUP_CALLBACK_HANDLE_KEY = "background_setup_callback";
  private static final String BACKGROUND_MESSAGE_CALLBACK_HANDLE_KEY =
          "background_message_callback";

  // TODO(kroikie): make isIsolateRunning per-instance, not static.
  private static AtomicBoolean isIsolateRunning = new AtomicBoolean(false);

  /** Background Dart execution context. */
  private static FlutterNativeView backgroundFlutterView;

  private static MethodChannel backgroundChannel;

  private static Long backgroundMessageHandle;

  private static List<RemoteMessage> backgroundMessageQueue =
          Collections.synchronizedList(new LinkedList<RemoteMessage>());

  private static PluginRegistry.PluginRegistrantCallback pluginRegistrantCallback;

  private static final String TAG = "FlutterFcmService";
  private static final String C_TAG = "FCM custom notif";
  private static Context backgroundContext;

  @Override
  public void onCreate() {
    super.onCreate();

    backgroundContext = getApplicationContext();
    FlutterMain.ensureInitializationComplete(backgroundContext, null);

    // If background isolate is not running start it.
    if (!isIsolateRunning.get()) {
      SharedPreferences p = backgroundContext.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
      long callbackHandle = p.getLong(BACKGROUND_SETUP_CALLBACK_HANDLE_KEY, 0);
      startBackgroundIsolate(backgroundContext, callbackHandle);
    }
  }

  /**
   * Called when message is received.
   *
   * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
   */
  @Override
  public void onMessageReceived(final RemoteMessage remoteMessage) {
    // If application is running in the foreground use local broadcast to handle message.
    // Otherwise use the background isolate to handle message.
    if (isApplicationForeground(this)) {
      Intent intent = new Intent(ACTION_REMOTE_MESSAGE);
      intent.putExtra(EXTRA_REMOTE_MESSAGE, remoteMessage);
      LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    } else {
      // If background isolate is not running yet, put message in queue and it will be handled
      // when the isolate starts.
      if (!isIsolateRunning.get()) {
        backgroundMessageQueue.add(remoteMessage);
      } else {
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(getMainLooper())
                .post(
                        new Runnable() {
                          @Override
                          public void run() {
                            executeDartCallbackInBackgroundIsolate(
                                    FlutterFirebaseMessagingService.this, remoteMessage, latch);
                          }
                        });
        try {
          latch.await();
        } catch (InterruptedException ex) {
          Log.i(TAG, "Exception waiting to execute Dart callback", ex);
        }
      }
      handleBackgroundNotification(remoteMessage);
    }
  }

  private static AtomicInteger c = new AtomicInteger(0);
  public static int getID() {
    return c.incrementAndGet();
  }
  private static final String CLICK_ACTION_VALUE = "FLUTTER_NOTIFICATION_CLICK";
  String currentTimeInMillis = Long.toString(System.currentTimeMillis());

  void handleBackgroundNotification(RemoteMessage remoteMessage) {
    int notificationId = (int) System.currentTimeMillis();
    int bodyNotificationId = getID();
    String GROUP_PUSH_NOTIFICATION = "com.android.example.PUSH_NOTIFICATION";
    String groupId = "push_notification";
    String groupName = "Push Notification";
    Log.d(C_TAG, remoteMessage.toString());
    Log.d(C_TAG, "onMessageReceived id: " + notificationId);
    Map<String, String> message = remoteMessage.getData();
    JSONObject messageObject = new JSONObject(message);
    int summaryIcon = getIconFromDrawable(messageObject.optString("@mipmap/ic_launcher"));

    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "noti_push_app_1");
    builder.setContentTitle(messageObject.optString("title"));
    builder.setContentText(messageObject.optString("body"));
    builder.setContentIntent(getPendingIntent(remoteMessage, notificationId, bodyNotificationId));
    builder.setSmallIcon(this.getApplicationInfo().icon);
    builder.setAutoCancel(true);
    builder.setGroup(groupId);
    if (remoteMessage.getData().containsKey("actions")) {
      try {
        String actionsOject = messageObject.optString("actions");
        Log.d(C_TAG, actionsOject);
        JSONArray actions = new JSONArray(actionsOject);
        for (int i = 0; i < actions.length(); i++) {
          Log.d(C_TAG, messageObject.toString());
          JSONObject actionObject = actions.getJSONObject(i);
          final String action = actionObject.optString("title");
          if (action.equals("REJECT") || action.equals("CANCEL") || action.equals("CLOSE")) {
            ///do nothing if action is reject/cancel/close
            Intent notificationIntent = new Intent();
            PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, notificationIntent, 0);
            int icon = getIconFromDrawable(actionObject.optString("@mipmap/ic_launcher"));
            builder.addAction(new NotificationCompat.Action.Builder(icon, action, pendingIntent).build());
          } else {
            int icon = getIconFromDrawable(actionObject.optString("@mipmap/ic_launcher"));
            Log.d("ICON", actionObject.optString("@mipmap/ic_launcher"));
            Log.d("NOTIFICATION_ID", Integer.toString(notificationId));
            Log.d("currentTimeMillis", currentTimeInMillis);

            builder.addAction(new NotificationCompat.Action.Builder(icon, action, getActionPendingIntent(remoteMessage, notificationId)).build());
          }
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }
    NotificationManager notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);


    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      String channelId = "noti_push_app_1";
      String channelName = "noti_push_app";
      int importance = NotificationManager.IMPORTANCE_HIGH;
      NotificationChannel mChannel = new NotificationChannel(
              channelId, channelName, importance);
      notificationManager.createNotificationChannelGroup(new NotificationChannelGroup(groupId, groupName));
      notificationManager.createNotificationChannel(mChannel);

    }

    Notification summaryNotification =
            new NotificationCompat.Builder(this, "noti_push_app_1")
                    .setContentTitle(messageObject.optString("title"))
                    //set content text to support devices running API level < 24
                    .setContentText("Two new messages")
                    //build summary info into InboxStyle template
                    .setStyle(new NotificationCompat.InboxStyle())
                    //specify which group this notification belongs to
                    .setGroup(groupId)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    //set this notification as the summary for the group
                    .setGroupSummary(true)
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_dark)
                    .build();

    notificationManager.notify(notificationId, builder.build());
    if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      notificationManager.notify(0, summaryNotification);
    }
  }

  PendingIntent getActionPendingIntent(RemoteMessage remoteMessage, int notifId) {
    Log.d("notifId", Integer.toString(notifId));
    RemoteMessage message = new RemoteMessage.Builder("message from server")
            .setMessageId(remoteMessage.getMessageId())
            .setData(remoteMessage.getData())
            .build();
    Intent launchIntent = message.toIntent();
    launchIntent.putExtra("actionClick", "ACCEPT");
    launchIntent.putExtra("notifId", Integer.toString(notifId));
    launchIntent.setAction(CLICK_ACTION_VALUE);

    return PendingIntent.getActivity(
            getApplicationContext(),
            notifId,
            launchIntent,
            0);
  }

  PendingIntent getPendingIntent(RemoteMessage remoteMessage, int notifId, int bodyResponse) {
    String packageName = getApplication().getPackageName();
    Log.d("the package is : ", packageName);
    RemoteMessage message = new RemoteMessage.Builder("message from server")
            .setMessageId(remoteMessage.getMessageId())
            .setData(remoteMessage.getData())
            .build();
    Intent launchIntent = message.toIntent();
    launchIntent.putExtra("actionClick", "BODY");
    launchIntent.putExtra("notifId", Integer.toString(notifId));
    launchIntent.setAction(CLICK_ACTION_VALUE);
    return PendingIntent.getActivity(
            getApplicationContext(),
            bodyResponse,
            launchIntent,
            0);
  }
  int getIconFromDrawable(String name) {
    try {
      Class res = R.drawable.class;
      Field field = res.getField(name);
      return field.getInt(null);
    } catch (Exception e) {
      Log.e(C_TAG, "Failed to get drawable id from name " + name, e);
      return 0;
    }
  }

  /**
   * Called when a new token for the default Firebase project is generated.
   *
   * @param token The token used for sending messages to this application instance. This token is
   *     the same as the one retrieved by getInstanceId().
   */
  @Override
  public void onNewToken(String token) {
    Intent intent = new Intent(ACTION_TOKEN);
    intent.putExtra(EXTRA_TOKEN, token);
    LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
  }

  /**
   * Setup the background isolate that would allow background messages to be handled on the Dart
   * side. Called either by the plugin when the app is starting up or when the app receives a
   * message while it is inactive.
   *
   * @param context Registrar or FirebaseMessagingService context.
   * @param callbackHandle Handle used to retrieve the Dart function that sets up background
   *     handling on the dart side.
   */
  public static void startBackgroundIsolate(Context context, long callbackHandle) {
    FlutterMain.ensureInitializationComplete(context, null);
    String appBundlePath = FlutterMain.findAppBundlePath();
    FlutterCallbackInformation flutterCallback =
            FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);
    if (flutterCallback == null) {
      Log.e(TAG, "Fatal: failed to find callback");
      return;
    }

    // Note that we're passing `true` as the second argument to our
    // FlutterNativeView constructor. This specifies the FlutterNativeView
    // as a background view and does not create a drawing surface.
    backgroundFlutterView = new FlutterNativeView(context, true);
    if (appBundlePath != null && !isIsolateRunning.get()) {
      if (pluginRegistrantCallback == null) {
        throw new RuntimeException("PluginRegistrantCallback is not set.");
      }
      FlutterRunArguments args = new FlutterRunArguments();
      args.bundlePath = appBundlePath;
      args.entrypoint = flutterCallback.callbackName;
      args.libraryPath = flutterCallback.callbackLibraryPath;
      backgroundFlutterView.runFromBundle(args);
      pluginRegistrantCallback.registerWith(backgroundFlutterView.getPluginRegistry());
    }
  }

  /**
   * Acknowledge that background message handling on the Dart side is ready. This is called by the
   * Dart side once all background initialization is complete via `FcmDartService#initialized`.
   */
  public static void onInitialized() {
    isIsolateRunning.set(true);
    synchronized (backgroundMessageQueue) {
      // Handle all the messages received before the Dart isolate was
      // initialized, then clear the queue.
      Iterator<RemoteMessage> i = backgroundMessageQueue.iterator();
      while (i.hasNext()) {
        executeDartCallbackInBackgroundIsolate(backgroundContext, i.next(), null);
      }
      backgroundMessageQueue.clear();
    }
  }

  /**
   * Set the method channel that is used for handling background messages. This method is only
   * called when the plugin registers.
   *
   * @param channel Background method channel.
   */
  public static void setBackgroundChannel(MethodChannel channel) {
    backgroundChannel = channel;
  }

  /**
   * Set the background message handle for future use. When background messages need to be handled
   * on the Dart side the handler must be retrieved in the background isolate to allow processing of
   * the incoming message. This method is called by the Dart side via `FcmDartService#start`.
   *
   * @param context Registrar context.
   * @param handle Handle representing the Dart side method that will handle background messages.
   */
  public static void setBackgroundMessageHandle(Context context, Long handle) {
    backgroundMessageHandle = handle;

    // Store background message handle in shared preferences so it can be retrieved
    // by other application instances.
    SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
    prefs.edit().putLong(BACKGROUND_MESSAGE_CALLBACK_HANDLE_KEY, handle).apply();
  }

  /**
   * Set the background message setup handle for future use. The Dart side of this plugin has a
   * method that sets up the background method channel. When ready to setup the background channel
   * the Dart side needs to be able to retrieve the setup method. This method is called by the Dart
   * side via `FcmDartService#start`.
   *
   * @param context Registrar context.
   * @param setupBackgroundHandle Handle representing the dart side method that will setup the
   *     background method channel.
   */
  public static void setBackgroundSetupHandle(Context context, long setupBackgroundHandle) {
    // Store background setup handle in shared preferences so it can be retrieved
    // by other application instances.
    SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
    prefs.edit().putLong(BACKGROUND_SETUP_CALLBACK_HANDLE_KEY, setupBackgroundHandle).apply();
  }

  /**
   * Retrieve the background message handle. When a background message is received and must be
   * processed on the dart side the handle representing the Dart side handle is retrieved so the
   * appropriate method can be called to process the message on the Dart side. This method is called
   * by FlutterFirebaseMessagingServcie either when a new background message is received or if
   * background messages were queued up while background message handling was being setup.
   *
   * @param context Application context.
   * @return Dart side background message handle.
   */
  public static Long getBackgroundMessageHandle(Context context) {
    return context
            .getSharedPreferences(SHARED_PREFERENCES_KEY, 0)
            .getLong(BACKGROUND_MESSAGE_CALLBACK_HANDLE_KEY, 0);
  }

  /**
   * Process the incoming message in the background isolate. This method is called only after
   * background method channel is setup, it is called by FlutterFirebaseMessagingServcie either when
   * a new background message is received or after background method channel setup for queued
   * messages received during setup.
   *
   * @param context Application or FirebaseMessagingService context.
   * @param remoteMessage Message received from Firebase Cloud Messaging.
   * @param latch If set will count down when the Dart side message processing is complete. Allowing
   *     any waiting threads to continue.
   */
  private static void executeDartCallbackInBackgroundIsolate(
          Context context, RemoteMessage remoteMessage, final CountDownLatch latch) {
    if (backgroundChannel == null) {
      throw new RuntimeException(
              "setBackgroundChannel was not called before messages came in, exiting.");
    }

    // If another thread is waiting, then wake that thread when the callback returns a result.
    MethodChannel.Result result = null;
    if (latch != null) {
      result = new LatchResult(latch).getResult();
    }

    Map<String, Object> args = new HashMap<>();
    Map<String, Object> messageData = new HashMap<>();
    if (backgroundMessageHandle == null) {
      backgroundMessageHandle = getBackgroundMessageHandle(context);
    }
    args.put("handle", backgroundMessageHandle);

    if (remoteMessage.getData() != null) {
      messageData.put("data", remoteMessage.getData());
    }
    if (remoteMessage.getNotification() != null) {
      messageData.put("notification", remoteMessage.getNotification());
    }

    args.put("message", messageData);

    backgroundChannel.invokeMethod("handleBackgroundMessage", args, result);
  }

  /**
   * Set the registrant callback. This is called by the app's Application class if background
   * message handling is enabled.
   *
   * @param callback Application class which implements PluginRegistrantCallback.
   */
  public static void setPluginRegistrant(PluginRegistry.PluginRegistrantCallback callback) {
    pluginRegistrantCallback = callback;
  }

  /**
   * Identify if the application is currently in a state where user interaction is possible. This
   * method is only called by FlutterFirebaseMessagingService when a message is received to
   * determine how the incoming message should be handled.
   *
   * @param context FlutterFirebaseMessagingService context.
   * @return True if the application is currently in a state where user interaction is possible,
   *     false otherwise.
   */
  // TODO(kroikie): Find a better way to determine application state.
  private static boolean isApplicationForeground(Context context) {
    KeyguardManager keyguardManager =
            (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

    if (keyguardManager.isKeyguardLocked()) {
      return false;
    }
    int myPid = Process.myPid();

    ActivityManager activityManager =
            (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

    List<ActivityManager.RunningAppProcessInfo> list;

    if ((list = activityManager.getRunningAppProcesses()) != null) {
      for (ActivityManager.RunningAppProcessInfo aList : list) {
        ActivityManager.RunningAppProcessInfo info;
        if ((info = aList).pid == myPid) {
          return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        }
      }
    }
    return false;
  }
}
