package es.no2.rtcapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.opengl.GLException;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRendererGui;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.opengles.GL10;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import static android.graphics.Bitmap.Config.ARGB_4444;

public class MainActivity extends AppCompatActivity {

    private String SIGNALING_URI = "http://192.168.1.104:7000";
    private static final String VIDEO_TRACK_ID = "video1";
    private static final String AUDIO_TRACK_ID = "audio1";
    private static final String LOCAL_STREAM_ID = "stream1";
    private static final String SDP_MID = "sdpMid";
    private static final String SDP_M_LINE_INDEX = "sdpMLineIndex";
    private static final String SDP = "sdp";
    private static final String CREATEOFFER = "createoffer";
    private static final String OFFER = "offer";
    private static final String ANSWER = "answer";
    private static final String CANDIDATE = "candidate";

    private PeerConnectionFactory peerConnectionFactory;
    private GLSurfaceView videoView;
    private VideoSource localVideoSource;
    private PeerConnection peerConnection;
    private MediaStream localMediaStream;
    private VideoRenderer otherPeerRenderer;
    private DataChannel myDataChannel;
    private Socket socket;
    private boolean createOffer = false;
    TextView ipAddrTextView;
    ImageView cropImageView;
    RelativeLayout mainLayout;
    private Bitmap snapshotBitmap;
    boolean isSender = true;


    DataChannel.Observer dataChannelObserver = new DataChannel.Observer()
    {
        @Override
        public void onBufferedAmountChange(long l) {

        }

        @Override
        public void onStateChange() {

        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {

                if (isSender == false) {

                    if (buffer.binary == false) {
                        int limit = buffer.data.limit();
                        byte[] datas = new byte[limit];
                        buffer.data.get(datas);
                        Bitmap maskBitmap;
                        maskBitmap = BitmapFactory.decodeStream(new ByteArrayInputStream(datas));

                        final Bitmap finalMaskBitmap = maskBitmap;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                cropImageView.setImageBitmap(finalMaskBitmap);
                            }
                        });

                    }

                }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);

        ipAddrTextView = (TextView)findViewById(R.id.ipAddrEditText);
        cropImageView = (ImageView)findViewById(R.id.cropImageView);
        mainLayout = (RelativeLayout)findViewById(R.id.activity_main);


        if(isSender == false)
        {
            cropImageView.setVisibility(View.VISIBLE);
        }
        else{
            cropImageView.setVisibility(View.INVISIBLE);
        }

        PeerConnectionFactory.initializeAndroidGlobals(
                this,  // Context
                true,  // Audio Enabled
                true,  // Video Enabled
                true,  // Hardware Acceleration Enabled
                null); // Render EGL Context

        peerConnectionFactory = new PeerConnectionFactory();

        VideoCapturerAndroid vc = VideoCapturerAndroid.create(VideoCapturerAndroid.getNameOfFrontFacingDevice(), null);

        localVideoSource = peerConnectionFactory.createVideoSource(vc, new MediaConstraints());
        VideoTrack localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, localVideoSource);
        localVideoTrack.setEnabled(true);

        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        AudioTrack localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        localAudioTrack.setEnabled(true);

        localMediaStream = peerConnectionFactory.createLocalMediaStream(LOCAL_STREAM_ID);
        localMediaStream.addTrack(localVideoTrack);
        localMediaStream.addTrack(localAudioTrack);

        videoView = (GLSurfaceView) findViewById(R.id.glview_call);
        if(isSender == true) {
            videoView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    Log.d("Test", "Touch (" + (int) event.getX() + "  ,  " + (int) event.getY() + ")");
                    Log.d("Test", "Width" + videoView.getWidth());
                    Log.d("Test", "Height" + videoView.getHeight());
                    onCaptureBitmap((int) event.getX(), (int) event.getY());
                    return false;
                }
            });
        }
        org.webrtc.VideoRendererGui.setView(videoView, null);
        try {
            otherPeerRenderer = VideoRendererGui.createGui(0, 0, 100, 100, VideoRendererGui.ScalingType.SCALE_ASPECT_FILL, true);
            VideoRenderer renderer = VideoRendererGui.createGui(80, 80, 20, 20, VideoRendererGui.ScalingType.SCALE_ASPECT_FILL, true);
            localVideoTrack.addRenderer(renderer);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onCaptureBitmap(final int x, final int y)
    {
        videoView.queueEvent(new Runnable() {
            @Override
            public void run() {
                EGL10 egl = (EGL10) EGLContext.getEGL();
                GL10 gl = (GL10)egl.eglGetCurrentContext().getGL();
                snapshotBitmap = createBitmapFromGLSurface(0, 0, videoView.getWidth(), videoView.getHeight(), gl);
                final Bitmap maskBitmap = Bitmap.createBitmap(150,150,ARGB_4444);

                for(int i=0; i<150; i++)
                {
                    for(int j=0; j<150; j++)
                    {
                            maskBitmap.setPixel(i,j,snapshotBitmap.getPixel(x+i-75,y+j-75));
                    }
                }

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                ByteBuffer buffer = ByteBuffer.wrap(out.toByteArray());
                myDataChannel.send(new DataChannel.Buffer(buffer, false));
            }
        });
    }

    public void onConnect(View button) {
        if (peerConnection != null)
            return;

        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));

        peerConnection = peerConnectionFactory.createPeerConnection(
                iceServers,
                new MediaConstraints(),
                peerConnectionObserver);

        peerConnection.addStream(localMediaStream);

        if(isSender == true) {
            myDataChannel = peerConnection.createDataChannel("mdc1", new DataChannel.Init());
            myDataChannel.registerObserver(dataChannelObserver);
        }

        try {
            SIGNALING_URI = ipAddrTextView.getText().toString();
            socket = IO.socket(SIGNALING_URI);
            socket.on(CREATEOFFER, new Emitter.Listener() {

                @Override
                public void call(Object... args) {
                    createOffer = true;
                    peerConnection.createOffer(sdpObserver, new MediaConstraints());
                }

            }).on(OFFER, new Emitter.Listener() {

                @Override
                public void call(Object... args) {
                    try {
                        JSONObject obj = (JSONObject) args[0];
                        SessionDescription sdp = new SessionDescription(SessionDescription.Type.OFFER,
                                obj.getString(SDP));
                        peerConnection.setRemoteDescription(sdpObserver, sdp);
                        peerConnection.createAnswer(sdpObserver, new MediaConstraints());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

            }).on(ANSWER, new Emitter.Listener() {

                @Override
                public void call(Object... args) {
                    try {
                        JSONObject obj = (JSONObject) args[0];
                        SessionDescription sdp = new SessionDescription(SessionDescription.Type.ANSWER,
                                obj.getString(SDP));
                        peerConnection.setRemoteDescription(sdpObserver, sdp);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

            }).on(CANDIDATE, new Emitter.Listener() {

                @Override
                public void call(Object... args) {
                    try {
                        JSONObject obj = (JSONObject) args[0];
                        peerConnection.addIceCandidate(new IceCandidate(obj.getString(SDP_MID),
                                obj.getInt(SDP_M_LINE_INDEX),
                                obj.getString(SDP)));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

            });

            socket.connect();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    SdpObserver sdpObserver = new SdpObserver() {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            peerConnection.setLocalDescription(sdpObserver, sessionDescription);
            try {
                JSONObject obj = new JSONObject();
                obj.put(SDP, sessionDescription.description);
                if (createOffer) {
                    socket.emit(OFFER, obj);
                } else {
                    socket.emit(ANSWER, obj);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {

        }

        @Override
        public void onCreateFailure(String s) {

        }

        @Override
        public void onSetFailure(String s) {

        }
    };

    PeerConnection.Observer peerConnectionObserver = new PeerConnection.Observer() {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d("RTCAPP", "onSignalingChange:" + signalingState.toString());
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d("RTCAPP", "onIceConnectionChange:" + iceConnectionState.toString());
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            try {
                JSONObject obj = new JSONObject();
                obj.put(SDP_MID, iceCandidate.sdpMid);
                obj.put(SDP_M_LINE_INDEX, iceCandidate.sdpMLineIndex);
                obj.put(SDP, iceCandidate.sdp);
                socket.emit(CANDIDATE, obj);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            mediaStream.videoTracks.getFirst().addRenderer(otherPeerRenderer);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            if(isSender == false) {
                myDataChannel = dataChannel;
                myDataChannel.registerObserver(dataChannelObserver);
            }
        }

        @Override
        public void onRenegotiationNeeded() {

        }
    };

    @Override
    protected void onPause() {
        super.onPause();
    }

    public void onExitFunc(View view)
    {
        if(peerConnection != null)
        {
            peerConnection.close();
        }

        if(myDataChannel != null)
        {
            myDataChannel.close();
            myDataChannel.unregisterObserver();
        }

        System.exit(0);
    }


    // from other answer in this question
    public Bitmap createBitmapFromGLSurface(int x, int y, int w, int h, GL10 gl) {

        int bitmapBuffer[] = new int[w * h];
        int bitmapSource[] = new int[w * h];
        IntBuffer intBuffer = IntBuffer.wrap(bitmapBuffer);
        intBuffer.position(0);

        try {
            gl.glReadPixels(x, y, w, h, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, intBuffer);
            int offset1, offset2;
            for (int i = 0; i < h; i++) {
                offset1 = i * w;
                offset2 = (h - i - 1) * w;
                for (int j = 0; j < w; j++) {
                    int texturePixel = bitmapBuffer[offset1 + j];
                    int blue = (texturePixel >> 16) & 0xff;
                    int red = (texturePixel << 16) & 0x00ff0000;
                    int pixel = (texturePixel & 0xff00ff00) | red | blue;
                    bitmapSource[offset2 + j] = pixel;
                }
            }

        } catch (GLException e) {
            Log.e("Test", "createBitmapFromGLSurface: " + e.getMessage(), e);
            return null;
        }

        return Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888);
    }

}
