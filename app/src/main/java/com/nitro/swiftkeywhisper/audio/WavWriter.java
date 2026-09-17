package com.nitro.swiftkeywhisper.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavWriter {

    public static byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int totalAudioLen = pcmData.length;
        int totalDataLen = totalAudioLen + 36;
        int byteRate = sampleRate * channels * (bitsPerSample / 8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(totalAudioLen + 44);
        try {
            // RIFF header
            out.write("RIFF".getBytes());
            out.write(intToByteArray(totalDataLen));
            out.write("WAVE".getBytes());

            // fmt subchunk
            out.write("fmt ".getBytes());
            out.write(intToByteArray(16)); // Subchunk1Size for PCM
            out.write(shortToByteArray((short) 1)); // AudioFormat: 1 = PCM
            out.write(shortToByteArray((short) channels));
            out.write(intToByteArray(sampleRate));
            out.write(intToByteArray(byteRate));
            out.write(shortToByteArray((short) (channels * (bitsPerSample / 8)))); // BlockAlign
            out.write(shortToByteArray((short) bitsPerSample));

            // data subchunk
            out.write("data".getBytes());
            out.write(intToByteArray(totalAudioLen));
            out.write(pcmData);

            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] intToByteArray(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] shortToByteArray(short value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array();
    }
}
