package com.mrajapa.cordova.videoplayer;

import android.annotation.TargetApi;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.LinearLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaResourceApi;
import org.apache.cordova.PluginResult;
import org.json.JSONException;

import com.google.android.exoplayer2.drm.UnsupportedDrmException;

import com.bitmovin.player.BitmovinPlayer;
import com.bitmovin.player.BitmovinPlayerView;
import com.bitmovin.player.api.event.data.PlaybackFinishedEvent;
import com.bitmovin.player.api.event.data.ReadyEvent;
import com.bitmovin.player.api.event.data.ErrorEvent;
import com.bitmovin.player.api.event.listener.OnErrorListener;
import com.bitmovin.player.api.event.listener.OnPlaybackFinishedListener;
import com.bitmovin.player.api.event.listener.OnReadyListener;
import com.bitmovin.player.config.PlaybackConfiguration;
import com.bitmovin.player.config.PlayerConfiguration;
import com.bitmovin.player.config.StyleConfiguration;
import com.bitmovin.player.config.drm.DRMConfiguration;
import com.bitmovin.player.config.drm.DRMSystems;
import com.bitmovin.player.config.media.DASHSource;
import com.bitmovin.player.config.media.SourceConfiguration;
import com.bitmovin.player.config.media.SourceItem;
import com.bitmovin.player.config.network.NetworkConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoPlayer extends CordovaPlugin implements OnDismissListener {

    protected static final String LOG_TAG = "VideoPlayer";

    protected static final String ASSETS = "/android_asset/";

    private CallbackContext callbackContext = null;

    private Dialog dialog;
    
    private BitmovinPlayerView bitmovinPlayerView;
    
    private BitmovinPlayer bitmovinPlayer;
    
    private ExecutorService executor
            = Executors.newSingleThreadExecutor();

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action        The action to execute.
     * @param args          JSONArray of arguments for the plugin.
     * @param callbackId    The callback id used when calling back into JavaScript.
     * @return              A PluginResult object with a status and message.
     */
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("play")) {
            this.callbackContext = callbackContext;

            CordovaResourceApi resourceApi = webView.getResourceApi();
            String target = args.getString(0);
            String drmLicenseUrl = args.getString(1);

            String fileUriStr;
            try {
                Uri targetUri = resourceApi.remapUri(Uri.parse(target));
                fileUriStr = targetUri.toString();
            } catch (IllegalArgumentException e) {
                fileUriStr = target;
            }
            
            String drmLicenseUriStr;
            try {
                Uri drmLicenseUri = resourceApi.remapUri(Uri.parse(drmLicenseUrl));
                drmLicenseUriStr = drmLicenseUri.toString();
            } catch (IllegalArgumentException e) {
                drmLicenseUriStr = drmLicenseUrl;
            }

            Log.v(LOG_TAG, fileUriStr);
            Log.v(LOG_TAG, drmLicenseUriStr);

            final String path = stripFileProtocol(fileUriStr);
            final String drmLicensePath = stripFileProtocol(drmLicenseUriStr);
            
            boolean errored = false;

            // Create dialog in new thread
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        openVideoDialog(path, drmLicensePath);
                    } catch (UnsupportedDrmException ude) {
                        errored = true;
                        ude.printStackTrace();
                    }
                }
            });

            // Don't return any result now
            PluginResult pluginResult = new PluginResult(errored ? PluginResult.Status.ERROR : PluginResult.Status.NO_RESULT);
            pluginResult.setKeepCallback(!errored);
            callbackContext.sendPluginResult(pluginResult);
            callbackContext = null;

            return true;
        }
        else if (action.equals("close")) {
            if (dialog != null && bitmovinPlayer != null) {
                if(bitmovinPlayer.isPlaying()) {
                    bitmovinPlayer.unload();
                }
                bitmovinPlayer.destroy();
                bitmovinPlayerView.onDestroy();
                dialog.dismiss();
            }

            if (callbackContext != null) {
                PluginResult result = new PluginResult(PluginResult.Status.OK);
                result.setKeepCallback(false); // release status callback in JS side
                callbackContext.sendPluginResult(result);
                callbackContext = null;
            }

            return true;
        }
        return false;
    }

    /**
     * Removes the "file://" prefix from the given URI string, if applicable.
     * If the given URI string doesn't have a "file://" prefix, it is returned unchanged.
     *
     * @param uriString the URI string to operate on
     * @return a path without the "file://" prefix
     */
    public static String stripFileProtocol(String uriString) {
        if (uriString.startsWith("file://")) {
            return Uri.parse(uriString).getPath();
        }
        return uriString;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    protected void openVideoDialog(String path, String drmLicensePath) throws UnsupportedDrmException {
        // Let's create the main dialog
        dialog = new Dialog(cordova.getActivity(), android.R.style.Theme_NoTitleBar);
        dialog.getWindow().getAttributes().windowAnimations = android.R.style.Animation_Dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(this);
        dialog.getWindow().setFlags(LayoutParams.FLAG_FULLSCREEN, LayoutParams.FLAG_FULLSCREEN);

        // Main container layout
        LinearLayout main = new LinearLayout(cordova.getActivity());
        main.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        main.setOrientation(LinearLayout.VERTICAL);
        main.setHorizontalGravity(Gravity.CENTER_HORIZONTAL);
        main.setVerticalGravity(Gravity.CENTER_VERTICAL);
        
        // Create a new SourceItem. In this case we are loading a DASH source.
        String sourceURL = path; // "http://bitmovin-a.akamaihd.net/content/art-of-motion_drm/mpds/11331.mpd";
        SourceItem sourceItem = new SourceItem(sourceURL);

        // Creating a new PlayerConfiguration
        PlayerConfiguration playerConfiguration = new PlayerConfiguration();

        // Assign created SourceConfiguration to the PlayerConfiguration
        SourceConfiguration sourceConfiguration = new SourceConfiguration();
        String drmLicenseUrl = drmLicensePath;
        /* DRMConfiguration drmConfiguration = new DRMConfiguration.Builder()
                .uuid(DRMSystems.WIDEVINE_UUID)
                .licenseUrl(drmLicenseUrl)
                .putHttpHeader("X-Forwarded-For", spoofIpAddress)
                .build();
        sourceItem.addDRMConfiguration(drmConfiguration); */
        sourceItem.addDRMConfiguration(DRMSystems.WIDEVINE_UUID, drmLicenseUrl);
        sourceConfiguration.addSourceItem(sourceItem);
        playerConfiguration.setSourceConfiguration(sourceConfiguration);
        
        // StyleConfiguration styleConfiguration = new StyleConfiguration();
        // styleConfiguration.setPlayerUiJs("file:///android_asset/bitmovinplayer-ui.js");
        // playerConfiguration.setStyleConfiguration(styleConfiguration);

        PlaybackConfiguration playbackConfiguration = new PlaybackConfiguration();
        playbackConfiguration.setAutoplayEnabled(true);
        playerConfiguration.setPlaybackConfiguration(playbackConfiguration);

        /* NetworkConfiguration networkConfig = new NetworkConfiguration();
        networkConfig.setPreprocessHttpRequestCallback((httpRequestType, httpRequest) -> {
            Map<String, String> map = new HashMap<String, String>();
            map.put("X-Forwarded-For", spoofIpAddress);
            httpRequest.setHeaders(map);
            return executor.submit(() -> {
                return httpRequest;
            });
        });
        playerConfiguration.setNetworkConfiguration(networkConfig); */

        bitmovinPlayerView = new BitmovinPlayerView(cordova.getActivity(), playerConfiguration);
        bitmovinPlayerView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        
        bitmovinPlayer = bitmovinPlayerView.getPlayer();
        addListenersToPlayer();
        
        main.addView(bitmovinPlayerView);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;

        dialog.setContentView(main);
        dialog.show();
        dialog.getWindow().setAttributes(lp);
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        Log.d(LOG_TAG, "Dialog dismissed");
        bitmovinPlayer.destroy();
        bitmovinPlayerView.onDestroy();
        if (callbackContext != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK);
            result.setKeepCallback(false); // release status callback in JS side
            callbackContext.sendPluginResult(result);
            callbackContext = null;
        }
    }
    
    private OnReadyListener onReadyListener = new OnReadyListener()
    {
        @Override
        public void onReady(ReadyEvent readyEvent)
        {
            bitmovinPlayer.play();
        }
    };

    private OnPlaybackFinishedListener onPlaybackFinishedListener = new OnPlaybackFinishedListener()
    {
        @Override
        public void onPlaybackFinished(PlaybackFinishedEvent playbackFinishedEvent)
        {
            Log.d(LOG_TAG, "MediaPlayer completed");
            bitmovinPlayer.destroy();
            bitmovinPlayerView.onDestroy();
            dialog.dismiss();
        }
    };
    
    private OnErrorListener onErrorListener = new OnErrorListener()
    {
        @Override
        public void onError(ErrorEvent errorEvent)
        {
            Log.e(LOG_TAG, "An Error occurred (" + errorEvent.getCode() + "): " + errorEvent.getMessage());
            if(bitmovinPlayer.isPlaying()) {
                bitmovinPlayer.unload();
            }
            bitmovinPlayer.destroy();
            bitmovinPlayerView.onDestroy();
            dialog.dismiss();
        }
    };
    
    protected void addListenersToPlayer()
    {
        this.bitmovinPlayer.addEventListener(this.onReadyListener);
        this.bitmovinPlayer.addEventListener(this.onErrorListener);
        this.bitmovinPlayer.addEventListener(this.onPlaybackFinishedListener);
    }
}
