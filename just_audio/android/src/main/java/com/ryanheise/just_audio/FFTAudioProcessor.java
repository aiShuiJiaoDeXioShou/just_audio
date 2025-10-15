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
    private double[] fftOutput;
    private byte[] fftBytes;
    private FFTListener listener;
    
    // 用于控制更新频率
    private long lastFftTime = 0;
    // Android Visualizer API 推荐的更新频率约为 10-30 fps
    // 使用 getMaxCaptureRate() 的 1/2 或 3/4，大约是 10-30 次/秒
    private static final long MIN_FFT_INTERVAL = 50; // 约 20fps (1000ms/50ms = 20)

    private static final int FFT_SIZE = 1024;

    public interface FFTListener {
        void onFFTData(byte[] data);
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
        fftOutput = new double[FFT_SIZE];
        // 创建与Visualizer API兼容的byte数组
        // 大小为FFT_SIZE，包含实部和虚部
        fftBytes = new byte[FFT_SIZE];
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
                    // 将16位PCM数据归一化到[-1, 1]范围
                    fftInput[i] = sample / 32768.0;
                }

                if (samplesToProcess < FFT_SIZE) {
                    Arrays.fill(fftInput, samplesToProcess, FFT_SIZE, 0.0);
                }

                // 控制更新频率，避免过快更新
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFftTime >= MIN_FFT_INTERVAL) {
                    lastFftTime = currentTime;
                    
                    // 执行FFT
                    System.arraycopy(fftInput, 0, fftOutput, 0, FFT_SIZE);
                    fft.realForward(fftOutput);
                    
                    // 将结果转换为与Visualizer API兼容的byte数组格式
                    convertToVisualizerFormat(fftOutput, fftBytes);
                    
                    if (listener != null) {
                        listener.onFFTData(fftBytes);
                    }
                }
            }
        }
        outputBuffer = inputBuffer;
    }
    
    /**
     * 将JTransforms的FFT输出转换为与Android Visualizer API兼容的格式
     * Visualizer API的格式:
     * | Index | 0 | 1 | 2 | 3 | 4 | 5 | ... | n-2 | n-1 |
     * | Data  | Rf0 | Rf(n/2) | Rf1 | If1 | Rf2 | If2 | ... | Rf(n-1)/2 | If(n-1)/2 |
     * 
     * 根据Android Visualizer的规范，数据应该是8-bit magnitude FFT
     * 我们需要将JTransforms的double结果转换为byte，并保持正确的比例关系
     * 
     * 针对音乐和人声优化：
     * 1. 使用对数缩放增强低幅度分量的可见性
     * 2. 应用加重函数突出人声频率范围(300Hz-3400Hz)
     * 3. 动态范围压缩使数据更适合可视化
     */
    private void convertToVisualizerFormat(double[] fftData, byte[] bytes) {
        // 计算幅度并应用优化处理
        double[] magnitudes = new double[FFT_SIZE / 2];
        double maxMagnitude = 0.0;
        
        // DC分量 (频率为0)
        magnitudes[0] = Math.abs(fftData[0]);
        maxMagnitude = Math.max(maxMagnitude, magnitudes[0]);
        
        // Nyquist分量
        magnitudes[FFT_SIZE / 2 - 1] = Math.abs(fftData[1]);
        maxMagnitude = Math.max(maxMagnitude, magnitudes[FFT_SIZE / 2 - 1]);
        
        // 其他频率分量
        for (int i = 1; i < FFT_SIZE / 2; i++) {
            double real = fftData[2 * i];
            double imag = fftData[2 * i + 1];
            // 计算幅度
            magnitudes[i] = Math.sqrt(real * real + imag * imag);
            
            // 对人声频率范围(大约在索引10-100之间)应用加重
            // 这个范围大致对应300Hz-3400Hz(人声的主要频率范围)
            if (i >= 10 && i <= 100) {
                magnitudes[i] *= 1.5; // 增强人声频率的权重
            }
            
            maxMagnitude = Math.max(maxMagnitude, magnitudes[i]);
        }
        
        // 避免除零错误
        if (maxMagnitude == 0.0) {
            maxMagnitude = 1.0;
        }
        
        // 转换为byte数组
        // DC分量
        bytes[0] = convertMagnitudeToByte(magnitudes[0], maxMagnitude);
        
        // Nyquist分量
        bytes[1] = convertMagnitudeToByte(magnitudes[FFT_SIZE / 2 - 1], maxMagnitude);
        
        // 其他频率分量
        for (int i = 1; i < FFT_SIZE / 2; i++) {
            // 实部和虚部都使用相同的幅度值进行转换，但保持符号
            double real = fftData[2 * i];
            double imag = fftData[2 * i + 1];
            
            // 使用对数缩放增强小值的可见性
            byte realByte = convertMagnitudeToByte(Math.abs(real), maxMagnitude);
            byte imagByte = convertMagnitudeToByte(Math.abs(imag), maxMagnitude);
            
            // 保持符号信息
            if (real < 0) realByte = (byte) -realByte;
            if (imag < 0) imagByte = (byte) -imagByte;
            
            bytes[2 * i] = realByte;
            bytes[2 * i + 1] = imagByte;
        }
    }
    
    /**
     * 将幅度值转换为byte，使用对数缩放和动态范围压缩
     * @param magnitude 原始幅度值
     * @param maxMagnitude 最大幅度值
     * @return 转换后的byte值
     */
    private byte convertMagnitudeToByte(double magnitude, double maxMagnitude) {
        // 避免log(0)
        if (magnitude <= 0) {
            return 0;
        }
        
        // 归一化到[0,1]范围
        double normalized = magnitude / maxMagnitude;
        
        // 应用对数缩放以增强小值的可见性
        // log(1) = 0, log(10) ≈ 2.3, 所以我们使用log10(normalized * 9 + 1)
        double logScaled = Math.log10(normalized * 9 + 1);
        
        // 映射到byte范围
        return (byte) (logScaled * 127);
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
        if (fftOutput != null) {
            Arrays.fill(fftOutput, 0.0);
        }
        if (fftBytes != null) {
            Arrays.fill(fftBytes, (byte) 0);
        }
        lastFftTime = 0;
    }

    @Override
    public void reset() {
        flush();
        inputAudioFormat = AudioFormat.NOT_SET;
        outputAudioFormat = AudioFormat.NOT_SET;
    }
}