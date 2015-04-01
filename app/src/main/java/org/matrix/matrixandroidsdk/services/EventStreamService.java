package org.matrix.matrixandroidsdk.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.bingrules.BingRule;
import org.matrix.matrixandroidsdk.Matrix;
import org.matrix.matrixandroidsdk.R;
import org.matrix.matrixandroidsdk.ViewedRoomTracker;
import org.matrix.matrixandroidsdk.activity.HomeActivity;
import org.matrix.matrixandroidsdk.activity.LockScreenActivity;
import org.matrix.matrixandroidsdk.activity.RoomActivity;

/**
 * A foreground service in charge of controlling whether the event stream is running or not.
 */
public class EventStreamService extends Service {
    private static final String CAR_VOICE_REPLY_KEY = "org.matrix.matrixandroidsdk.CAR_VOICE_REPLY_KEY";

    public static enum StreamAction {
        UNKNOWN,
        STOP,
        START,
        PAUSE,
        RESUME
    }
    public static final String EXTRA_STREAM_ACTION = "org.matrix.matrixandroidsdk.services.EventStreamService.EXTRA_STREAM_ACTION";
    public static final String QUICK_LAUNCH_ACTION = "org.matrix.matrixandroidsdk.services.EventStreamService.QUICK_LAUNCH_ACTION";
    public static final String TAP_TO_VIEW_ACTION = "org.matrix.matrixandroidsdk.services.EventStreamService.TAP_TO_VIEW_ACTION";

    private static final String LOG_TAG = "EventStreamService";
    private static final int NOTIFICATION_ID = 42;
    private static final int MSG_NOTIFICATION_ID = 43;

    private MXSession mSession;
    private StreamAction mState = StreamAction.UNKNOWN;

    private String mNotificationRoomId = null;

    private static EventStreamService mActiveEventStreamService = null;


    /**
     * Cancel the push notifications for a dedicated roomId.
     * If the roomId is null, cancel all the push notification.
     * @param roomId
     */
    public static void cancelNotificationsForRoomId(String roomId) {
        if (null != mActiveEventStreamService) {
            mActiveEventStreamService.cancelNotifications(roomId);
        }
    }

    private void cancelNotifications(String roomId) {
        boolean cancelNotifications = true;

        // clear only if the notification has been pushed for a dedicated RoomId
        if (null != roomId) {
            cancelNotifications = (null != mNotificationRoomId) && (mNotificationRoomId.equals(roomId));
        }

        // cancel the notifications
        if (cancelNotifications) {
            NotificationManager nm = (NotificationManager) EventStreamService.this.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancelAll();
        }
    }

    private MXEventListener mListener = new MXEventListener() {

        @Override
        public void onBingEvent(Event event, RoomState roomState, BingRule bingRule) {

            final String roomId = event.roomId;

            // Just don't bing for the room the user's currently in
            if ((roomId != null) && event.roomId.equals(ViewedRoomTracker.getInstance().getViewedRoomId())) {
                return;
            }

            String senderID = event.userId;
            // FIXME: Support event contents with no body
            if (!event.content.has("body")) {
                return;
            }

            Room room = mSession.getDataHandler().getRoom(roomId);

            // invalid room ?
            if(null == room) {
                return;
            }

            RoomMember member = room.getMember(senderID);

            // invalid member
            if (null == member) {
                return;
            }

            mNotificationRoomId = roomId;

            final String body = event.content.getAsJsonPrimitive("body").getAsString();

            Notification n = buildMessageNotification(member.getName(), body, event.roomId);
            NotificationManager nm = (NotificationManager) EventStreamService.this.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancelAll();

            if (bingRule.shouldPlaySound()) {
                n.defaults |= Notification.DEFAULT_SOUND;
            }

            Log.w(LOG_TAG, "onMessageEvent >>>> " + event);
            nm.notify(MSG_NOTIFICATION_ID, n);
        }

        @Override
        public void onResendEvent(Event event) {
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        StreamAction action = StreamAction.values()[intent.getIntExtra(EXTRA_STREAM_ACTION, StreamAction.UNKNOWN.ordinal())];
        Log.d(LOG_TAG, "onStartCommand >> "+action);
        switch (action) {
            case START:
            case RESUME:
                start();
                break;
            case STOP:
                stop();
                break;
            case PAUSE:
                pause();
                break;
            default:
                break;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void start() {
        if (mState == StreamAction.START) {
            Log.w(LOG_TAG, "Already started.");
            return;
        }
        else if (mState == StreamAction.PAUSE) {
            Log.i(LOG_TAG, "Resuming active stream.");
            resume();
            return;
        }
        if (mSession == null) {
            mSession = Matrix.getInstance(getApplicationContext()).getDefaultSession();
            if (mSession == null) {
                Log.e(LOG_TAG, "No valid MXSession.");
                return;
            }
        }

        mActiveEventStreamService = this;

        mSession.getDataHandler().addListener(mListener);
        mSession.startEventStream();
        startWithNotification();
    }

    private void stop() {
        stopForeground(true);
        if (mSession != null) {
            mSession.stopEventStream();
            mSession.getDataHandler().removeListener(mListener);
        }
        mSession = null;
        mState = StreamAction.STOP;

        mActiveEventStreamService = null;
    }

    private void pause() {
        stopForeground(true);
        if (mSession != null) {
            mSession.pauseEventStream();
        }
        mState = StreamAction.PAUSE;
    }

    private void resume() {
        if (mSession != null) {
            mSession.resumeEventStream();
        }
        startWithNotification();
    }

    private void startWithNotification() {
        // remove the listening for events notification
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        mState = StreamAction.START;
    }

    private Notification buildMessageNotification(String from, String body, String roomId) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setWhen(System.currentTimeMillis());
        builder.setContentTitle(from);
        builder.setContentText(body);
        builder.setAutoCancel(true);
        builder.setSmallIcon(R.drawable.ic_menu_small_matrix);
        builder.setTicker(from + ":" + body);

        {
            // Build the pending intent for when the notification is clicked
            Intent roomIntent = new Intent(this, RoomActivity.class);
            roomIntent.putExtra(RoomActivity.EXTRA_ROOM_ID, roomId);
            // Recreate the back stack
            TaskStackBuilder stackBuilder = TaskStackBuilder.create(this)
                    .addParentStack(RoomActivity.class)
                    .addNextIntent(roomIntent);

            builder.setContentIntent(stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT));
        }

        // display the message with more than 1 lines when the device supports it
        NotificationCompat.BigTextStyle textStyle = new NotificationCompat.BigTextStyle();
        textStyle.bigText(from + ":" + body);
        builder.setStyle(textStyle);

        // do not offer to quick respond if the user did not dismiss the previous one
        if (!LockScreenActivity.isDisplayingALockScreenActivity()) {
            // offer to type a quick answer (i.e. without launching the application)
            Intent quickReplyIntent = new Intent(this, LockScreenActivity.class);
            quickReplyIntent.putExtra(LockScreenActivity.EXTRA_ROOM_ID, roomId);
            quickReplyIntent.putExtra(LockScreenActivity.EXTRA_SENDER_NAME, from);
            quickReplyIntent.putExtra(LockScreenActivity.EXTRA_MESSAGE_BODY, body);
            // the action must be unique else the parameters are ignored
            quickReplyIntent.setAction(QUICK_LAUNCH_ACTION + ((int) (System.currentTimeMillis())));
            PendingIntent pIntent = PendingIntent.getActivity(this, 0, quickReplyIntent, 0);
            builder.addAction(R.drawable.ic_menu_edit, getString(R.string.action_quick_reply), pIntent);

            // Build the pending intent for when the notification is clicked
            Intent roomIntentTap = new Intent(this, RoomActivity.class);
            roomIntentTap.putExtra(RoomActivity.EXTRA_ROOM_ID, roomId);
            // the action must be unique else the parameters are ignored
            roomIntentTap.setAction(TAP_TO_VIEW_ACTION + ((int) (System.currentTimeMillis())));
            // Recreate the back stack
            TaskStackBuilder stackBuildertap = TaskStackBuilder.create(this)
                    .addParentStack(RoomActivity.class)
                    .addNextIntent(roomIntentTap);
            builder.addAction(R.drawable.ic_menu_start_conversation, getString(R.string.action_open), stackBuildertap.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT));
        }

        // send message to car if connected
        {
            int carConversationId = roomId.hashCode();
            Intent msgHeardIntent = new Intent()
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .setAction("org.matrix.matrixandroidsdk.ACTION_MESSAGE_HEARD")
                    .putExtra("conversation_id", carConversationId);

            PendingIntent msgHeardPendingIntent =
                    PendingIntent.getBroadcast(getApplicationContext(),
                            carConversationId,
                            msgHeardIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT);

            Intent msgReplyIntent = new Intent()
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .setAction("org.matrix.matrixandroidsdk.ACTION_MESSAGE_REPLY")
                    .putExtra("conversation_id", carConversationId);

            PendingIntent msgReplyPendingIntent = PendingIntent.getBroadcast(
                    getApplicationContext(),
                    carConversationId,
                    msgReplyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            // Build a RemoteInput for receiving voice input in a Car Notification
            android.support.v4.app.RemoteInput remoteInput = new android.support.v4.app.RemoteInput.Builder(CAR_VOICE_REPLY_KEY)
                    .setLabel(getApplicationContext().getString(R.string.action_quick_reply))
                    .build();

            // Create an unread conversation object to organize a group of messages
            // from a particular sender.
            NotificationCompat.CarExtender.UnreadConversation.Builder unreadConvBuilder =
                    new NotificationCompat.CarExtender.UnreadConversation.Builder(roomId)
                            .setReadPendingIntent(msgHeardPendingIntent)
                            .setReplyAction(msgReplyPendingIntent, remoteInput);

            unreadConvBuilder.addMessage(body)
                    .setLatestTimestamp(System.currentTimeMillis());
            builder.extend(new NotificationCompat.CarExtender()
                    .setUnreadConversation(unreadConvBuilder.build()));
        }
        Notification n = builder.build();
        n.flags |= Notification.FLAG_SHOW_LIGHTS;
        n.defaults |= Notification.DEFAULT_LIGHTS;

        return n;
    }

    private Notification buildNotification() {
        Notification notification = new Notification(
                R.drawable.ic_menu_small_matrix,
                "Matrix",
                System.currentTimeMillis()
        );

        // go to the home screen if this is clicked.
        Intent i = new Intent(this, HomeActivity.class);

        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|
                Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);

        notification.setLatestEventInfo(this, getString(R.string.app_name),
                "Listening for events",
                pi);
        notification.flags |= Notification.FLAG_NO_CLEAR;
        return notification;
    }
}
