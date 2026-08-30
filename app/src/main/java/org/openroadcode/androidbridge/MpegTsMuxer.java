package org.openroadcode.androidbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Minimal single-program MPEG-TS muxer for one H.264 video stream. */
final class MpegTsMuxer {
    private static final int TS_SIZE = 188;
    private static final int PAT_PID = 0x0000;
    private static final int PMT_PID = 0x1000;
    private static final int VIDEO_PID = 0x0100;
    private static final long PTS_HZ = 90_000L;

    private int patContinuity;
    private int pmtContinuity;
    private int videoContinuity;

    void writeHeaders(OutputStream out) throws IOException {
        writePsi(out, PAT_PID, buildPat(), true);
        writePsi(out, PMT_PID, buildPmt(), true);
    }

    void writeVideo(OutputStream out, byte[] annexB, long presentationTimeUs, boolean keyFrame) throws IOException {
        if (keyFrame) writeHeaders(out);
        byte[] pes = buildPes(annexB, presentationTimeUs);
        int offset = 0;
        boolean first = true;
        while (offset < pes.length) {
            byte[] packet = new byte[TS_SIZE];
            packet[0] = 0x47;
            packet[1] = (byte) ((VIDEO_PID >> 8) & 0x1f);
            if (first) packet[1] |= 0x40;
            packet[2] = (byte) VIDEO_PID;

            int remaining = pes.length - offset;
            if (remaining >= 184) {
                packet[3] = (byte) (0x10 | (videoContinuity++ & 0x0f));
                System.arraycopy(pes, offset, packet, 4, 184);
                offset += 184;
            } else {
                int adaptationLength = 183 - remaining;
                packet[3] = (byte) (0x30 | (videoContinuity++ & 0x0f));
                packet[4] = (byte) adaptationLength;
                if (adaptationLength > 0) {
                    packet[5] = 0;
                    for (int i = 6; i < 5 + adaptationLength; i++) packet[i] = (byte) 0xff;
                }
                int payloadStart = 5 + adaptationLength;
                System.arraycopy(pes, offset, packet, payloadStart, remaining);
                offset += remaining;
            }
            out.write(packet);
            first = false;
        }
    }

    private byte[] buildPes(byte[] annexB, long presentationTimeUs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(annexB.length + 32);
        out.write(0); out.write(0); out.write(1); out.write(0xe0);
        int pesLength = annexB.length + 8;
        if (pesLength > 0xffff) pesLength = 0;
        out.write((pesLength >> 8) & 0xff); out.write(pesLength & 0xff);
        out.write(0x80); out.write(0x80); out.write(5);
        long pts = (presentationTimeUs * PTS_HZ / 1_000_000L) & 0x1ffffffffL;
        writePts(out, pts);
        out.write(annexB, 0, annexB.length);
        return out.toByteArray();
    }

    private void writePts(ByteArrayOutputStream out, long pts) {
        out.write((int) (0x21 | (((pts >> 30) & 0x07) << 1)));
        out.write((int) ((pts >> 22) & 0xff));
        out.write((int) (0x01 | (((pts >> 15) & 0x7f) << 1)));
        out.write((int) ((pts >> 7) & 0xff));
        out.write((int) (0x01 | ((pts & 0x7f) << 1)));
    }

    private byte[] buildPat() {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(0x00); s.write(0xb0); s.write(0x0d);
        s.write(0x00); s.write(0x01); s.write(0xc1); s.write(0x00); s.write(0x00);
        s.write(0x00); s.write(0x01); s.write(0xe0 | ((PMT_PID >> 8) & 0x1f)); s.write(PMT_PID & 0xff);
        appendCrc(s);
        return s.toByteArray();
    }

    private byte[] buildPmt() {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(0x02); s.write(0xb0); s.write(0x12);
        s.write(0x00); s.write(0x01); s.write(0xc1); s.write(0x00); s.write(0x00);
        s.write(0xe0 | ((VIDEO_PID >> 8) & 0x1f)); s.write(VIDEO_PID & 0xff);
        s.write(0xf0); s.write(0x00);
        s.write(0x1b); // AVC/H.264
        s.write(0xe0 | ((VIDEO_PID >> 8) & 0x1f)); s.write(VIDEO_PID & 0xff);
        s.write(0xf0); s.write(0x00);
        appendCrc(s);
        return s.toByteArray();
    }

    private void writePsi(OutputStream out, int pid, byte[] section, boolean payloadStart) throws IOException {
        byte[] packet = new byte[TS_SIZE];
        packet[0] = 0x47;
        packet[1] = (byte) ((pid >> 8) & 0x1f);
        if (payloadStart) packet[1] |= 0x40;
        packet[2] = (byte) pid;
        int cc = pid == PAT_PID ? patContinuity++ : pmtContinuity++;
        packet[3] = (byte) (0x10 | (cc & 0x0f));
        packet[4] = 0; // pointer field
        System.arraycopy(section, 0, packet, 5, section.length);
        for (int i = 5 + section.length; i < TS_SIZE; i++) packet[i] = (byte) 0xff;
        out.write(packet);
    }

    private void appendCrc(ByteArrayOutputStream section) {
        byte[] bytes = section.toByteArray();
        int crc = 0xffffffff;
        for (byte value : bytes) {
            crc ^= (value & 0xff) << 24;
            for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ ((crc & 0x80000000) != 0 ? 0x04c11db7 : 0);
        }
        section.write((crc >>> 24) & 0xff); section.write((crc >>> 16) & 0xff);
        section.write((crc >>> 8) & 0xff); section.write(crc & 0xff);
    }
}
