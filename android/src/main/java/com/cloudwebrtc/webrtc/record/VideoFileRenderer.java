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
    private final HandlerThread inputAudioThread;
    private final HandlerThread outputAudioThread;
    private final Handler inputAudioThreadHandler;
    private final Handler outputAudioThreadHandler;
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
    private boolean isRunning = true;
    private GlRectDrawer drawer;
    private Surface surface;

    // Add a mixed audio encoder
    private MediaCodec mixedAudioEncoder;
    private MediaCodec.BufferInfo mixedBufferInfo;
    private ByteBuffer[] mixedOutputBuffers;
    private int audioTrackIndex = -1;

    // Buffers to store incoming audio samples
    private byte[] micDataBuffer;
    private byte[] speakerDataBuffer;
    private final Object audioLock = new Object(); // For synchronization

    VideoFileRenderer(String outputFile, final EglBase.Context sharedContext, boolean withAudio) throws IOException {
        renderThread = new HandlerThread(TAG + "RenderThread");
        renderThread.start();
        renderThreadHandler = new Handler(renderThread.getLooper());
        if (withAudio) {
            inputAudioThread = new HandlerThread(TAG + "AudioThread");
            inputAudioThread.start();
            inputAudioThreadHandler = new Handler(inputAudioThread.getLooper());
            outputAudioThread = new HandlerThread(TAG + "AudioThread");
            outputAudioThread.start();
            outputAudioThreadHandler = new Handler(inputAudioThread.getLooper());
        } else {
            inputAudioThread = null;
            outputAudioThread = null;
            inputAudioThreadHandler = null;
            outputAudioThreadHandler = null;
        }
        videoBufferInfo = new MediaCodec.BufferInfo();
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
        if (inputAudioThreadHandler != null)
            inputAudioThreadHandler.post(() -> {
                if (micAudioEncoder != null) {
                    micAudioEncoder.stop();
                    micAudioEncoder.release();
                }
                inputAudioThread.quit();
            });
        if (outputAudioThreadHandler != null)
            outputAudioThreadHandler.post(() -> {
                if (speakerAudioEncoder != null) {
                    speakerAudioEncoder.stop();
                    speakerAudioEncoder.release();
                }
                outputAudioThread.quit();
            });
        renderThreadHandler.post(() -> {
            if (videoEncoder != null) {
                videoEncoder.stop();
                videoEncoder.release();
            }
            muxerStarted = false;
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
                Log.e(TAG, "add video track: " + newFormat);
                videoTrackIndex = mediaMuxer.addTrack(newFormat);
                Log.e(TAG, "video drain mediaMuxer start? videoTrackIndex = " + videoTrackIndex + ", audioTrackIndex = " + audioTrackIndex + "muxerStarted = " + muxerStarted);
                if (videoTrackIndex != -1 && audioTrackIndex != -1 && !muxerStarted) {
                    Log.e(TAG, "mediaMuxer started in drainVideoEncoder");
                    mediaMuxer.start();
                    muxerStarted = true;
                }
                Log.e(TAG, "muxerStarted: " + muxerStarted);

                if (!muxerStarted)
                    break;

            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
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
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }

    private void initMixedAudioEncoder(int sampleRate, int channelCount) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000); // Adjust bitrate as needed
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        try {
            mixedAudioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            mixedAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mixedAudioEncoder.start();
            mixedOutputBuffers = mixedAudioEncoder.getOutputBuffers();
            mixedBufferInfo = new MediaCodec.BufferInfo();
        } catch (IOException e) {
            Log.wtf(TAG, "Failed to initialize mixedAudioEncoder", e);
        }
    }


    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        if (!isRunning) {
            Log.e(TAG, "onWebRtcAudioRecordSamplesReady !isRunning");
            return;
        }
        inputAudioThreadHandler.post(() -> {
            synchronized (audioLock) {
                // Initialize encoder if not yet
                if (mixedAudioEncoder == null) {
                    initMixedAudioEncoder(samples.getSampleRate(), samples.getChannelCount());
                }
                // Store mic samples
                micDataBuffer = samples.getData();
                // Attempt to mix
                attemptMixAndEncode();
            }
        });
    }

    public void onWebRtcOutputAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        if (!isRunning) {
            Log.e(TAG, "onWebRtcOutputAudioRecordSamplesReady !isRunning");
            return;
        }
        outputAudioThreadHandler.post(() -> {
            synchronized (audioLock) {
                // Initialize encoder if not yet
                if (mixedAudioEncoder == null) {
                    initMixedAudioEncoder(samples.getSampleRate(), samples.getChannelCount());
                }
                // Store speaker samples
                speakerDataBuffer = samples.getData();
                // Attempt to mix
                attemptMixAndEncode();
            }
        });
    }

    private byte[] mixAudioBuffers(byte[] micData, byte[] speakerData, int bufferSize) {
        byte[] mixedData = new byte[bufferSize];

        for (int i = 0; i < bufferSize; i++) {
            byte micSample = i < micData.length ? micData[i] : 0;
            byte speakerSample = i < speakerData.length ? speakerData[i] : 0;

            int mixedSample = micSample + speakerSample;

            // 클리핑 방지
            if (mixedSample > Byte.MAX_VALUE) {
                mixedSample = Byte.MAX_VALUE;
            } else if (mixedSample < Byte.MIN_VALUE) {
                mixedSample = Byte.MIN_VALUE;
            }

            mixedData[i] = (byte) mixedSample;
        }

        return mixedData;
    }

    private void attemptMixAndEncode() {
        if (micDataBuffer == null || speakerDataBuffer == null) {
            // Wait until both buffers are available
            return;
        }
        int bufferSize = Math.max(micDataBuffer.length, speakerDataBuffer.length);

        // Mix the audio samples
        byte[] mixedData = mixAudioBuffers(micDataBuffer, speakerDataBuffer, bufferSize);
        micDataBuffer = null;
        speakerDataBuffer = null;

        // Feed mixed data to encoder
        int bufferIndex = mixedAudioEncoder.dequeueInputBuffer(10000);
        if (bufferIndex >= 0) {
            ByteBuffer inputBuffer = mixedAudioEncoder.getInputBuffer(bufferIndex);
            if (inputBuffer != null) {
                inputBuffer.clear();
                inputBuffer.put(mixedData);
                long presentationTimeUs = System.nanoTime() / 1000; // Adjust as needed
                mixedAudioEncoder.queueInputBuffer(bufferIndex, 0, mixedData.length, presentationTimeUs, 0);
            }
        }

        // Drain the encoder
        drainMixedAudioEncoder();
    }


    private void drainMixedAudioEncoder() {
        while (true) {
            int encoderStatus = mixedAudioEncoder.dequeueOutputBuffer(mixedBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                mixedOutputBuffers = mixedAudioEncoder.getOutputBuffers();
                Log.e(TAG, "mixedAudioEncoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new RuntimeException("Format changed twice");
                }
                MediaFormat newFormat = mixedAudioEncoder.getOutputFormat();
                audioTrackIndex = mediaMuxer.addTrack(newFormat);
                Log.e(TAG, "Added audio track: " + audioTrackIndex + "videoTrackIndex = " + videoTrackIndex + "muxerStarted = " + muxerStarted);
                if (videoTrackIndex != -1 && audioTrackIndex != -1 && !muxerStarted) {
                    mediaMuxer.start();
                    muxerStarted = true;
                    Log.e(TAG, "MediaMuxer started in drainMixedAudioEncoder");
                }
            } else if (encoderStatus < 0) {
                Log.e(TAG, "Unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                ByteBuffer encodedData = mixedOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    Log.e(TAG, "mixed encoderOutputBuffer " + encoderStatus + " was null");
                    break;
                }
                encodedData.position(mixedBufferInfo.offset);
                encodedData.limit(mixedBufferInfo.offset + mixedBufferInfo.size);
                if (mixedBufferInfo.size > 0 && muxerStarted) {
                    mediaMuxer.writeSampleData(audioTrackIndex, encodedData, mixedBufferInfo);
                }
                mixedAudioEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((mixedBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }


}
