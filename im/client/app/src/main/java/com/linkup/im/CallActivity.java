package com.linkup.im;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.linkup.im.databinding.ActivityCallBinding;

import org.json.JSONObject;
import org.json.JSONArray;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class CallActivity extends AppCompatActivity implements RealtimeClient.Listener {
    private ActivityCallBinding binding;
    private LinkUpApp app;
    private String conversationId;
    private String peerId;
    private String peerName;
    private String peerUsername;
    private String callId;
    private String callType;
    private boolean incoming;
    private boolean ended;
    private boolean muted;
    private boolean speaker;
    private long connectedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<IceCandidate> queuedCandidates = new ArrayList<>();

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private boolean remoteDescriptionSet;
    private boolean initializing;

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                boolean cameraGranted = !isVideo() || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                if (!audioGranted || !cameraGranted) {
                    Toast.makeText(this, "通话需要麦克风" + (isVideo() ? "和摄像头" : "") + "权限", Toast.LENGTH_LONG).show();
                    hangUp("permission_denied");
                    return;
                }
                beginCall();
            });

    public static void openOutgoing(Context context, String conversationId, Models.User peer, String type) {
        Intent intent = baseIntent(context, conversationId, peer, type);
        intent.putExtra("incoming", false);
        context.startActivity(intent);
    }

    public static void openIncoming(Context context, String conversationId, Models.User peer, String callId, String type) {
        Intent intent = baseIntent(context, conversationId, peer, type);
        intent.putExtra("incoming", true);
        intent.putExtra("callId", callId);
        context.startActivity(intent);
    }

    private static Intent baseIntent(Context context, String conversationId, Models.User peer, String type) {
        Intent intent = new Intent(context, CallActivity.class);
        MainActivity.putPeer(intent, conversationId, peer);
        intent.putExtra("callType", type);
        return intent;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (LinkUpApp) getApplication();
        conversationId = getIntent().getStringExtra("conversationId");
        peerId = getIntent().getStringExtra("peerId");
        peerName = getIntent().getStringExtra("peerName");
        peerUsername = getIntent().getStringExtra("peerUsername");
        callId = getIntent().getStringExtra("callId");
        callType = getIntent().getStringExtra("callType");
        incoming = getIntent().getBooleanExtra("incoming", false);
        if (!incoming && (callId == null || callId.isEmpty())) callId = UUID.randomUUID().toString();
        if (conversationId == null || peerId == null || (!"audio".equals(callType) && !"video".equals(callType))) {
            finish();
            return;
        }
        binding = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        app.cancelIncoming(callId == null ? "" : callId);
        Ui.edgeToEdge(this, binding.root);
        binding.callName.setText(peerName);
        binding.callAvatar.setText(Ui.initials(peerName));
        binding.switchCameraButton.setVisibility(isVideo() ? View.VISIBLE : View.GONE);
        binding.endButton.setOnClickListener(view -> hangUp("hangup"));
        binding.declineButton.setOnClickListener(view -> hangUp("declined"));
        binding.acceptButton.setOnClickListener(view -> requestPermissionsAndBegin());
        binding.muteButton.setOnClickListener(view -> toggleMute());
        binding.speakerButton.setOnClickListener(view -> toggleSpeaker());
        binding.switchCameraButton.setOnClickListener(view -> switchCamera());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { hangUp("hangup"); }
        });
        app.realtime().addListener(this);
        app.realtime().connect();
        if (incoming) {
            binding.callStatus.setText(isVideo() ? "视频来电" : "语音来电");
            binding.incomingControls.setVisibility(View.VISIBLE);
            binding.activeControls.setVisibility(View.GONE);
            sendCallEvent("call:ringing", null);
        } else {
            binding.callStatus.setText("正在呼叫…");
            binding.incomingControls.setVisibility(View.GONE);
            binding.activeControls.setVisibility(View.VISIBLE);
            requestPermissionsAndBegin();
        }
    }

    private void requestPermissionsAndBegin() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (isVideo() && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (permissions.isEmpty()) beginCall();
        else permissionLauncher.launch(permissions.toArray(new String[0]));
    }

    private void beginCall() {
        if (peerConnection != null || initializing) return;
        initializing = true;
        binding.incomingControls.setVisibility(View.GONE);
        binding.activeControls.setVisibility(View.VISIBLE);
        startCallService();
        app.api().rtcConfig(new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                if (!ended && !isFinishing()) initializePeerConnection(body.optJSONArray("iceServers"));
            }
            @Override public void onError(String message) {
                if (ended || isFinishing()) return;
                Toast.makeText(CallActivity.this, "使用默认网络配置", Toast.LENGTH_SHORT).show();
                initializePeerConnection(null);
            }
        });
    }

    private void peerReady() {
        if (incoming) {
            binding.callStatus.setText("正在连接…");
            sendCallEvent("call:accept", null);
        } else {
            JSONObject extra = new JSONObject();
            try { extra.put("type", callType); } catch (Exception ignored) {}
            sendCallEvent("call:start", extra);
        }
    }

    private void initializePeerConnection(JSONArray serverArray) {
        if (ended || isFinishing()) return;
        eglBase = EglBase.create();
        binding.remoteVideo.init(eglBase.getEglBaseContext(), null);
        binding.localVideo.init(eglBase.getEglBaseContext(), null);
        binding.remoteVideo.setMirror(false);
        binding.localVideo.setMirror(true);
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        List<PeerConnection.IceServer> iceServers = parseIceServers(serverArray);
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peerConnection = factory.createPeerConnection(configuration, peerObserver);
        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("audio0", audioSource);
        peerConnection.addTrack(audioTrack, Collections.singletonList("stream0"));
        if (isVideo()) startLocalVideo();
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        setSpeaker(isVideo());
        peerReady();
    }

    private List<PeerConnection.IceServer> parseIceServers(JSONArray array) {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                JSONArray urls = item.optJSONArray("urls");
                List<String> values = new ArrayList<>();
                if (urls != null) for (int j = 0; j < urls.length(); j++) values.add(urls.optString(j));
                else if (!item.optString("urls").isEmpty()) values.add(item.optString("urls"));
                if (values.isEmpty()) continue;
                PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(values);
                if (!item.optString("username").isEmpty()) builder.setUsername(item.optString("username"));
                if (!item.optString("credential").isEmpty()) builder.setPassword(item.optString("credential"));
                result.add(builder.createIceServer());
            }
        }
        if (result.isEmpty()) result.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        return result;
    }

    private void startLocalVideo() {
        CameraEnumerator enumerator = new Camera2Enumerator(this);
        for (String device : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(device)) {
                videoCapturer = enumerator.createCapturer(device, null);
                break;
            }
        }
        if (videoCapturer == null) {
            for (String device : enumerator.getDeviceNames()) {
                videoCapturer = enumerator.createCapturer(device, null);
                if (videoCapturer != null) break;
            }
        }
        if (videoCapturer == null) {
            Toast.makeText(this, "未找到可用摄像头", Toast.LENGTH_LONG).show();
            return;
        }
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(false);
        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());
        try { videoCapturer.startCapture(720, 1280, 30); } catch (Exception ignored) {}
        videoTrack = factory.createVideoTrack("video0", videoSource);
        videoTrack.addSink(binding.localVideo);
        peerConnection.addTrack(videoTrack, Collections.singletonList("stream0"));
        binding.localVideo.setVisibility(View.VISIBLE);
        binding.remoteVideo.setVisibility(View.VISIBLE);
        binding.audioIdentity.setVisibility(View.GONE);
    }

    private final PeerConnection.Observer peerObserver = new PeerConnection.Observer() {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            runOnUiThread(() -> {
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) onConnected();
                if (state == PeerConnection.IceConnectionState.FAILED) binding.callStatus.setText("连接失败");
            });
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        @Override public void onIceCandidate(IceCandidate candidate) { sendCandidate(candidate); }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {
            if (!stream.videoTracks.isEmpty()) runOnUiThread(() -> stream.videoTracks.get(0).addSink(binding.remoteVideo));
        }
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel channel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            MediaStreamTrack track = receiver.track();
            if (track instanceof VideoTrack) runOnUiThread(() -> ((VideoTrack) track).addSink(binding.remoteVideo));
        }
    };

    private void createOffer() {
        MediaConstraints constraints = mediaConstraints();
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription description) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() { sendDescription(description); }
                }, description);
            }
        }, constraints);
    }

    private void createAnswer() {
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription description) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() { sendDescription(description); }
                }, description);
            }
        }, mediaConstraints());
    }

    private MediaConstraints mediaConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", String.valueOf(isVideo())));
        return constraints;
    }

    private void sendDescription(SessionDescription description) {
        JSONObject signal = new JSONObject();
        try { signal.put("kind", description.type.canonicalForm()).put("sdp", description.description); } catch (Exception ignored) {}
        sendSignal(signal);
    }

    private void sendCandidate(IceCandidate candidate) {
        JSONObject signal = new JSONObject();
        try {
            signal.put("kind", "candidate").put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp);
        } catch (Exception ignored) {}
        sendSignal(signal);
    }

    private void sendSignal(JSONObject signal) {
        if (callId == null || callId.isEmpty()) return;
        JSONObject data = baseData();
        try { data.put("signal", signal); } catch (Exception ignored) {}
        app.realtime().send("signal", data);
    }

    private void handleSignal(JSONObject signal) {
        if (peerConnection == null || signal == null) return;
        String kind = signal.optString("kind");
        if ("candidate".equals(kind)) {
            IceCandidate candidate = new IceCandidate(signal.optString("sdpMid"), signal.optInt("sdpMLineIndex"), signal.optString("candidate"));
            if (remoteDescriptionSet) peerConnection.addIceCandidate(candidate);
            else queuedCandidates.add(candidate);
            return;
        }
        SessionDescription.Type type = "offer".equals(kind) ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER;
        SessionDescription description = new SessionDescription(type, signal.optString("sdp"));
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override public void onSetSuccess() {
                remoteDescriptionSet = true;
                for (IceCandidate candidate : queuedCandidates) peerConnection.addIceCandidate(candidate);
                queuedCandidates.clear();
                if (type == SessionDescription.Type.OFFER) createAnswer();
            }
        }, description);
    }

    @Override public void onRealtimeEvent(String event, JSONObject data) {
        if (!conversationId.equals(data.optString("conversationId"))) return;
        String eventCallId = data.optString("callId");
        if ("call:created".equals(event) && !incoming) {
            callId = eventCallId;
        } else if ("call:accept".equals(event) && eventCallId.equals(callId) && !incoming
                && !app.session().user().id.equals(data.optString("fromUserId"))) {
            binding.callStatus.setText("正在连接…");
            createOffer();
        } else if ("signal".equals(event) && eventCallId.equals(callId)) {
            handleSignal(data.optJSONObject("signal"));
        } else if ("call:end".equals(event) && eventCallId.equals(callId)
                && !app.session().user().id.equals(data.optString("fromUserId"))) {
            ended = true;
            binding.callStatus.setText("通话已结束");
            handler.postDelayed(this::finish, 700);
        }
    }

    private void sendCallEvent(String event, JSONObject extra) {
        JSONObject data = baseData();
        if (extra != null) {
            java.util.Iterator<String> keys = extra.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try { data.put(key, extra.opt(key)); } catch (Exception ignored) {}
            }
        }
        app.realtime().send(event, data);
    }

    private JSONObject baseData() {
        JSONObject data = new JSONObject();
        try { data.put("conversationId", conversationId).put("callId", callId == null ? "" : callId); } catch (Exception ignored) {}
        return data;
    }

    private void onConnected() {
        if (connectedAt != 0) return;
        connectedAt = System.currentTimeMillis();
        updateDuration();
    }

    private void updateDuration() {
        if (connectedAt == 0 || ended) return;
        long seconds = (System.currentTimeMillis() - connectedAt) / 1000;
        binding.callStatus.setText(String.format(java.util.Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60));
        handler.postDelayed(this::updateDuration, 1000);
    }

    private void toggleMute() {
        muted = !muted;
        if (audioTrack != null) audioTrack.setEnabled(!muted);
        binding.muteButton.setText(muted ? "已静音" : "麦克风");
    }

    @SuppressWarnings("deprecation")
    private void setSpeaker(boolean enabled) {
        speaker = enabled;
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        manager.setSpeakerphoneOn(enabled);
        binding.speakerButton.setText(enabled ? "扬声器开" : "扬声器");
    }

    private void toggleSpeaker() { setSpeaker(!speaker); }

    private void switchCamera() {
        if (videoCapturer instanceof CameraVideoCapturer) ((CameraVideoCapturer) videoCapturer).switchCamera(null);
    }

    private boolean isVideo() { return "video".equals(callType); }

    private void startCallService() {
        Intent intent = new Intent(this, CallService.class);
        intent.putExtra("video", isVideo()).putExtra("peerName", peerName);
        ContextCompat.startForegroundService(this, intent);
    }

    private void hangUp(String reason) {
        if (ended) return;
        ended = true;
        JSONObject extra = new JSONObject();
        try { extra.put("reason", reason); } catch (Exception ignored) {}
        if (callId != null && !callId.isEmpty()) sendCallEvent("call:end", extra);
        finish();
    }

    @Override protected void onDestroy() {
        app.realtime().removeListener(this);
        handler.removeCallbacksAndMessages(null);
        try { if (videoCapturer != null) videoCapturer.stopCapture(); } catch (Exception ignored) {}
        if (videoTrack != null) videoTrack.dispose();
        if (videoSource != null) videoSource.dispose();
        if (videoCapturer != null) videoCapturer.dispose();
        if (surfaceTextureHelper != null) surfaceTextureHelper.dispose();
        if (audioTrack != null) audioTrack.dispose();
        if (audioSource != null) audioSource.dispose();
        if (peerConnection != null) peerConnection.dispose();
        if (factory != null) factory.dispose();
        if (binding != null) {
            binding.localVideo.release();
            binding.remoteVideo.release();
        }
        if (eglBase != null) eglBase.release();
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        manager.setMode(AudioManager.MODE_NORMAL);
        stopService(new Intent(this, CallService.class));
        super.onDestroy();
    }

    private static class SimpleSdpObserver implements org.webrtc.SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
