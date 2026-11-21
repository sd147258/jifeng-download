package com.jifeng.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Android 侧 m3u8 后处理：TS 分片拼接完成后，用系统 MediaExtractor + MediaMuxer
 * 转封装成标准 MP4（不自己写 TS demuxer）。fMP4 分片在下载器里已直接拼接为 .mp4，
 * 不需要走这里。
 *
 * 转封装失败时返回 null，调用方保留原始 .ts 文件作为兜底，不假装成功。
 */
public final class AndroidM3u8Post {

    private AndroidM3u8Post() {
    }

    /**
     * @param ts  拼接好的 TS 文件
     * @param mp4 目标 MP4 文件
     * @return 成功返回 mp4 文件；失败返回 null（临时 mp4 已清理）
     */
    public static File remuxTsToMp4(File ts, File mp4) {
        if (ts == null || !ts.exists() || ts.length() == 0) return null;
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(ts.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            if (trackCount == 0) return null;

            muxer = new MediaMuxer(mp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] trackMap = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                trackMap[i] = muxer.addTrack(fmt);
            }
            muxer.start();

            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = 0; i < trackCount; i++) {
                extractor.selectTrack(i);
            }
            while (true) {
                buf.clear();
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) break;
                int track = extractor.getSampleTrackIndex();
                if (track < 0 || track >= trackCount) {
                    extractor.advance();
                    continue;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(trackMap[track], buf, info);
                extractor.advance();
            }
            muxer.stop();
            return mp4;
        } catch (Exception e) {
            android.util.Log.e("jifeng.m3u8", "remux failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            if (mp4.exists()) mp4.delete();
            return null;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
