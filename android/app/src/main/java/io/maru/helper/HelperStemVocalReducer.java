package io.maru.helper;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class HelperStemVocalReducer {
    private static final String TAG = "HelperStemVocalReducer";
    private static final String MODEL_ASSET_PATH = "models/2stems.tflite";
    private static final String SIGNATURE_KEY = "serving_default";
    private static final String INPUT_TENSOR_NAME = "waveform";
    private static final String ACCOMPANIMENT_OUTPUT_NAME = "strided_slice_23";
    private static final String VOCAL_OUTPUT_NAME = "strided_slice_13";
    private static final int CHANNEL_COUNT = 2;
    private static final int LIVE_SAMPLE_RATE = 48000;
    private static final int MODEL_SAMPLE_RATE = 44100;
    // The converted Spleeter TFLite model sounds far cleaner with longer
    // overlapped windows than with tiny realtime chunks.
    private static final int MODEL_CHUNK_FRAMES = MODEL_SAMPLE_RATE * 2;
    private static final int MODEL_OVERLAP_FRAMES = 4096;
    private static final int LIVE_CHUNK_FRAMES =
        scaleFrames(MODEL_CHUNK_FRAMES, LIVE_SAMPLE_RATE, MODEL_SAMPLE_RATE);
    private static final int LIVE_OVERLAP_FRAMES =
        scaleFrames(MODEL_OVERLAP_FRAMES, LIVE_SAMPLE_RATE, MODEL_SAMPLE_RATE);
    private static final int LIVE_STEP_FRAMES = LIVE_CHUNK_FRAMES - LIVE_OVERLAP_FRAMES;
    private static final int MAX_PENDING_BLOCKS = 3;
    private static final int PCM_BYTES_PER_SAMPLE = 2;
    // Accept more startup delay so live karaoke can stay continuous instead of
    // bursting a multi-second block and then starving until the next one lands.
    private static final int STREAM_PREROLL_BLOCKS = 4;
    // Drop the first couple of emitted blocks after a mode reset so karaoke
    // starts from audio the model has already "seen" instead of the
    // boundary-prone first block.
    private static final int STREAM_WARMUP_DROP_BLOCKS = 2;
    private static final float DIRECT_SUBTRACTION_BLEND = 0.28f;
    private static final float VOCAL_DRIVEN_SUBTRACTION_BOOST = 0.38f;
    private static final float RESIDUAL_VOCAL_SUBTRACTION_GAIN = 0.18f;
    private static final float RESIDUAL_CENTER_DUCK_GAIN = 0.24f;
    private static final float OUTPUT_NORMALIZE_THRESHOLD = 1.18f;

    private final Object lock = new Object();
    private final Context appContext;
    private final ArrayDeque<PendingStemBlock> pendingBlocks = new ArrayDeque<>();
    private final ArrayDeque<ReadyStemBlock> readyBlocks = new ArrayDeque<>();
    private final float[] currentInputBlock = new float[LIVE_CHUNK_FRAMES * CHANNEL_COUNT];
    private final float[] pendingOverlapTail = new float[LIVE_OVERLAP_FRAMES * CHANNEL_COUNT];

    private Interpreter interpreter;
    private Thread workerThread;
    private String activeSignatureKey = null;
    private boolean activeUsesSignatureApi = false;
    private OutputTensorBinding[] outputBindings = new OutputTensorBinding[0];
    private int accompanimentOutputIndex = -1;
    private int vocalOutputIndex = -1;
    private int inputRank = 0;
    private boolean modelReady = false;
    private boolean running = false;
    private boolean hasPendingOverlapTail = false;
    private int currentInputFrames = 0;
    private int readyByteCount = 0;
    private int generation = 1;
    private String lastPrepareErrorMessage = null;
    private byte[] drainingBlock = null;
    private int drainingBlockOffset = 0;
    private boolean outputPrimed = false;
    private int remainingWarmupDropBlocks = STREAM_WARMUP_DROP_BLOCKS;

    HelperStemVocalReducer(Context context) {
        appContext = context.getApplicationContext();
    }

    boolean prepare() {
        synchronized (lock) {
            if (modelReady && running && workerThread != null && workerThread.isAlive()) {
                return true;
            }
            clearProcessingStateLocked();
        }

        shutdown();

        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
            Interpreter nextInterpreter = new Interpreter(loadModelFile(), options);
            String nextSignatureKey = resolveSignatureKey(nextInterpreter);
            boolean nextUsesSignatureApi = nextSignatureKey != null;
            Tensor inputTensor =
                nextUsesSignatureApi
                    ? nextInterpreter.getInputTensorFromSignature(INPUT_TENSOR_NAME, nextSignatureKey)
                    : nextInterpreter.getInputTensor(0);
            int[] inputShape = resolveInputShape(inputTensor);
            nextInterpreter.resizeInput(inputTensor.index(), inputShape);
            nextInterpreter.allocateTensors();

            OutputTensorBinding[] nextOutputBindings =
                nextUsesSignatureApi
                    ? resolveSignatureOutputBindings(nextInterpreter, nextSignatureKey)
                    : resolveRawOutputBindings(nextInterpreter);
            int nextAccompanimentOutputIndex = resolveAccompanimentOutputIndex(nextOutputBindings);
            int nextVocalOutputIndex = resolveVocalOutputIndex(nextOutputBindings);
            if (nextAccompanimentOutputIndex < 0 && nextVocalOutputIndex >= 0) {
                nextAccompanimentOutputIndex =
                    findOtherAudioOutputIndex(nextOutputBindings, nextVocalOutputIndex);
            }
            if (nextVocalOutputIndex < 0 && nextAccompanimentOutputIndex >= 0) {
                nextVocalOutputIndex =
                    findOtherAudioOutputIndex(nextOutputBindings, nextAccompanimentOutputIndex);
            }
            if (nextAccompanimentOutputIndex < 0 && nextVocalOutputIndex < 0) {
                throw new IllegalStateException(
                    "Stem model did not expose any usable stereo stem outputs: " +
                    describeOutputBindings(nextOutputBindings)
                );
            }

            synchronized (lock) {
                interpreter = nextInterpreter;
                activeSignatureKey = nextSignatureKey;
                activeUsesSignatureApi = nextUsesSignatureApi;
                outputBindings = nextOutputBindings;
                accompanimentOutputIndex = nextAccompanimentOutputIndex;
                vocalOutputIndex = nextVocalOutputIndex;
                inputRank = inputShape.length;
                lastPrepareErrorMessage = null;
                modelReady = true;
                running = true;
                workerThread = new Thread(this::runWorkerLoop, "MarucastStemVocalReducer");
                workerThread.setDaemon(true);
                workerThread.start();
            }
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Marucast Karaoke could not prepare the local stem model.", error);
            String errorMessage = buildPrepareErrorMessage(error);
            synchronized (lock) {
                interpreter = null;
                activeSignatureKey = null;
                activeUsesSignatureApi = false;
                outputBindings = new OutputTensorBinding[0];
                accompanimentOutputIndex = -1;
                vocalOutputIndex = -1;
                inputRank = 0;
                lastPrepareErrorMessage = errorMessage;
                modelReady = false;
                running = false;
                clearProcessingStateLocked();
            }
            return false;
        }
    }

    boolean isModelReady() {
        synchronized (lock) {
            return modelReady && interpreter != null;
        }
    }

    String getLastPrepareErrorMessage() {
        synchronized (lock) {
            return lastPrepareErrorMessage;
        }
    }

    int getEstimatedOutputDelayMs() {
        long totalDelayFrames =
            LIVE_CHUNK_FRAMES +
                (long) Math.max(0, STREAM_PREROLL_BLOCKS - 1) * LIVE_STEP_FRAMES;
        return (int) Math.max(
            0L,
            Math.round((totalDelayFrames * 1000.0d) / LIVE_SAMPLE_RATE)
        );
    }

    void reset() {
        synchronized (lock) {
            generation += 1;
            clearProcessingStateLocked();
        }
    }

    void shutdown() {
        Thread threadToJoin;
        Interpreter interpreterToClose;
        synchronized (lock) {
            generation += 1;
            running = false;
            modelReady = false;
            accompanimentOutputIndex = -1;
            vocalOutputIndex = -1;
            inputRank = 0;
            activeSignatureKey = null;
            activeUsesSignatureApi = false;
            clearProcessingStateLocked();
            threadToJoin = workerThread;
            workerThread = null;
            interpreterToClose = interpreter;
            interpreter = null;
            outputBindings = new OutputTensorBinding[0];
            lock.notifyAll();
        }

        if (threadToJoin != null) {
            threadToJoin.interrupt();
            try {
                threadToJoin.join(1500L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        if (interpreterToClose != null) {
            try {
                interpreterToClose.close();
            } catch (Exception ignored) {
                // Ignore stale interpreter shutdown failures.
            }
        }
    }

    boolean enqueuePcm16(byte[] buffer, int length) {
        synchronized (lock) {
            if (!modelReady || !running || interpreter == null) {
                return false;
            }
            if (buffer == null || length < CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE) {
                return true;
            }

            int frameSizeBytes = CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE;
            int alignedLength = length - (length % frameSizeBytes);
            for (int offset = 0; offset < alignedLength; offset += frameSizeBytes) {
                int targetOffset = currentInputFrames * CHANNEL_COUNT;
                currentInputBlock[targetOffset] =
                    readLittleEndianPcm16(buffer, offset) / 32768.0f;
                currentInputBlock[targetOffset + 1] =
                    readLittleEndianPcm16(buffer, offset + PCM_BYTES_PER_SAMPLE) / 32768.0f;
                currentInputFrames += 1;

                if (currentInputFrames >= LIVE_CHUNK_FRAMES) {
                    if (pendingBlocks.size() >= MAX_PENDING_BLOCKS) {
                        pendingBlocks.removeFirst();
                    }
                    pendingBlocks.addLast(
                        new PendingStemBlock(
                            generation,
                            Arrays.copyOf(currentInputBlock, currentInputBlock.length)
                        )
                    );

                    if (LIVE_OVERLAP_FRAMES > 0) {
                        System.arraycopy(
                            currentInputBlock,
                            LIVE_STEP_FRAMES * CHANNEL_COUNT,
                            currentInputBlock,
                            0,
                            LIVE_OVERLAP_FRAMES * CHANNEL_COUNT
                        );
                    }
                    currentInputFrames = LIVE_OVERLAP_FRAMES;
                }
            }

            if (!pendingBlocks.isEmpty()) {
                lock.notifyAll();
            }
            return true;
        }
    }

    byte[] dequeueProcessedPcm16(int requestedLength) {
        synchronized (lock) {
            if (requestedLength <= 0) {
                return new byte[0];
            }
            if (!outputPrimed) {
                int minimumBytes =
                    LIVE_STEP_FRAMES *
                        CHANNEL_COUNT *
                        PCM_BYTES_PER_SAMPLE *
                        STREAM_PREROLL_BLOCKS;
                if (readyByteCount < minimumBytes) {
                    return null;
                }
                outputPrimed = true;
            }
            if (readyByteCount < requestedLength) {
                return null;
            }

            byte[] drained = new byte[requestedLength];
            int writeOffset = 0;
            while (writeOffset < requestedLength) {
                if (drainingBlock == null || drainingBlockOffset >= drainingBlock.length) {
                    drainingBlock = null;
                    drainingBlockOffset = 0;
                    while (!readyBlocks.isEmpty() && drainingBlock == null) {
                        ReadyStemBlock nextBlock = readyBlocks.removeFirst();
                        if (nextBlock.generation != generation) {
                            readyByteCount -= nextBlock.buffer.length;
                            continue;
                        }
                        drainingBlock = nextBlock.buffer;
                    }
                    if (drainingBlock == null) {
                        throw new IllegalStateException(
                            "Stem output underrun while dequeuing processed karaoke audio."
                        );
                    }
                }

                int copyLength = Math.min(
                    requestedLength - writeOffset,
                    drainingBlock.length - drainingBlockOffset
                );
                System.arraycopy(
                    drainingBlock,
                    drainingBlockOffset,
                    drained,
                    writeOffset,
                    copyLength
                );
                drainingBlockOffset += copyLength;
                writeOffset += copyLength;
                readyByteCount -= copyLength;
                if (drainingBlockOffset >= drainingBlock.length) {
                    drainingBlock = null;
                    drainingBlockOffset = 0;
                }
            }
            return drained;
        }
    }

    private void runWorkerLoop() {
        while (true) {
            PendingStemBlock pendingBlock;
            Interpreter activeInterpreter;
            String signatureKey;
            boolean usesSignatureApi;
            int activeInputRank;
            int activeAccompanimentOutputIndex;
            int activeVocalOutputIndex;
            OutputTensorBinding[] activeOutputBindings;

            synchronized (lock) {
                while (running && pendingBlocks.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (!running || interpreter == null) {
                    return;
                }

                pendingBlock = pendingBlocks.removeFirst();
                activeInterpreter = interpreter;
                signatureKey = activeSignatureKey;
                usesSignatureApi = activeUsesSignatureApi;
                activeInputRank = inputRank;
                activeAccompanimentOutputIndex = accompanimentOutputIndex;
                activeVocalOutputIndex = vocalOutputIndex;
                activeOutputBindings = outputBindings;
            }

            float[] processedBlock;
            try {
                processedBlock = processBlock(
                    activeInterpreter,
                    signatureKey,
                    usesSignatureApi,
                    activeInputRank,
                    activeAccompanimentOutputIndex,
                    activeVocalOutputIndex,
                    activeOutputBindings,
                    pendingBlock.samples
                );
            } catch (Exception error) {
                Log.e(TAG, "Marucast Karaoke lost the local stem worker.", error);
                String errorMessage = buildPrepareErrorMessage(error);
                synchronized (lock) {
                    running = false;
                    modelReady = false;
                    lastPrepareErrorMessage = errorMessage;
                    clearProcessingStateLocked();
                }
                return;
            }

            synchronized (lock) {
                if (!running || pendingBlock.generation != generation) {
                    continue;
                }

                List<byte[]> readyBuffers = buildReadyBuffersLocked(processedBlock);
                for (byte[] readyBuffer : readyBuffers) {
                    if (remainingWarmupDropBlocks > 0) {
                        remainingWarmupDropBlocks -= 1;
                        continue;
                    }
                    readyBlocks.addLast(new ReadyStemBlock(pendingBlock.generation, readyBuffer));
                    readyByteCount += readyBuffer.length;
                }
            }
        }
    }

    private float[] processBlock(
        Interpreter activeInterpreter,
        String signatureKey,
        boolean usesSignatureApi,
        int activeInputRank,
        int activeAccompanimentOutputIndex,
        int activeVocalOutputIndex,
        OutputTensorBinding[] activeOutputBindings,
        float[] liveBlock
    ) {
        float[] modelInputBlock = resampleInterleaved(liveBlock, LIVE_CHUNK_FRAMES, MODEL_CHUNK_FRAMES);
        Object inputBuffer = createInputBuffer(modelInputBlock, activeInputRank);
        float[] accompanimentModelBlock;
        float[] vocalModelBlock;

        if (usesSignatureApi && signatureKey != null) {
            Map<String, Object> inputMap = new HashMap<>();
            inputMap.put(INPUT_TENSOR_NAME, inputBuffer);
            Map<String, Object> outputMap = new HashMap<>();

            for (OutputTensorBinding binding : activeOutputBindings) {
                outputMap.put(binding.name, createOutputBuffer(binding));
            }

            activeInterpreter.runSignature(inputMap, outputMap, signatureKey);
            accompanimentModelBlock =
                activeAccompanimentOutputIndex >= 0
                    ? flattenOutputBuffer(
                        outputMap.get(activeOutputBindings[activeAccompanimentOutputIndex].name),
                        activeOutputBindings[activeAccompanimentOutputIndex]
                    )
                    : null;
            vocalModelBlock =
                activeVocalOutputIndex >= 0
                    ? flattenOutputBuffer(
                        outputMap.get(activeOutputBindings[activeVocalOutputIndex].name),
                        activeOutputBindings[activeVocalOutputIndex]
                    )
                    : null;
        } else {
            activeInterpreter.runForMultipleInputsOutputs(
                new Object[] { inputBuffer },
                new HashMap<Integer, Object>()
            );
            accompanimentModelBlock =
                activeAccompanimentOutputIndex >= 0
                    ? readRawOutputBuffer(
                        activeInterpreter,
                        activeOutputBindings[activeAccompanimentOutputIndex]
                    )
                    : null;
            vocalModelBlock =
                activeVocalOutputIndex >= 0
                    ? readRawOutputBuffer(
                        activeInterpreter,
                        activeOutputBindings[activeVocalOutputIndex]
                    )
                    : null;
        }
        float[] instrumentalModelBlock = buildInstrumentalModelBlock(
            modelInputBlock,
            accompanimentModelBlock,
            vocalModelBlock
        );
        int instrumentalModelFrames = instrumentalModelBlock.length / CHANNEL_COUNT;
        if (instrumentalModelFrames <= 0) {
            throw new IllegalStateException("Stem model returned an empty instrumental block.");
        }

        float[] accompanimentLiveBlock = resampleInterleaved(
            instrumentalModelBlock,
            instrumentalModelFrames,
            LIVE_CHUNK_FRAMES
        );
        return sanitizeInterleavedPcm(accompanimentLiveBlock);
    }

    private ByteBuffer loadModelFile() throws IOException {
        // First try loading from internal storage (downloaded by user)
        final File stemModelFile = new File(appContext.getFilesDir(), "2stems.tflite");
        if (stemModelFile.exists()) {
            try (FileInputStream inputStream = new FileInputStream(stemModelFile);
                 FileChannel fileChannel = inputStream.getChannel()) {
                return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    stemModelFile.length()
                );
            }
        }

        // Fallback: load from bundled assets
        try {
            AssetFileDescriptor fileDescriptor = appContext.getAssets().openFd(MODEL_ASSET_PATH);
            try (AssetFileDescriptor activeDescriptor = fileDescriptor;
                 FileInputStream inputStream = new FileInputStream(activeDescriptor.getFileDescriptor());
                 FileChannel fileChannel = inputStream.getChannel()) {
                return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    activeDescriptor.getStartOffset(),
                    activeDescriptor.getDeclaredLength()
                );
            }
        } catch (IOException error) {
            try (InputStream inputStream = appContext.getAssets().open(MODEL_ASSET_PATH);
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    outputStream.write(buffer, 0, read);
                }
                byte[] modelBytes = outputStream.toByteArray();
                ByteBuffer modelBuffer = ByteBuffer.allocateDirect(modelBytes.length);
                modelBuffer.order(ByteOrder.nativeOrder());
                modelBuffer.put(modelBytes);
                modelBuffer.rewind();
                return modelBuffer.asReadOnlyBuffer();
            }
        }
    }

    private int[] resolveInputShape(Tensor inputTensor) {
        int[] signature = inputTensor.shapeSignature();
        if (signature.length == 2) {
            return new int[] { MODEL_CHUNK_FRAMES, CHANNEL_COUNT };
        }
        if (signature.length == 3) {
            return new int[] { 1, MODEL_CHUNK_FRAMES, CHANNEL_COUNT };
        }
        throw new IllegalStateException("Unsupported stem-model input rank.");
    }

    private String resolveSignatureKey(Interpreter activeInterpreter) {
        String[] signatureKeys = activeInterpreter.getSignatureKeys();
        if (signatureKeys == null || signatureKeys.length == 0) {
            return null;
        }
        for (String key : signatureKeys) {
            if (SIGNATURE_KEY.equals(key)) {
                return key;
            }
        }
        return signatureKeys[0];
    }

    private OutputTensorBinding[] resolveSignatureOutputBindings(
        Interpreter activeInterpreter,
        String signatureKey
    ) {
        String[] outputNames = activeInterpreter.getSignatureOutputs(signatureKey);
        if (outputNames == null || outputNames.length == 0) {
            throw new IllegalStateException("Stem model signature did not expose any outputs.");
        }

        OutputTensorBinding[] bindings = new OutputTensorBinding[outputNames.length];
        for (int index = 0; index < outputNames.length; index += 1) {
            String outputName = outputNames[index];
            Tensor tensor = activeInterpreter.getOutputTensorFromSignature(outputName, signatureKey);
            bindings[index] = new OutputTensorBinding(
                index,
                outputName,
                tensor.shape(),
                tensor.shapeSignature()
            );
        }
        Log.i(
            TAG,
            "Marucast Karaoke signatures=" + Arrays.toString(activeInterpreter.getSignatureKeys()) +
                " using=" + signatureKey +
                " inputs=" + Arrays.toString(activeInterpreter.getSignatureInputs(signatureKey)) +
                " outputs=" + Arrays.toString(outputNames) +
                " bindings=" + describeOutputBindings(bindings)
        );
        return bindings;
    }

    private OutputTensorBinding[] resolveRawOutputBindings(Interpreter activeInterpreter) {
        int outputCount = activeInterpreter.getOutputTensorCount();
        if (outputCount <= 0) {
            throw new IllegalStateException("Stem model did not expose any raw graph outputs.");
        }

        OutputTensorBinding[] bindings = new OutputTensorBinding[outputCount];
        for (int index = 0; index < outputCount; index += 1) {
            Tensor tensor = activeInterpreter.getOutputTensor(index);
            bindings[index] = new OutputTensorBinding(
                index,
                tensor.name(),
                tensor.shape(),
                tensor.shapeSignature()
            );
        }
        Log.i(
            TAG,
            "Marucast Karaoke raw graph inputs=" + activeInterpreter.getInputTensorCount() +
                " outputs=" + outputCount +
                " bindings=" + describeOutputBindings(bindings)
        );
        return bindings;
    }

    private int resolveAccompanimentOutputIndex(OutputTensorBinding[] bindings) {
        for (OutputTensorBinding binding : bindings) {
            if (binding.audioLike && binding.name.contains(ACCOMPANIMENT_OUTPUT_NAME)) {
                return binding.index;
            }
        }

        int vocalIndex = resolveVocalOutputIndex(bindings);
        int fallbackIndex = findOtherAudioOutputIndex(bindings, vocalIndex);
        if (fallbackIndex >= 0) {
            return fallbackIndex;
        }
        return -1;
    }

    private int resolveVocalOutputIndex(OutputTensorBinding[] bindings) {
        for (OutputTensorBinding binding : bindings) {
            if (binding.audioLike && binding.name.contains(VOCAL_OUTPUT_NAME)) {
                return binding.index;
            }
        }
        return -1;
    }

    private String buildPrepareErrorMessage(Exception error) {
        String detail = error == null ? null : error.getMessage();
        if (detail != null) {
            detail = detail.trim();
        }
        if (detail == null || detail.isEmpty()) {
            return "Marucast Karaoke couldn't load its local stem model.";
        }
        return "Marucast Karaoke couldn't load its local stem model: " + detail;
    }

    private Object createInputBuffer(float[] interleavedInput, int rank) {
        if (rank == 2) {
            float[][] input = new float[MODEL_CHUNK_FRAMES][CHANNEL_COUNT];
            for (int frame = 0; frame < MODEL_CHUNK_FRAMES; frame += 1) {
                int offset = frame * CHANNEL_COUNT;
                input[frame][0] = interleavedInput[offset];
                input[frame][1] = interleavedInput[offset + 1];
            }
            return input;
        }

        float[][][] input = new float[1][MODEL_CHUNK_FRAMES][CHANNEL_COUNT];
        for (int frame = 0; frame < MODEL_CHUNK_FRAMES; frame += 1) {
            int offset = frame * CHANNEL_COUNT;
            input[0][frame][0] = interleavedInput[offset];
            input[0][frame][1] = interleavedInput[offset + 1];
        }
        return input;
    }

    private Object createOutputBuffer(OutputTensorBinding binding) {
        if (binding.rank == 2) {
            return new float[MODEL_CHUNK_FRAMES][CHANNEL_COUNT];
        }
        if (binding.rank == 3) {
            return new float[1][MODEL_CHUNK_FRAMES][CHANNEL_COUNT];
        }
        throw new IllegalStateException(
            "Unsupported signature output rank for " + binding.name + ": " + binding.rank
        );
    }

    private float[] flattenOutputBuffer(Object outputBuffer, OutputTensorBinding binding) {
        if (binding == null || !binding.audioLike) {
            return null;
        }

        if (outputBuffer instanceof float[][]) {
            float[][] typed = (float[][]) outputBuffer;
            float[] flattened = new float[typed.length * CHANNEL_COUNT];
            for (int frame = 0; frame < typed.length; frame += 1) {
                int offset = frame * CHANNEL_COUNT;
                flattened[offset] = readModelChannel(typed[frame], 0);
                flattened[offset + 1] = readModelChannel(typed[frame], 1);
            }
            return flattened;
        }

        if (outputBuffer instanceof float[][][]) {
            float[][][] typed = (float[][][]) outputBuffer;
            if (typed.length == 0) {
                return new float[0];
            }
            float[] flattened = new float[typed[0].length * CHANNEL_COUNT];
            for (int frame = 0; frame < typed[0].length; frame += 1) {
                int offset = frame * CHANNEL_COUNT;
                flattened[offset] = readModelChannel(typed[0][frame], 0);
                flattened[offset + 1] = readModelChannel(typed[0][frame], 1);
            }
            return flattened;
        }

        throw new IllegalStateException("Stem-model signature output buffer type was unsupported.");
    }

    private float[] readRawOutputBuffer(Interpreter activeInterpreter, OutputTensorBinding binding) {
        if (activeInterpreter == null || binding == null) {
            return null;
        }

        Tensor tensor = activeInterpreter.getOutputTensor(binding.index);
        int byteCount = tensor.numBytes();
        if (byteCount < (CHANNEL_COUNT * Float.BYTES) || (byteCount % Float.BYTES) != 0) {
            return null;
        }

        if (!OutputTensorBinding.looksLikeAudioTensor(binding.name, tensor.shape(), tensor.shapeSignature())) {
            return null;
        }

        ByteBuffer tensorBuffer = tensor.asReadOnlyBuffer();
        if (tensorBuffer == null) {
            return null;
        }

        ByteBuffer orderedBuffer = tensorBuffer.duplicate().order(ByteOrder.nativeOrder());
        orderedBuffer.rewind();
        FloatBuffer floatBuffer = orderedBuffer.asFloatBuffer();
        int sampleCount = Math.min(byteCount / Float.BYTES, floatBuffer.remaining());
        if (sampleCount < CHANNEL_COUNT) {
            return null;
        }

        int alignedSampleCount = sampleCount - (sampleCount % CHANNEL_COUNT);
        float[] flattened = new float[alignedSampleCount];
        floatBuffer.get(flattened, 0, alignedSampleCount);
        return flattened;
    }

    private float readModelChannel(float[] frame, int channelIndex) {
        if (frame == null || frame.length == 0) {
            return 0.0f;
        }
        if (channelIndex < frame.length) {
            return frame[channelIndex];
        }
        return frame[0];
    }

    private float[] resampleInterleaved(float[] source, int sourceFrames, int targetFrames) {
        if (sourceFrames <= 0 || targetFrames <= 0) {
            return new float[0];
        }
        if (sourceFrames == targetFrames) {
            return Arrays.copyOf(source, source.length);
        }

        float[] resampled = new float[targetFrames * CHANNEL_COUNT];
        double denominator = Math.max(1, targetFrames - 1);
        double sourceSpan = Math.max(1, sourceFrames - 1);

        for (int targetFrame = 0; targetFrame < targetFrames; targetFrame += 1) {
            double sourcePosition = (targetFrame * sourceSpan) / denominator;
            int leftFrame = (int) Math.floor(sourcePosition);
            int rightFrame = Math.min(sourceFrames - 1, leftFrame + 1);
            double blend = sourcePosition - leftFrame;

            int sourceLeftOffset = leftFrame * CHANNEL_COUNT;
            int sourceRightOffset = rightFrame * CHANNEL_COUNT;
            int targetOffset = targetFrame * CHANNEL_COUNT;
            for (int channel = 0; channel < CHANNEL_COUNT; channel += 1) {
                double start = source[sourceLeftOffset + channel];
                double end = source[sourceRightOffset + channel];
                resampled[targetOffset + channel] = (float) (start + ((end - start) * blend));
            }
        }

        return resampled;
    }

    private float[] sanitizeInterleavedPcm(float[] interleavedPcm) {
        float[] sanitized = Arrays.copyOf(interleavedPcm, interleavedPcm.length);
        float peak = 0.0f;

        for (int index = 0; index < sanitized.length; index += 1) {
            float sample = sanitized[index];
            if (!Float.isFinite(sample)) {
                sample = 0.0f;
            }
            sanitized[index] = sample;
            peak = Math.max(peak, Math.abs(sample));
        }

        if (peak > OUTPUT_NORMALIZE_THRESHOLD) {
            float scale = 1.0f / peak;
            for (int index = 0; index < sanitized.length; index += 1) {
                sanitized[index] *= scale;
            }
        }

        return sanitized;
    }

    private float[] buildInstrumentalModelBlock(
        float[] originalModelInput,
        float[] accompanimentModelBlock,
        float[] vocalModelBlock
    ) {
        float[] subtractionBlock = null;
        if (vocalModelBlock != null && vocalModelBlock.length >= CHANNEL_COUNT) {
            int sharedFrames = Math.min(
                originalModelInput.length / CHANNEL_COUNT,
                vocalModelBlock.length / CHANNEL_COUNT
            );
            subtractionBlock = new float[sharedFrames * CHANNEL_COUNT];
            for (int index = 0; index < subtractionBlock.length; index += 1) {
                subtractionBlock[index] = originalModelInput[index] - vocalModelBlock[index];
            }
        }

        if (accompanimentModelBlock != null && accompanimentModelBlock.length >= CHANNEL_COUNT) {
            if (subtractionBlock == null) {
                return Arrays.copyOf(accompanimentModelBlock, accompanimentModelBlock.length);
            }

            int sharedFrames = Math.min(
                accompanimentModelBlock.length / CHANNEL_COUNT,
                subtractionBlock.length / CHANNEL_COUNT
            );
            float[] combinedBlock = new float[sharedFrames * CHANNEL_COUNT];
            for (int frame = 0; frame < sharedFrames; frame += 1) {
                int offset = frame * CHANNEL_COUNT;
                float accompanimentLeft = accompanimentModelBlock[offset];
                float accompanimentRight = accompanimentModelBlock[offset + 1];
                float directSubtractionLeft = subtractionBlock[offset];
                float directSubtractionRight = subtractionBlock[offset + 1];
                float vocalLeft = vocalModelBlock[offset];
                float vocalRight = vocalModelBlock[offset + 1];
                float originalEnergy =
                    averageAbsStereoSample(
                        originalModelInput[offset],
                        originalModelInput[offset + 1]
                    );
                float vocalEnergy = averageAbsStereoSample(vocalLeft, vocalRight);
                float vocalPresence = clampFloat(
                    (vocalEnergy - 0.018f) / Math.max(0.12f, originalEnergy + 0.04f),
                    0.0f,
                    1.0f
                );
                float subtractionBlend = clampFloat(
                    DIRECT_SUBTRACTION_BLEND +
                        (VOCAL_DRIVEN_SUBTRACTION_BOOST * vocalPresence),
                    0.0f,
                    0.82f
                );
                float cleanedAccompanimentLeft =
                    accompanimentLeft -
                    (vocalLeft * RESIDUAL_VOCAL_SUBTRACTION_GAIN * vocalPresence);
                float cleanedAccompanimentRight =
                    accompanimentRight -
                    (vocalRight * RESIDUAL_VOCAL_SUBTRACTION_GAIN * vocalPresence);
                float mixedLeft =
                    (cleanedAccompanimentLeft * (1.0f - subtractionBlend)) +
                    (directSubtractionLeft * subtractionBlend);
                float mixedRight =
                    (cleanedAccompanimentRight * (1.0f - subtractionBlend)) +
                    (directSubtractionRight * subtractionBlend);
                float mid = (mixedLeft + mixedRight) * 0.5f;
                float side = (mixedLeft - mixedRight) * 0.5f;
                float centerDuck = 1.0f - (RESIDUAL_CENTER_DUCK_GAIN * vocalPresence);
                mid *= clampFloat(centerDuck, 0.0f, 1.0f);
                combinedBlock[offset] = mid + side;
                combinedBlock[offset + 1] = mid - side;
            }
            return combinedBlock;
        }

        if (subtractionBlock != null) {
            return subtractionBlock;
        }

        throw new IllegalStateException("Stem model did not return usable vocal or accompaniment stems.");
    }

    private List<byte[]> buildReadyBuffersLocked(float[] processedChunk) {
        if (LIVE_STEP_FRAMES <= 0 || LIVE_OVERLAP_FRAMES <= 0) {
            return Arrays.asList(encodePcm16(processedChunk));
        }

        List<byte[]> readyBuffers = new ArrayList<>(1);
        if (!hasPendingOverlapTail) {
            readyBuffers.add(encodePcm16Range(processedChunk, 0, LIVE_STEP_FRAMES));
        } else {
            float[] emitted = new float[LIVE_STEP_FRAMES * CHANNEL_COUNT];
            crossfadeOverlapTailInto(emitted, processedChunk);
            int middleFrames = LIVE_STEP_FRAMES - LIVE_OVERLAP_FRAMES;
            if (middleFrames > 0) {
                System.arraycopy(
                    processedChunk,
                    LIVE_OVERLAP_FRAMES * CHANNEL_COUNT,
                    emitted,
                    LIVE_OVERLAP_FRAMES * CHANNEL_COUNT,
                    middleFrames * CHANNEL_COUNT
                );
            }
            readyBuffers.add(encodePcm16(emitted));
        }

        System.arraycopy(
            processedChunk,
            LIVE_STEP_FRAMES * CHANNEL_COUNT,
            pendingOverlapTail,
            0,
            LIVE_OVERLAP_FRAMES * CHANNEL_COUNT
        );
        hasPendingOverlapTail = true;
        return readyBuffers;
    }

    private void crossfadeOverlapTailInto(float[] destination, float[] currentChunk) {
        double denominator = Math.max(1, LIVE_OVERLAP_FRAMES - 1);
        for (int frame = 0; frame < LIVE_OVERLAP_FRAMES; frame += 1) {
            double progress = frame / denominator;
            double fadeOut = Math.cos(progress * Math.PI * 0.5d);
            double fadeIn = Math.sin(progress * Math.PI * 0.5d);
            int offset = frame * CHANNEL_COUNT;
            for (int channel = 0; channel < CHANNEL_COUNT; channel += 1) {
                destination[offset + channel] = (float) (
                    (pendingOverlapTail[offset + channel] * fadeOut) +
                    (currentChunk[offset + channel] * fadeIn)
                );
            }
        }
    }

    private byte[] encodePcm16Range(float[] interleavedPcm, int startFrame, int frameCount) {
        int sampleOffset = startFrame * CHANNEL_COUNT;
        int sampleLength = frameCount * CHANNEL_COUNT;
        byte[] encoded = new byte[sampleLength * PCM_BYTES_PER_SAMPLE];
        for (int index = 0; index < sampleLength; index += 1) {
            int sample = clampPcm16(
                Math.round(interleavedPcm[sampleOffset + index] * Short.MAX_VALUE)
            );
            int outputOffset = index * PCM_BYTES_PER_SAMPLE;
            encoded[outputOffset] = (byte) (sample & 0xFF);
            encoded[outputOffset + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return encoded;
    }

    private byte[] encodePcm16(float[] interleavedPcm) {
        return encodePcm16Range(interleavedPcm, 0, interleavedPcm.length / CHANNEL_COUNT);
    }

    private int readLittleEndianPcm16(byte[] buffer, int offset) {
        int low = buffer[offset] & 0xFF;
        int high = buffer[offset + 1];
        return (short) (low | (high << 8));
    }

    private int clampPcm16(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private float averageAbsStereoSample(float left, float right) {
        return (Math.abs(left) + Math.abs(right)) * 0.5f;
    }

    private float clampFloat(float value, float minValue, float maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private void clearProcessingStateLocked() {
        pendingBlocks.clear();
        readyBlocks.clear();
        Arrays.fill(currentInputBlock, 0.0f);
        Arrays.fill(pendingOverlapTail, 0.0f);
        currentInputFrames = 0;
        readyByteCount = 0;
        hasPendingOverlapTail = false;
        drainingBlock = null;
        drainingBlockOffset = 0;
        outputPrimed = false;
        remainingWarmupDropBlocks = STREAM_WARMUP_DROP_BLOCKS;
    }

    private static int scaleFrames(int sourceFrames, int targetRate, int sourceRate) {
        return (int) Math.round((sourceFrames * (double) targetRate) / sourceRate);
    }

    private int findOtherAudioOutputIndex(OutputTensorBinding[] bindings, int excludedIndex) {
        for (OutputTensorBinding binding : bindings) {
            if (binding.audioLike && binding.index != excludedIndex) {
                return binding.index;
            }
        }
        return -1;
    }

    private String describeOutputBindings(OutputTensorBinding[] bindings) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < bindings.length; index += 1) {
            if (index > 0) {
                builder.append(" | ");
            }
            OutputTensorBinding binding = bindings[index];
            builder
                .append("#")
                .append(binding.index)
                .append(" ")
                .append(binding.name)
                .append(" shape=")
                .append(Arrays.toString(binding.shape))
                .append(" signatureShape=")
                .append(Arrays.toString(binding.shapeSignature))
                .append(binding.audioLike ? " audio" : " aux");
        }
        return builder.toString();
    }

    private static final class PendingStemBlock {
        final int generation;
        final float[] samples;

        PendingStemBlock(int generation, float[] samples) {
            this.generation = generation;
            this.samples = samples;
        }
    }

    private static final class ReadyStemBlock {
        final int generation;
        final byte[] buffer;

        ReadyStemBlock(int generation, byte[] buffer) {
            this.generation = generation;
            this.buffer = buffer;
        }
    }

    private static final class OutputTensorBinding {
        final int index;
        final String name;
        final int[] shape;
        final int[] shapeSignature;
        final int rank;
        final boolean audioLike;

        OutputTensorBinding(int index, String name, int[] shape, int[] shapeSignature) {
            this.index = index;
            this.name = name == null ? "" : name;
            this.shape = shape == null ? new int[0] : Arrays.copyOf(shape, shape.length);
            this.shapeSignature =
                shapeSignature == null ? new int[0] : Arrays.copyOf(shapeSignature, shapeSignature.length);
            this.rank = this.shapeSignature.length > 0 ? this.shapeSignature.length : this.shape.length;
            this.audioLike = looksLikeAudioTensor(this.name, this.shape, this.shapeSignature);
        }

        private static boolean looksLikeAudioTensor(String name, int[] shape, int[] shapeSignature) {
            if (
                name != null &&
                (name.contains(ACCOMPANIMENT_OUTPUT_NAME) || name.contains(VOCAL_OUTPUT_NAME))
            ) {
                return true;
            }
            int[] candidateShape =
                shapeSignature != null && shapeSignature.length > 0 ? shapeSignature : shape;
            if (candidateShape == null || candidateShape.length == 0) {
                return false;
            }
            int trailingDimension = candidateShape[candidateShape.length - 1];
            if (trailingDimension != CHANNEL_COUNT) {
                return false;
            }
            return candidateShape.length == 2 || candidateShape.length == 3;
        }
    }
}
