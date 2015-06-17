package com.kaltura.playersdk.players;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.FrameworkSampleSource;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.VideoSurfaceView;
import com.kaltura.playersdk.types.PlayerStates;

import java.util.jar.Attributes;

/**
 * Created by nissopa on 6/15/15.
 */
public class KPlayer extends FrameLayout implements KPlayerController.KPlayer, ExoPlayer.Listener, MediaCodecVideoTrackRenderer.EventListener, SurfaceHolder.Callback {

    private int NUM_OF_RENFERERS = 2;
    private static final String TAG = KalturaPlayer.class.getSimpleName();
    public static int PLAYHEAD_UPDATE_INTERVAL = 200;
    public static int BUFFER_WAIT_INTERVAL = 200;
    private static final int BUFFER_COUNTER_MAX = 5;
    private volatile ExoPlayer mExoPlayer;
    private String mVideoUrl = null;
    private int mBufferWaitCounter = 0;

    protected KPlayerListener listener;
    protected String mPlayerSource;
    protected boolean mIsReady = false;
    protected int mStartPos = 0;
    protected int mPrevProgress = 0;
    protected VideoSurfaceView mSurfaceView;

    protected boolean mPrepared = false;
    protected boolean mSeeking = false;
    protected boolean mShouldResumePlayback = false;
    protected RelativeLayout mBackgroundRL;

    static protected String PlayKey = "play";
    static protected String PauseKey = "pause";
    static protected String DurationChangedKey = "durationchange";
    static protected String LoadedMetaDataKey = "loadedmetadata";
    static protected String TimeUpdateKey = "timeupdate";
    static protected String ProgressKey = "progress";
    static protected String EndedKey = "ended";
    static protected String SeekedKey = "seeked";
    static protected String CanPlayKey = "canplay";
    static protected String PostrollEndedKey = "postEnded";

    private Handler mHandler = new Handler(Looper.getMainLooper());

    public KPlayer(Context context) {
        super(context);
        addSurface();
    }

    public KPlayer(Context context, AttributeSet attributes) {
        super(context, attributes);
        addSurface();
    }

    @Override
    public void setPlayerListener(KPlayerListener listener) {
        this.listener = listener;
    }

    @Override
    public KPlayerListener getPlayerListener() {
        return this.listener;
    }

    @Override
    public void setPlayerSource(String playerSource) {
        if ( !playerSource.equals(mVideoUrl) ) {
            removePlayer();
            mVideoUrl = playerSource;
//            mListenerExecutor.executeOnPlayheadUpdated(0);
            preparePlayer();
        }
    }

    @Override
    public String getPlayerSource() {
        return this.mPlayerSource;
    }

    @Override
    public void setCurrentPlaybackTime(float currentPlaybackTime) {
        if ( mIsReady ) {
            mSeeking = true;
//            mListenerExecutor.executeOnStateChanged(PlayerStates.SEEKING);
            mExoPlayer.seekTo( (int)(currentPlaybackTime * 1000) );
        }
    }

    @Override
    public float getCurrentPlaybackTime() {
        return (float)(mExoPlayer.getCurrentPosition() / 1000);
    }


    @Override
    public float getDuration() {
        return (float)(mExoPlayer.getDuration() / 1000);
    }

    @Override
    public void initWithParentView(RelativeLayout parentView) {
        ViewGroup.LayoutParams currLP = parentView.getLayoutParams();

        // Add background view
        mBackgroundRL = new RelativeLayout(parentView.getContext());
        mBackgroundRL.setBackgroundColor(Color.BLACK);
        parentView.addView(mBackgroundRL, currLP);

        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(currLP.width, currLP.height);
        parentView.addView(this, lp);

    }

    @Override
    public void play() {
        if ( !mPrepared ) {
            preparePlayer();
        }
        if ( !this.isPlaying() ) {
            int a = mExoPlayer.getPlaybackState();
            if(a == ExoPlayer.STATE_BUFFERING){
                startWaitingLoop();
                return;
            }
            setPlayWhenReady(true);
            if ( mStartPos != 0 ) {
                mExoPlayer.seekTo( mStartPos );
                mStartPos = 0;
            }

            mHandler.removeCallbacks(null);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if ( mExoPlayer != null ) {
                        try {
                            int position = mExoPlayer.getCurrentPosition();
                            if ( position != 0 && isPlaying() ) {
//                                mListenerExecutor.executeOnPlayheadUpdated(position);
//                                KPlayer.this.setCurrentPlaybackTime((float)position / 1000);
                                KPlayer.this.listener.eventWithValue(KPlayer.this, KPlayer.TimeUpdateKey, Float.toString((float)position / 1000));
                            }
                        } catch(IllegalStateException e){
                            e.printStackTrace();
                        }

                        mHandler.postDelayed(this, PLAYHEAD_UPDATE_INTERVAL);
                    }

                }
            });

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mExoPlayer != null) {
                        try {
                            int progress = mExoPlayer.getBufferedPercentage();
                            if (progress != 0 && mPrevProgress != progress) {
//                                mListenerExecutor.executeOnProgressUpdate(progress);
                                mPrevProgress = progress;
                            }

                        } catch (IllegalStateException e) {
                            e.printStackTrace();
                        }

                        mHandler.postDelayed(this, PLAYHEAD_UPDATE_INTERVAL);
                    }
                }
            });

//            mListenerExecutor.executeOnStateChanged(PlayerStates.PLAY);
            this.listener.eventWithValue(this, KPlayer.PlayKey, null);
        }
    }

    @Override
    public void pause() {
        if ( this.isPlaying() && mExoPlayer != null ) {
            setPlayWhenReady(false);
//            mListenerExecutor.executeOnStateChanged(PlayerStates.PAUSE);
            this.listener.eventWithValue(this, KPlayer.PauseKey, null);
        }
    }

    @Override
    public void changeSubtitleLanguage(String languageCode) {

    }

    @Override
    public void removePlayer() {
        updateStopState();
        if (mExoPlayer != null) {
            Log.d(TAG, "Releasing ExoPlayer");
            mExoPlayer.release();
            Log.d(TAG, "ExoPlayer released");
            mExoPlayer = null;
        }
        mPrepared = false;
        mIsReady = false;
    }

    @Override
    public void setDRMKey(String drmKey) {

    }

    @Override
    public boolean isKPlayer() {
        return false;
    }
    // Exo Player listener events
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        PlayerStates state = null;

        Log.d(TAG, "PlayerStateChanged: " + playbackState);

        switch ( playbackState ) {
            case ExoPlayer.STATE_PREPARING:
            case ExoPlayer.STATE_BUFFERING:
                state = PlayerStates.LOAD;
                break;
            case ExoPlayer.STATE_READY:
                if ( !mIsReady ) {
                    state = PlayerStates.START;
                    mIsReady = true;
                    this.listener.eventWithValue(this, KPlayer.DurationChangedKey, Float.toString(this.getDuration()));
                    this.listener.eventWithValue(this, KPlayer.LoadedMetaDataKey, "");
                    this.listener.eventWithValue(this, KPlayer.CanPlayKey, null);
                } else if ( mSeeking ) {
                    state = PlayerStates.SEEKED;
                    mSeeking = false;
                }
                break;
            case ExoPlayer.STATE_IDLE:
                if ( mSeeking ) {
                    state = PlayerStates.SEEKED;
                    mSeeking = false;
                }
                break;
            case ExoPlayer.STATE_ENDED:
                Log.d(TAG, "state ended");
                if (mExoPlayer != null) {
                    Log.d(TAG, "state ended: set play when ready false");
                    setPlayWhenReady(false);
                }
                if (mExoPlayer != null) {
                    Log.d(TAG, "state ended: seek to 0");
                    mExoPlayer.seekTo(0);
                }
                updateStopState();
                state = PlayerStates.END;
                pause();
                break;

        }

        if ( state != null ) {
//            mListenerExecutor.executeOnStateChanged(state);
        }
        //TODO: this if doesn't make a whole lot of sense
        //notify initial play
        if ( state == PlayerStates.START && playWhenReady ) {
//            mListenerExecutor.executeOnStateChanged(PlayerStates.PLAY);
//            this.listener.eventWithValue(this, KPlayer.PlayKey, null);
        }
    }

    @Override
    public void onPlayWhenReadyCommitted() {

    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {

    }


    // MediaCodecVideoTrackRenderer event listener
    @Override
    public void onDroppedFrames(int count, long elapsed) {

    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        mSurfaceView.setVideoWidthHeightRatio(height == 0 ? 1 : (float) width / height);
    }

    @Override
    public void onDrawnToSurface(Surface surface) {

    }

    @Override
    public void onDecoderInitializationError(MediaCodecTrackRenderer.DecoderInitializationException e) {

    }

    @Override
    public void onCryptoError(MediaCodec.CryptoException e) {

    }




    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }

    private void preparePlayer() {
        mPrepared = true;
        mPrevProgress = 0;

        mExoPlayer = ExoPlayer.Factory.newInstance(NUM_OF_RENFERERS);
        mExoPlayer.addListener(this);
        mExoPlayer.seekTo(0);

        SampleSource sampleSource = new FrameworkSampleSource(getContext(), Uri.parse(mVideoUrl), null, NUM_OF_RENFERERS);
        Handler handler = new Handler(Looper.getMainLooper());
        TrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(sampleSource, null, true, MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, handler, this, 50);
        TrackRenderer audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource);


        Surface surface = mSurfaceView.getHolder().getSurface();
        if (videoRenderer == null || surface == null || !surface.isValid()) {
            // We're not ready yet.
            return;
        }
        mExoPlayer.prepare(videoRenderer, audioRenderer);
        mExoPlayer.sendMessage(videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, surface);
    }

    private void addSurface() {
        mSurfaceView = new VideoSurfaceView( getContext() );
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
        this.addView(mSurfaceView, lp);
        mSurfaceView.getHolder().addCallback(this);
    }


    private void updateStopState() {
        if ( mHandler != null ) {
            Log.d(TAG, "remove handler callbacks");
            mHandler.removeCallbacks(null); //removes all runnables
        }

    }

    private void setPlayWhenReady(boolean shouldPlay) {
        mExoPlayer.setPlayWhenReady(shouldPlay);
        setKeepScreenOn(shouldPlay);
    }

    private void startWaitingLoop(){
        mBufferWaitCounter = 0;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                int state = mExoPlayer.getPlaybackState();
                if (state == ExoPlayer.STATE_BUFFERING) {
                    Log.d(TAG, "buffer loop" + mBufferWaitCounter);
                    mBufferWaitCounter++;
                    if (mBufferWaitCounter > BUFFER_COUNTER_MAX) {
                        mBufferWaitCounter = 0;
                        removePlayer();
                        preparePlayer();
                        play();
                    } else {
                        mHandler.postDelayed(this, BUFFER_WAIT_INTERVAL);
                    }
                } else {
                    play();
                }
            }
        });
    }

    private boolean isPlaying() {
        if ( mExoPlayer != null ) {
            return mExoPlayer.getPlayWhenReady();
        }
        return false;
    }
}
