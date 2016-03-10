/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.music;

import android.app.Activity;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Toast;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.io.IOException;

/**
 * Dialog that comes up in response to various music-related VIEW intents.
 */
public class AudioPreview extends Activity implements OnPreparedListener, OnErrorListener, OnCompletionListener
{
    private final static String TAG = "AudioPreview";
    private PreviewPlayer mPlayer;
    private TextView mTextLine1;
    private TextView mTextLine2;
    private TextView mLoadingText;
    private SeekBar mSeekBar;
    private Handler mProgressRefresher;
    private boolean mSeeking = false;
    private int mDuration;
    private Uri mUri;
    private long mMediaId = -1;
    private static final int OPEN_IN_MUSIC = 1;
    private AudioManager mAudioManager;
    private boolean mPausedByTransientLossOfFocus;
    /* SPRD: add @{ */
    private boolean mShouldPlayAfterPhone = false;
    private long mLastSeekEventTime;
    private int mPosOverride = -1;
    private boolean mIsReceiverRegistered = false;
    private boolean mIsReceiverShutdown = false;
    private boolean mIsPlaybackFailedShowed = false;

    private TelephonyManager mTelephonyManager;
    private PhoneState mPhoneState;
    /* @} */

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        /* SPRD 402154 In case of reproduce,add log @{ */
        Log.i(TAG, "onCreate");
        /* @} */
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        mUri = intent.getData();
        if (mUri == null) {
            finish();
            return;
        }

        //SPRD :add
        sprdOncreate();
        String scheme = mUri.getScheme();
        
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        /* SPRD 402154 press blank space will not dismiss @{ */
        setFinishOnTouchOutside(false);
        /* @} */
        setContentView(R.layout.audiopreview);

        mTextLine1 = (TextView) findViewById(R.id.line1);
        mTextLine2 = (TextView) findViewById(R.id.line2);
        mLoadingText = (TextView) findViewById(R.id.loading);
        if (scheme.equals("http")) {
            String msg = getString(R.string.streamloadingtext, mUri.getHost());
            mLoadingText.setText(msg);
        } else {
            mLoadingText.setVisibility(View.GONE);
        }
        mSeekBar = (SeekBar) findViewById(R.id.progress);
        mProgressRefresher = new Handler();
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        PreviewPlayer player = (PreviewPlayer) getLastNonConfigurationInstance();
        if (player == null) {
            mPlayer = new PreviewPlayer();
            Log.i(TAG, "onCreate()      mPlayer = " + mPlayer);
            mPlayer.setActivity(this);
            try {
                mPlayer.setDataSourceAndPrepare(mUri);
            } catch (Exception ex) {
                // catch generic Exception, since we may be called with a media
                // content URI, another content provider's URI, a file URI,
                // an http URI, and there are different exceptions associated
                // with failure to open each of those.
                Log.d(TAG, "Failed to open file: " + ex);
                Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show();
                /* SPRD: add bugfix 290480 @{ */
                mIsPlaybackFailedShowed = true;
                /* @} */
                finish();
                return;
            }
        } else {
            mPlayer = player;
            mPlayer.setActivity(this);
            Log.i(TAG, "onCreate      mPlayer = " + mPlayer);
            if (mPlayer.isPrepared()) {
                // SPRD： add
                requestAudiFocus();
                showPostPrepareUI();
            }
        }

        AsyncQueryHandler mAsyncQueryHandler = new AsyncQueryHandler(getContentResolver()) {
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                if (cursor != null && cursor.moveToFirst()) {

                    int titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    int displaynameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);

                    if (idIdx >=0) {
                        mMediaId = cursor.getLong(idIdx);
                    }
                    
                    if (titleIdx >= 0) {
                        String title = cursor.getString(titleIdx);
                        mTextLine1.setText(title);
                        if (artistIdx >= 0) {
                            String artist = cursor.getString(artistIdx);
                            mTextLine2.setText(artist);
                        }
                    } else if (displaynameIdx >= 0) {
                        String name = cursor.getString(displaynameIdx);
                        mTextLine1.setText(name);
                    } else {
                        // Couldn't find anything to display, what to do now?
                        Log.w(TAG, "Cursor had no names for us");
                    }
                } else {
                    Log.w(TAG, "empty cursor");
                }

                if (cursor != null) {
                    cursor.close();
                }
                setNames();
            }
        };

        if (scheme.equals(ContentResolver.SCHEME_CONTENT)) {
            if (mUri.getAuthority() == MediaStore.AUTHORITY) {
                // try to get title and artist from the media content provider
                mAsyncQueryHandler.startQuery(0, null, mUri, new String [] {
                        MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST},
                        null, null, null);
            } else {
                // Try to get the display name from another content provider.
                // Don't specifically ask for the display name though, since the
                // provider might not actually support that column.
                mAsyncQueryHandler.startQuery(0, null, mUri, null, null, null, null);
            }
        } else if (scheme.equals("file")) {
            // check if this file is in the media database (clicking on a download
            // in the download manager might follow this path
            String path = mUri.getPath();
            mAsyncQueryHandler.startQuery(0, null,  MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String [] {MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST},
                    MediaStore.Audio.Media.DATA + "=?", new String [] {path}, null);
        } else {
            // We can't get metadata from the file/stream itself yet, because
            // that API is hidden, so instead we display the URI being played
            if (mPlayer.isPrepared()) {
                setNames();
            }
        }
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        PreviewPlayer player = mPlayer;
        mPlayer = null;
        Log.i(TAG, "onRetainNonConfigurationInstance()");
        return player;
    }
    /* SPRD 402154 In case of reproduce,add log @{ */
    @Override
    public void onPause() {
        Log.i(TAG, "onPause");
        super.onPause();
    }
    /* @} */
    @Override
    public void onDestroy() {
        /* SPRD 402154 In case of reproduce,add log @{ */
        Log.i(TAG, "onDestroy");
        /* @} */
        stopPlayback();
        //SPRD :add
        sprdOndestroy();
        super.onDestroy();
    }

    private void stopPlayback() {
        if (mProgressRefresher != null) {
            mProgressRefresher.removeCallbacksAndMessages(null);
        }
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
            mAudioManager.abandonAudioFocus(mAudioFocusListener);
            Log.i(TAG,"stopPlayback()");
        }
    }

    @Override
    public void onUserLeaveHint() {
        stopPlayback();
        finish();
        super.onUserLeaveHint();
    }

    public void onPrepared(MediaPlayer mp) {
        if (isFinishing()) return;
        mPlayer = (PreviewPlayer) mp;
        setNames();
        Log.i(TAG,"onPrepared()    mPlayer = " + mPlayer);
        // SPRD： add
        requestAudiFocus();
        /* SPRD: add for mPlayer NullPointerException@{ */
        if (mPlayer != null) {
            mPlayer.start();
        }
        /* @} */
        showPostPrepareUI();
    }

    private void showPostPrepareUI() {
        ProgressBar pb = (ProgressBar) findViewById(R.id.spinner);
        pb.setVisibility(View.GONE);
        mDuration = mPlayer.getDuration();
        if (mDuration != 0) {
            mSeekBar.setMax(mDuration);
            mSeekBar.setVisibility(View.VISIBLE);
        }
        mSeekBar.setOnSeekBarChangeListener(mSeekListener);
        mLoadingText.setVisibility(View.GONE);
        View v = findViewById(R.id.titleandbuttons);
        v.setVisibility(View.VISIBLE);
        // SPRD :remove add to the function of requestAudiFocus()
        // mAudioManager.requestAudioFocus(mAudioFocusListener,AudioManager.STREAM_MUSIC,
        // AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        mProgressRefresher.postDelayed(new ProgressRefresher(), 200);
        updatePlayPause();
    }
    
    private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            if (mPlayer == null) {
                // this activity has handed its MediaPlayer off to the next activity
                // (e.g. portrait/landscape switch) and should abandon its focus
                mAudioManager.abandonAudioFocus(this);
                return;
            }
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    mPausedByTransientLossOfFocus = false;
                    mPlayer.pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (mPlayer.isPlaying()) {
                        mPausedByTransientLossOfFocus = true;
                        mPlayer.pause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (mPausedByTransientLossOfFocus) {
                        mPausedByTransientLossOfFocus = false;
                        start();
                    }
                    break;
            }
            updatePlayPause();
        }
    };
    
    private void start() {
        /* SPRD: check request result for bug 325674*/
        //mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
        //        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        requestAudiFocus();
        /* @} */
        Log.i(TAG, "start()   mPlayer = " + mPlayer);
        /*SPRD : Add 20150311 of bug 413159 ,check whether the member variable mPlayer is null.@{*/
        if (mPlayer != null) {
            mPlayer.start();
        }
        /*@}*/
        mProgressRefresher.postDelayed(new ProgressRefresher(), 200);
    }
    
    public void setNames() {
        Log.i(TAG, "mUri.toString() = " + mUri.toString());
        Log.i(TAG, "mUri.getLastPathSegment() = " + mUri.getLastPathSegment());
        if (TextUtils.isEmpty(mTextLine1.getText())) {
            /* SPRD: update for bug 275930 @{ */
            mTextLine1.setVisibility(View.VISIBLE);
            if (mUri.toString().startsWith("content://mms/part")) {
                if (getIntent().getExtras() != null) {
                    String filename = getIntent().getExtras().getString("filename");
                    if (filename != null && !filename.isEmpty()) {
                        mTextLine1.setText(filename);
                    } else {
                        mTextLine1.setVisibility(View.GONE);
                    }
                }
            } else {
                mTextLine1.setText(mUri.getLastPathSegment());
            }
            /* @} */
        }
        if (TextUtils.isEmpty(mTextLine2.getText())) {
            mTextLine2.setVisibility(View.GONE);
        } else {
            mTextLine2.setVisibility(View.VISIBLE);
        }
    }

    class ProgressRefresher implements Runnable {

        public void run() {
            if (mPlayer != null && !mSeeking && mDuration != 0) {
                int progress = mPlayer.getCurrentPosition() / mDuration;
                mSeekBar.setProgress(mPlayer.getCurrentPosition());
            }
            mProgressRefresher.removeCallbacksAndMessages(null);
            mProgressRefresher.postDelayed(new ProgressRefresher(), 200);
        }
    }
    
    private void updatePlayPause() {
        ImageButton b = (ImageButton) findViewById(R.id.playpause);
        if (b != null) {
            /* SPRD 442186 @{*/
            //if (mPlayer.isPlaying()) {
            if (mPlayer != null && mPlayer.isPlaying()) {
            /* @} */
                b.setImageResource(R.drawable.btn_playback_ic_pause_small);
            } else {
                b.setImageResource(R.drawable.btn_playback_ic_play_small);
                mProgressRefresher.removeCallbacksAndMessages(null);
            }
        }
    }

    private OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
        public void onStartTrackingTouch(SeekBar bar) {
            mSeeking = true;
        }
        public void onProgressChanged(SeekBar bar, int progress, boolean fromuser) {
            if (!fromuser) {
                return;
            }
            // Protection for case of simultaneously tapping on seek bar and exit
            if (mPlayer == null) {
                return;
            }
            // SPRD: check if the mPlayer is not a null reference
            if(!mSeeking && (mPlayer != null) )
            mPlayer.seekTo(progress);
        }
        public void onStopTrackingTouch(SeekBar bar) {
            mSeeking = false;
            // SPRD: check if the mPlayer is not a null reference
            if ((mPlayer != null)) {
                mPlayer.seekTo(bar.getProgress());
            }
        }
    };

    public boolean onError(MediaPlayer mp, int what, int extra) {
        /* SPRD: add bugfix 290480 @{ */
        if (!mIsPlaybackFailedShowed) {
            Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show();
            mIsPlaybackFailedShowed = true;
        }
        /* @} */
        finish();
        return true;
    }

    public void onCompletion(MediaPlayer mp) {
        mSeekBar.setProgress(mDuration);
        //SPRD modify
        //updatePlayPause();
        updatePausebutton();
    }

    public void playPauseClicked(View v) {
        // Protection for case of simultaneously tapping on play/pause and exit
        //SPRD: add
        getCallState();
        if (mPlayer == null) {
            return;
        }
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
        } else {
            start();
        }
        updatePlayPause();
    }

    /* SPRD: remove the menu for bug 390894 @{
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        // TODO: if mMediaId != -1, then the playing file has an entry in the media
        // database, and we could open it in the full music app instead.
        // Ideally, we would hand off the currently running mediaplayer
        // to the music UI, which can probably be done via a public static
        menu.add(0, OPEN_IN_MUSIC, 0, "open in music");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(OPEN_IN_MUSIC);
        if (mMediaId >= 0) {
            item.setVisible(true);
            return true;
        }
        item.setVisible(false);
        return false;
    }
    @} */
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        /* SPRD: fix bug 329890 @{ */
        // filter repeat key code
        if (event.getRepeatCount() > 0) {
            return isMediaKey(keyCode);
        }
        /* SPRD: fix bug 329890 @} */

        switch (keyCode) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                mKeyDownEventTime = event.getEventTime();
                // SPRD: remove Android Original Code for bug 329890
                /* if (mPlayer.isPlaying()) {
                    mPlayer.pause();
                } else {
                    start();
                }
                updatePlayPause();*/
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                start();
                updatePlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (mPlayer.isPlaying()) {
                    mPlayer.pause();
                }
                updatePlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_BACK:
                stopPlayback();
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /*
     * Wrapper class to help with handing off the MediaPlayer to the next instance
     * of the activity in case of orientation change, without losing any state.
     */
    private static class PreviewPlayer extends MediaPlayer implements OnPreparedListener {
        AudioPreview mActivity;
        boolean mIsPrepared = false;

        public void setActivity(AudioPreview activity) {
            mActivity = activity;
            setOnPreparedListener(this);
            setOnErrorListener(mActivity);
            setOnCompletionListener(mActivity);
        }

        public void setDataSourceAndPrepare(Uri uri) throws IllegalArgumentException,
                        SecurityException, IllegalStateException, IOException {
            setDataSource(mActivity,uri);
            prepareAsync();
        }

        /* (non-Javadoc)
         * @see android.media.MediaPlayer.OnPreparedListener#onPrepared(android.media.MediaPlayer)
         */
        @Override
        public void onPrepared(MediaPlayer mp) {
            mIsPrepared = true;
            mActivity.onPrepared(mp);
        }

        boolean isPrepared() {
            return mIsPrepared;
        }
    }

    /* SPRD: add @{ */
    private BroadcastReceiver mAudioReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (mPlayer != null && mPlayer.isPlaying()) {
                mPlayer.pause();
                updatePlayPause();
            }
        }
    };

    // system shutdown receiver
    private BroadcastReceiver mSystemShutdownReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            finish();
        };
    };

    /* SPRD: add for mPlayer NullPointerException @{ */
    @Override
    protected void onResume() {
        super.onResume();
        if (mPlayer == null) {
            Log.d(TAG, "onResume: mPlayer == null");
            finish();
        }
    }

    private void sprdOncreate() {
        getCallState();
        mPhoneState = new PhoneState();
        mTelephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        mTelephonyManager.listen(mPhoneState, PhoneStateListener.LISTEN_CALL_STATE);

        IntentFilter shutdownFilter = new IntentFilter();
        shutdownFilter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(mSystemShutdownReceiver, shutdownFilter);
        mIsReceiverShutdown = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(mAudioReceiver, filter);
        mIsReceiverRegistered = true;
    }

    private void sprdOndestroy() {
        if (mIsReceiverRegistered) {
            unregisterReceiver(mAudioReceiver);
            mIsReceiverRegistered = false;
        }
        if (mIsReceiverShutdown) {
            unregisterReceiver(mSystemShutdownReceiver);
            mIsReceiverShutdown = false;
        }
        if (mTelephonyManager != null) {
            mTelephonyManager.listen(mPhoneState, PhoneStateListener.LISTEN_NONE);
            mTelephonyManager = null;
        }
    }

    private void getCallState() {
        TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
            Toast.makeText(this, R.string.no_play, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
    }

    private class PhoneState extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            // TODO Auto-generated method stub
            int volume = mAudioManager.getStreamVolume(AudioManager.STREAM_RING);
            switch (state) {
            // no phone
                case TelephonyManager.CALL_STATE_IDLE:
                    if (mShouldPlayAfterPhone && volume == 0 && mPlayer != null
                            && !mPlayer.isPlaying()) {
                        mShouldPlayAfterPhone = false;
                        start();
                    }
                    break;
                case TelephonyManager.CALL_STATE_RINGING:
                    if (!mShouldPlayAfterPhone && volume == 0 && mPlayer != null
                            && mPlayer.isPlaying()) {
                        mShouldPlayAfterPhone = true;
                        mPlayer.pause();
                    }

                    break;
                default:
                    break;

            }
            super.onCallStateChanged(state, incomingNumber);
        }
    }

    private void requestAudiFocus() {
        int audioFocus = mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        if(audioFocus == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            return;
        }
    }

    private void updatePausebutton() {
        if (mDuration != mSeekBar.getMax()) {
            mSeekBar.setProgress(mSeekBar.getMax());
        }
        ImageButton b = (ImageButton) findViewById(R.id.playpause);
        b.setImageResource(R.drawable.btn_playback_ic_play_small);
        mProgressRefresher.removeCallbacksAndMessages(null);
    }

    /* SPRD: fix bug 329890 @{ */
    private long mKeyDownEventTime = 0;
    private static final long LONG_PRESS_TIMEOUT = 500;
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.getEventTime() - mKeyDownEventTime <= LONG_PRESS_TIMEOUT) {
                if (mPlayer != null && mPlayer.isPlaying()) {
                    mPlayer.pause();
                } else {
                    TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
                    if (tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                        Toast.makeText(this, R.string.no_play, Toast.LENGTH_LONG).show();
                        return true;
                    }
                    start();
                }
                updatePlayPause();
            }
            mKeyDownEventTime = 0;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static boolean isMediaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND;
    }
    /* SPRD: fix bug 329890 @} */
    /* @} */

}
