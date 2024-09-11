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
    private EglBase eglBase;
    private final EglBase.Context sharedContext;
    private VideoFrameDrawer frameDrawer;

    private static final String MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30; // 30fps
    private static final int IFRAME_INTERVAL = 5; // 5 seconds between I-frames

    private final MediaMuxer mediaMuxer;
    private MediaCodec videoEncoder;
    private final MediaCodec.BufferInfo videoBufferInfo;
    private int videoTrackIndex = -1;
    private boolean isRunning = true;
    private GlRectDrawer drawer;
    private Surface surface;

    // Add a mixed audio encoder
    private MediaCodec mixedAudioEncoder;
    private MediaCodec.BufferInfo mixedBufferInfo;
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
            audioThread = new HandlerThread(TAG + "AudioThread");
            audioThread.start();
            audioThreadHandler = new Handler(audioThread.getLooper());
        } else {
            audioThread = null;
            audioThreadHandler = null;
        }
        videoBufferInfo = new MediaCodec.BufferInfo();
        this.sharedContext = sharedContext;

        mediaMuxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        audioTrackIndex = withAudio ? -1 : 0;
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
        if (audioThreadHandler != null)
            audioThreadHandler.post(() -> {
                if (mixedAudioEncoder != null) {
                    mixedAudioEncoder.stop();
                    mixedAudioEncoder.release();
                }
                audioThread.quit();
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
            videoEncoderStarted = true;
            return;
        }
        while (true) {
            int encoderStatus = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                Log.e(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = videoEncoder.getOutputFormat();
                Log.e(TAG, "encoder output format changed: " + newFormat);
                Log.e(TAG, "add video track: " + newFormat);
                videoTrackIndex = mediaMuxer.addTrack(newFormat);
                Log.e(TAG, "video drain mediaMuxer start? videoTrackIndex = " + videoTrackIndex + ", audioTrackIndex = " + audioTrackIndex + "muxerStarted = " + muxerStarted);
                if (audioTrackIndex != -1 && !muxerStarted) {
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
                    ByteBuffer encodedData = videoEncoder.getOutputBuffer(encoderStatus);
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
                    isRunning = isRunning && (videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
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
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64 * 1024);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        try {
            mixedAudioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            mixedAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mixedAudioEncoder.start();
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
        audioThreadHandler.post(() -> {
            // Initialize encoder if not yet
            if (mixedAudioEncoder == null) {
                initMixedAudioEncoder(samples.getSampleRate(), samples.getChannelCount());
            }
            // Store mic samples
            micDataBuffer = samples.getData();
            // Attempt to mix
            attemptMixAndEncode();

        });
    }

    public void onWebRtcOutputAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        if (!isRunning) {
            Log.e(TAG, "onWebRtcOutputAudioRecordSamplesReady !isRunning");
            return;
        }
        audioThreadHandler.post(() -> {
            // Initialize encoder if not yet
            if (mixedAudioEncoder == null) {
                initMixedAudioEncoder(samples.getSampleRate(), samples.getChannelCount());
            }
            // Store speaker samples
            speakerDataBuffer = samples.getData();
            // Attempt to mix
            attemptMixAndEncode();
        });
    }


    public byte[] mixAudioBuffers(short[] firstAudio, short[] secondAudio) {
        int firstAudioLen = firstAudio.length;
        int secondAudioLen = secondAudio.length;
        int size = Math.max(firstAudioLen, secondAudioLen); // 두 오디오 중 긴 길이에 맞춤

        if (size <= 0) return new byte[0];

        byte[] result = new byte[size * 2]; // 최종 결과 버퍼 (byte 배열)

        for (int i = 0; i < size; i++) {
            int sample1 = 0, sample2 = 0;

            if (i < firstAudioLen) {
                sample1 = firstAudio[i]; // 첫 번째 오디오 샘플
            }

            if (i < secondAudioLen) {
                sample2 = secondAudio[i]; // 두 번째 오디오 샘플
            }

            // 두 샘플을 평균하여 동시에 재생되도록 믹스
            int mixedSample = (sample1 + sample2) / 2;

            // 클리핑 방지
            mixedSample = Math.min(Math.max(mixedSample, Short.MIN_VALUE), Short.MAX_VALUE);

            // ByteArray에 Short 값을 저장 (리틀 엔디안 방식)
            int byteIndex = i * 2;
            result[byteIndex] = (byte) (mixedSample & 0xff);           // 하위 바이트
            result[byteIndex + 1] = (byte) ((mixedSample >> 8) & 0xff); // 상위 바이트
        }

        return result;
    }


    long presTime = 0;

    public short[] byteArrayToShortArray(byte[] byteArray) {
        // byte 배열의 길이가 짝수가 아닐 경우 처리
        int shortArrayLength = byteArray.length / 2;
        short[] shortArray = new short[shortArrayLength];

        // byte 2개를 합쳐서 short로 변환
        for (int i = 0; i < shortArrayLength; i++) {
            int byteIndex = i * 2;

            // 리틀 엔디안 방식으로 변환 (하위 바이트 먼저)
            shortArray[i] = (short) ((byteArray[byteIndex + 1] << 8) | (byteArray[byteIndex] & 0xFF));
        }

        return shortArray;
    }
    int sampleRate = 48000; // or the actual sample rate used
    int channelCount = 2; // or the actual channel count
    int bytesPerSample = 2; // for 16-bit PCM
    private void attemptMixAndEncode() {
        int bufferSize;
        byte[] mixedData;
        if (micDataBuffer != null && speakerDataBuffer != null) {
            // Mix the audio samples
            mixedData = mixAudioBuffers(byteArrayToShortArray(micDataBuffer), byteArrayToShortArray(speakerDataBuffer));
            bufferSize = mixedData.length;
        } else if (micDataBuffer != null && speakerDataBuffer == null) {
            return;
        } else if (micDataBuffer == null && speakerDataBuffer != null) {
            return;
        } else {
            mixedData = new byte[0];
            return;
        }
        micDataBuffer = null;
        speakerDataBuffer = null;

        // Feed mixed data to encoder
        int bufferIndex = mixedAudioEncoder.dequeueInputBuffer(0);
        if (bufferIndex >= 0) {
            ByteBuffer inputBuffer = mixedAudioEncoder.getInputBuffer(bufferIndex);
            if (inputBuffer != null) {
                inputBuffer.clear();
                inputBuffer.put(mixedData, 0, bufferSize);
                mixedAudioEncoder.queueInputBuffer(bufferIndex, 0, mixedData.length, presTime, 0);
                presTime += mixedData.length * 125 / 12 * 8; // 1000000 microseconds / 48000hz / 2 bytes
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
                Log.e(TAG, "mixedAudioEncoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mixedAudioEncoder.getOutputFormat();
                audioTrackIndex = mediaMuxer.addTrack(newFormat);
                Log.e(TAG, "Added audio track: " + audioTrackIndex + "videoTrackIndex = " + videoTrackIndex + "muxerStarted = " + muxerStarted);
                if (videoTrackIndex != -1 && !muxerStarted) {
                    mediaMuxer.start();
                    muxerStarted = true;
                    Log.e(TAG, "MediaMuxer started in drainMixedAudioEncoder");
                }
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "Unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = mixedAudioEncoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) {
                        Log.e(TAG, "mixed encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    encodedData.position(mixedBufferInfo.offset);
                    encodedData.limit(mixedBufferInfo.offset + mixedBufferInfo.size);
                    if (muxerStarted) {
                        mediaMuxer.writeSampleData(audioTrackIndex, encodedData, mixedBufferInfo);
                    }
                    isRunning = isRunning && (mixedBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    mixedAudioEncoder.releaseOutputBuffer(encoderStatus, false);
                    if ((mixedBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
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
