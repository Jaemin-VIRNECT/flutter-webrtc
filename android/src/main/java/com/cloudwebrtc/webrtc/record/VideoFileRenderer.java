package com.cloudwebrtc.webrtc.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.VideoSink;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;

import java.io.IOException;
import java.nio.ByteBuffer;

class VideoFileRenderer implements VideoSink, SamplesReadyCallback {
    private static final String TAG = "VideoFileRenderer";
    private final HandlerThread renderThread;
    private final Handler renderThreadHandler;
    private final HandlerThread audioThread;
    private final Handler audioThreadHandler;
    private int outputFileWidth = -1;
    private int outputFileHeight = -1;
    private ByteBuffer[] videoOutputBuffers;
    private ByteBuffer[] micInputBuffers;
    private ByteBuffer[] micOutputBuffers;
    private ByteBuffer[] speakerInputBuffers;
    private ByteBuffer[] speakerOutputBuffers;
    private EglBase eglBase;
    private final EglBase.Context sharedContext;
    private VideoFrameDrawer frameDrawer;

    private static final String MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30; // 30fps
    private static final int IFRAME_INTERVAL = 5; // 5 seconds between I-frames

    private final MediaMuxer mediaMuxer;
    private MediaCodec videoEncoder;
    private MediaCodec micAudioEncoder;
    private MediaCodec speakerAudioEncoder;
    private final MediaCodec.BufferInfo videoBufferInfo;
    private MediaCodec.BufferInfo micBufferInfo;
    private MediaCodec.BufferInfo speakerBufferInfo;
    private int videoTrackIndex = -1;
    private int micTrackIndex = -1;
    private int speakerTrackIndex = -1;
    private boolean isRunning = true;
    private GlRectDrawer drawer;
    private Surface surface;

    VideoFileRenderer(String outputFile, final EglBase.Context sharedContext, boolean withAudio) throws IOException {
        renderThread = new HandlerThread(TAG + "RenderThread");
        renderThread.start();
        renderThreadHandler = new Handler(renderThread.getLooper());
        if (withAudio) {
            audioThread = new HandlerThread(TAG + "AudioThread");
            audioThread.start();
            audioThreadHandler = new Handler(audioThread.getLooper());
        } else {
            audioThread = null;
            audioThreadHandler = null;
        }
        videoBufferInfo = new MediaCodec.BufferInfo();
        micBufferInfo = new MediaCodec.BufferInfo();
        speakerBufferInfo = new MediaCodec.BufferInfo();
        this.sharedContext = sharedContext;

        mediaMuxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void initVideoEncoder() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, outputFileWidth, outputFileHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        try {
            videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            renderThreadHandler.post(() -> {
                eglBase = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE);
                surface = videoEncoder.createInputSurface();
                eglBase.createSurface(surface);
                eglBase.makeCurrent();
                drawer = new GlRectDrawer();
            });
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
    }

    private void initAudioEncoders(int sampleRate, int channelCount) {
        try {
            // Initialize mic audio encoder
            MediaFormat micFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount);
            micFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            micFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            micAudioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            micAudioEncoder.configure(micFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            micAudioEncoder.start();
            micInputBuffers = micAudioEncoder.getInputBuffers();
            micOutputBuffers = micAudioEncoder.getOutputBuffers();

            // Initialize speaker audio encoder
            MediaFormat speakerFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount);
            speakerFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            speakerFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            speakerAudioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            speakerAudioEncoder.configure(speakerFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            speakerAudioEncoder.start();
            speakerInputBuffers = speakerAudioEncoder.getInputBuffers();
            speakerOutputBuffers = speakerAudioEncoder.getOutputBuffers();
        } catch (IOException exception) {
            Log.wtf(TAG, exception);
        }
    }

    @Override
    public void onFrame(VideoFrame frame) {
        frame.retain();
        if (outputFileWidth == -1) {
            outputFileWidth = frame.getRotatedWidth();
            outputFileHeight = frame.getRotatedHeight();
            initVideoEncoder();
        }
        renderThreadHandler.post(() -> renderFrameOnRenderThread(frame));
    }

    private void renderFrameOnRenderThread(VideoFrame frame) {
        if (frameDrawer == null) {
            frameDrawer = new VideoFrameDrawer();
        }
        frameDrawer.drawFrame(frame, drawer, null, 0, 0, outputFileWidth, outputFileHeight);
        frame.release();
        drainVideoEncoder();
        eglBase.swapBuffers();
    }

    void release() {
        isRunning = false;
        if (audioThreadHandler != null)
            audioThreadHandler.post(() -> {
                if (micAudioEncoder != null) {
                    micAudioEncoder.stop();
                    micAudioEncoder.release();
                }
                if (speakerAudioEncoder != null) {
                    speakerAudioEncoder.stop();
                    speakerAudioEncoder.release();
                }
                audioThread.quit();
            });
        renderThreadHandler.post(() -> {
            if (videoEncoder != null) {
                videoEncoder.stop();
                videoEncoder.release();
            }
            eglBase.release();
            mediaMuxer.stop();
            mediaMuxer.release();
            renderThread.quit();
        });
    }

    private boolean videoEncoderStarted = false;
    private volatile boolean muxerStarted = false;
    private long videoFrameStart = 0;

    private void drainVideoEncoder() {
        if (!videoEncoderStarted) {
            videoEncoder.start();
            videoOutputBuffers = videoEncoder.getOutputBuffers();
            videoEncoderStarted = true;
            return;
        }
        while (true) {
            int encoderStatus = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                videoOutputBuffers = videoEncoder.getOutputBuffers();
                Log.e(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = videoEncoder.getOutputFormat();
                Log.e(TAG, "encoder output format changed: " + newFormat);
                videoTrackIndex = mediaMuxer.addTrack(newFormat);
                if (micTrackIndex != -1 && speakerTrackIndex != -1 && !muxerStarted) {
                    mediaMuxer.start();
                    muxerStarted = true;
                }
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                ByteBuffer encodedData = videoOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                    break;
                }
                encodedData.position(videoBufferInfo.offset);
                encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                if (videoFrameStart == 0 && videoBufferInfo.presentationTimeUs != 0) {
                    videoFrameStart = videoBufferInfo.presentationTimeUs;
                }
                videoBufferInfo.presentationTimeUs -= videoFrameStart;
                if (muxerStarted) {
                    mediaMuxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo);
                }
                videoEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        if (!isRunning) return;
        audioThreadHandler.post(() -> {
            processAudioSamples(true, samples, micAudioEncoder, micBufferInfo, micTrackIndex);
        });
    }

    public void onWebRtcOutputAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        if (!isRunning) return;
        audioThreadHandler.post(() -> {
            processAudioSamples(false, samples, speakerAudioEncoder, speakerBufferInfo, speakerTrackIndex);
        });
    }

    private long micPresTime = 0L;
    private long speakerPresTime = 0L;

    private void processAudioSamples(Boolean isMic, JavaAudioDeviceModule.AudioSamples samples, MediaCodec audioEncoder,
                                     MediaCodec.BufferInfo bufferInfo, int trackIndex) {
        int inputBufferIndex = audioEncoder.dequeueInputBuffer(0);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
            inputBuffer.clear();
            inputBuffer.put(samples.getData());
            Long presentationTimeUs;
            if (isMic) {
                presentationTimeUs = micPresTime;
            } else {
                presentationTimeUs = speakerPresTime;
            }
            audioEncoder.queueInputBuffer(inputBufferIndex, 0, samples.getData().length, presentationTimeUs, 0);
            if (isMic) {
                micPresTime += samples.getData().length * 125 / 12; // 1000000 microseconds / 48000hz / 2 bytes
            } else {
                speakerPresTime += samples.getData().length * 125 / 12; // 1000000 microseconds / 48000hz / 2 bytes
            }
        }

        int outputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
        while (outputBufferIndex >= 0) {
            ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputBufferIndex);
            if (muxerStarted) {
                mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
            }
            audioEncoder.releaseOutputBuffer(outputBufferIndex, false);
            outputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
        }
    }
}
