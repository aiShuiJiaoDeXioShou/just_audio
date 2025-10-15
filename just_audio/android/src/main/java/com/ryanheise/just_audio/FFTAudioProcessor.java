package com.ryanheise.just_audio;

import static android.media.AudioFormat.ENCODING_PCM_16BIT;

import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import org.jtransforms.fft.DoubleFFT_1D;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

@UnstableApi
public class FFTAudioProcessor implements AudioProcessor {

    private AudioFormat inputAudioFormat = AudioFormat.NOT_SET;
    private AudioFormat outputAudioFormat = AudioFormat.NOT_SET;

    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded;

    private DoubleFFT_1D fft;
    private double[] fftInput;
    private double[] magnitudes;
    private FFTListener listener;

    private static final int FFT_SIZE = 1024;

    public interface FFTListener {
        void onFFTData(double[] data);
    }

    public void setListener(FFTListener listener) {
        this.listener = listener;
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.inputAudioFormat = inputAudioFormat;
        this.outputAudioFormat = inputAudioFormat; // Passthrough, same format as input
        fft = new DoubleFFT_1D(FFT_SIZE);
        fftInput = new double[FFT_SIZE];
        magnitudes = new double[FFT_SIZE / 2];
        return outputAudioFormat;
    }

    @Override
    public boolean isActive() {
        // This processor is active only when it has been configured.
        return inputAudioFormat.sampleRate != Format.NO_VALUE;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (inputBuffer.hasRemaining()) {
            ByteBuffer readOnlyBuffer = inputBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());

            int position = readOnlyBuffer.position();
            int limit = readOnlyBuffer.limit();
            int size = limit - position;

            if (inputAudioFormat.channelCount > 0 && size > 0) {
                int samplesToProcess = size / (2 * inputAudioFormat.channelCount); // 2 bytes per sample for 16-bit PCM
                for (int i = 0; i < samplesToProcess && i < FFT_SIZE; i++) {
                    double sample = 0;
                    for (int c = 0; c < inputAudioFormat.channelCount; c++) {
                        sample += readOnlyBuffer.getShort(position + (i * inputAudioFormat.channelCount + c) * 2);
                    }
                    fftInput[i] = sample / inputAudioFormat.channelCount;
                }

                if (samplesToProcess < FFT_SIZE) {
                    Arrays.fill(fftInput, samplesToProcess, FFT_SIZE, 0.0);
                }

                fft.realForward(fftInput);

                for (int i = 0; i < FFT_SIZE / 2; i++) {
                    double real = fftInput[2 * i];
                    double imag = fftInput[2 * i + 1];
                    magnitudes[i] = Math.sqrt(real * real + imag * imag);
                }

                if (listener != null) {
                    listener.onFFTData(magnitudes);
                }
            }
        }
        outputBuffer = inputBuffer;
    }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer thisOutputBuffer = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return thisOutputBuffer;
    }

    @Override
    public void queueEndOfStream() {
        inputEnded = true;
    }

    @Override
    public boolean isEnded() {
        return inputEnded && !outputBuffer.hasRemaining();
    }

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        inputEnded = false;
        if (fftInput != null) {
            Arrays.fill(fftInput, 0.0);
        }
        if (magnitudes != null) {
            Arrays.fill(magnitudes, 0.0);
        }
    }

    @Override
    public void reset() {
        flush();
        inputAudioFormat = AudioFormat.NOT_SET;
        outputAudioFormat = AudioFormat.NOT_SET;
    }
}