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
                Log.e(TAG, "video drain mediaMuxer start? videoTrackIndex = " + videoTrackIndex + ", micTrackIndex = " + micTrackIndex + "speakerTrackIndex = " + speakerTrackIndex + "muxerStarted = " + muxerStarted);
                if (videoTrackIndex != -1 && micTrackIndex != -1 && speakerTrackIndex != -1 && !muxerStarted) {
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

    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        if (!isRunning) return;
        inputAudioThreadHandler.post(() -> {
            if (micAudioEncoder == null) try {
                micAudioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
                MediaFormat format = new MediaFormat();
                format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, samples.getChannelCount());
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, samples.getSampleRate());
                format.setInteger(MediaFormat.KEY_BIT_RATE, 64 * 1024);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                micAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                micAudioEncoder.start();
                micInputBuffers = micAudioEncoder.getInputBuffers();
                micOutputBuffers = micAudioEncoder.getOutputBuffers();
            } catch (IOException exception) {
                Log.wtf(TAG, exception);
            }
            int bufferIndex = micAudioEncoder.dequeueInputBuffer(0);
            if (bufferIndex >= 0) {
                ByteBuffer buffer = micInputBuffers[bufferIndex];
                buffer.clear();
                byte[] data = samples.getData();
                buffer.put(data);
                micAudioEncoder.queueInputBuffer(bufferIndex, 0, data.length, micPresTime, 0);
                micPresTime += data.length * 125 / 12; // 1000000 microseconds / 48000hz / 2 bytes
            }
            drainMicAudio(samples);
        });
    }

    public void onWebRtcOutputAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        if (!isRunning) return;
        outputAudioThreadHandler.post(() -> {
            if (speakerAudioEncoder == null) try {
                speakerAudioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
                MediaFormat format = new MediaFormat();
                format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, samples.getChannelCount());
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, samples.getSampleRate());
                format.setInteger(MediaFormat.KEY_BIT_RATE, 64 * 1024);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                speakerAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                speakerAudioEncoder.start();
                speakerInputBuffers = speakerAudioEncoder.getInputBuffers();
                speakerOutputBuffers = speakerAudioEncoder.getOutputBuffers();
            } catch (IOException exception) {
                Log.wtf(TAG, exception);
            }
            int bufferIndex = speakerAudioEncoder.dequeueInputBuffer(0);
            if (bufferIndex >= 0) {
                ByteBuffer buffer = speakerInputBuffers[bufferIndex];
                buffer.clear();
                byte[] data = samples.getData();
                buffer.put(data);
                speakerAudioEncoder.queueInputBuffer(bufferIndex, 0, data.length, speakerPresTime, 0);
                speakerPresTime += data.length * 125 / 12; // 1000000 microseconds / 48000hz / 2 bytes
            }
            drainSpeakerAudio(samples);
        });
    }

    private long micPresTime = 0L;
    private long speakerPresTime = 0L;

    private void drainMicAudio(JavaAudioDeviceModule.AudioSamples samples) {
        if (micBufferInfo == null) {
            micBufferInfo = new MediaCodec.BufferInfo();
        }
        while (true) {
            int encoderStatus = micAudioEncoder.dequeueOutputBuffer(micBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                micOutputBuffers = micAudioEncoder.getOutputBuffers();
                Log.e(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = micAudioEncoder.getOutputFormat();
                Log.e(TAG, "encoder output format changed: " + newFormat);
                Log.e(TAG, "before add mic Track: " + newFormat);
                micTrackIndex = mediaMuxer.addTrack(newFormat);
                Log.e(TAG, "after add mic Track: " + newFormat);
                Log.e(TAG, "drain Audio drain mediaMuxer start? videoTrackIndex = " + videoTrackIndex + ", micTrackIndex = " + micTrackIndex + "speakerTrackIndex = " + speakerTrackIndex + "muxerStarted = " + muxerStarted);
                if (videoTrackIndex != -1 && micTrackIndex != -1 && speakerTrackIndex != -1 && !muxerStarted) {
                    Log.e(TAG, "mediaMuxer started in drainAudio");
                    mediaMuxer.start();
                    muxerStarted = true;
                }
                Log.e(TAG, "mediaMuxer: " + muxerStarted);
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = micOutputBuffers[encoderStatus];

                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(micBufferInfo.offset);
                    encodedData.limit(micBufferInfo.offset + micBufferInfo.size);
                    if (muxerStarted) {
                        mediaMuxer.writeSampleData(micTrackIndex, encodedData, micBufferInfo);
                    }
                    isRunning = isRunning && (micBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    micAudioEncoder.releaseOutputBuffer(encoderStatus, false);
                    if ((micBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }

    private void drainSpeakerAudio(JavaAudioDeviceModule.AudioSamples samples) {
        if (speakerBufferInfo == null) {
            speakerBufferInfo = new MediaCodec.BufferInfo();
        }
        while (true) {
            int encoderStatus = speakerAudioEncoder.dequeueOutputBuffer(speakerBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                speakerOutputBuffers = speakerAudioEncoder.getOutputBuffers();
                Log.e(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = speakerAudioEncoder.getOutputFormat();
                Log.e(TAG, "encoder output format changed: " + newFormat);
                Log.e(TAG, "before add speaker Track: " + newFormat);
                speakerTrackIndex = mediaMuxer.addTrack(newFormat);
                Log.e(TAG, "after add speaker Track: " + newFormat);
                Log.e(TAG, "drain Audio drain mediaMuxer start? videoTrackIndex = " + videoTrackIndex + ", micTrackIndex = " + micTrackIndex + "speakerTrackIndex = " + speakerTrackIndex + "muxerStarted = " + muxerStarted);
                if (videoTrackIndex != -1 && micTrackIndex != -1 && speakerTrackIndex != -1 && !muxerStarted) {
                    Log.e(TAG, "mediaMuxer started in drainAudio");
                    mediaMuxer.start();
                    muxerStarted = true;
                }
                Log.e(TAG, "mediaMuxer: " + muxerStarted);
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = speakerOutputBuffers[encoderStatus];

                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(speakerBufferInfo.offset);
                    encodedData.limit(speakerBufferInfo.offset + speakerBufferInfo.size);
                    if (muxerStarted) {
                        mediaMuxer.writeSampleData(speakerTrackIndex, encodedData, speakerBufferInfo);
                    }
                    isRunning = isRunning && (speakerBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    speakerAudioEncoder.releaseOutputBuffer(encoderStatus, false);
                    if ((speakerBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }
}
