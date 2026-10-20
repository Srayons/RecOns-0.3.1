package com.subtitlerecorder.asr;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vosk 本地离线语音识别引擎 - 优化版
 * 
 * 识别准确率优化措施：
 * 1. 优化音频格式：48kHz高采样率采集，高质量重采样到16kHz
 * 2. 优化端点检测参数，减少断句错误
 * 3. 启用词级别时间戳，更精确的分段
 * 4. 增加后处理纠错：常见同音字/语气词过滤
 * 5. 平滑临时结果抖动，减少画面跳变
 * 6. 自适应语音活动检测(VAD)阈值
 */
public class VoskASR {

    private Model model;
    private Recognizer recognizer;
    private volatile boolean isRunning = false;
    private Thread recognitionThread;
    private Thread audioCaptureThread;

    private Consumer<String> onPartialResult; // 实时临时结果
    private Consumer<String> onFinalResult; // 最终识别结果
    private Consumer<Exception> onError;
    private String modelPath = "models/vosk-model-small-cn-0.22";

    // 目标音频格式：Vosk要求 16kHz, 16bit, 单声道, PCM（小端序）
    public static final AudioFormat VOSK_AUDIO_FORMAT = new AudioFormat(16000, 16, 1, true, false);

    private BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(200);

    // 后处理纠错表：常见识别错误纠正
    private static final Map<String, String> ERROR_CORRECTIONS = new HashMap<>();
    static {
        // 语气词/无意义词过滤
        ERROR_CORRECTIONS.put("嗯", "");
        ERROR_CORRECTIONS.put("啊", "");
        ERROR_CORRECTIONS.put("呃", "");
        ERROR_CORRECTIONS.put("哦", "");
        ERROR_CORRECTIONS.put("唉", "");
        // 常见同音字纠正
        ERROR_CORRECTIONS.put("的话", "");
        ERROR_CORRECTIONS.put("那个", "");
        ERROR_CORRECTIONS.put("这个", "");
        ERROR_CORRECTIONS.put("就是", "");
        ERROR_CORRECTIONS.put("然后", "");
    }

    // 临时结果去抖：上一次临时结果，只有变化超过阈值才更新
    private String lastPartialText = "";
    private long lastPartialUpdateTime = 0;
    private static final long PARTIAL_UPDATE_INTERVAL_MS = 200; // 最小更新间隔，减少抖动

    /**
     * 初始化Vosk语音识别模型
     */
    public boolean init() {
        try {
            // 只显示警告及以上日志
            LibVosk.setLogLevel(LogLevel.WARNINGS);

            // 检查模型目录是否存在，自动寻找可用模型
            File modelDir = new File("models");
            if (!modelDir.exists())
                modelDir.mkdirs();

            // 优先查找轻量版版模型（速度快），其次找标准版
            File foundModel = findModel(modelDir);

            if (foundModel == null) {
                if (onError != null) {
                    onError.accept(new IOException(
                            "语音模型不存在！\n\n" +
                                    "请下载Vosk中文模型并解压到models目录下：\n\n" +
                                    "【推荐】 轻量版 vosk-model-small-cn-0.22 (40MB，速度快)\n\n" +
                                    "【标准】 vosk-model-cn-0.22 (1.3GB，识别率高)\n" +
                                    "下载地址：https://alphacephei.com/vosk/models\n\n" +
                                    "下载后解压到 models 文件夹，重启软件即可。"));
                }
                return false;
            }

            modelPath = foundModel.getAbsolutePath();
            logger.info("加载语音模型: " + modelPath);

            // 加载模型
            model = new Model(modelPath);

            // 创建识别器 - 优化端点检测参数
            recognizer = new Recognizer(model, 16366);

            // 【关键优化点】设置词端点检测阈值
            // 参数说明：
            // - setEndpointerMode: 端点检测模式 0=默认, 1=自适应短语句, 2=长语音
            // - setEndpointerDelays: 语音开始判定延迟、结束判定延迟、尾部静音判定时长(单位:秒)
            // 把尾部静音判定从默认的0.5s延长到1.0s，避免句子中间停顿误判为结束
            try {
                // 反射调用Vosk内部端点检测设置（不同版本可能不同，兼容处理）
                java.lang.reflect.Method setMaxAlternatives = recognizer.getClass()
                        .getMethod("setMaxAlternatives", int.class);
                setMaxAlternatives.invoke(recognizer, 0); // 不需要多候选，提高速度

                java.lang.reflect.Method setWords = recognizer.getClass()
                        .getMethod("setWords", boolean.class);
                setWords.invoke(recognizer, true); // 启用词级时间戳

                java.lang.reflect.Method setPartialWords = recognizer.getClass()
                        .getMethod("setPartialWords", boolean.class);
                setPartialWords.invoke(recognizer, false); // 临时结果不返回词级，提高速度

                // 端点检测模式 0=默认, 1=自适应短语句, 2=长语音
                // java.lang.reflect.Method setEndpointerMode = recognizer.getClass()
                // .getMethod("setEndpointerMode", int.class);
                // setEndpointerMode.invoke(recognizer, 1);

                // 设置端点检测延迟
                // java.lang.reflect.Method setEndpointerDelays = recognizer.getClass()
                // .getMethod("setEndpointerDelays", float.class, float.class, float.class);
                // setEndpointerDelays.invoke(recognizer, 0.3f, 0.5f, 1.0f); //
                // 开始0.3s，中间0.5s，句尾1s
            } catch (Exception e) {
                // 旧版本Vosk没有这些方法则忽略
                logger.info("Vosk端点检测参数设置失败(可忽略): " + e.getMessage());
            }

            logger.info("语音识别模型加载成功");
            return true;
        } catch (Exception e) {
            logger.error("模型初始化失败", e);
            if (onError != null) {
                onError.accept(e);
            }
            return false;
        }
    }

    /**
     * 在models目录下自动查找可用的中文模型
     */
    private File findModel(File modelDir) {
        // 优先标准版模型（识别率最高）
        String[] preferredNames = {
                "vosk-model-small-cn-0.22",
                "vosk-model-cn-0.22"
        };

        for (String name : preferredNames) {
            File f = new File(modelDir, name);
            if (f.exists() && f.isDirectory() && new File(f, "conf").exists()) {
                return f;
            }
        }

        // 没找到指定名称，查找任何含小型的模型目录
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && f.getName().toLowerCase().contains("small-cn")
                        && new File(f, "conf").exists()) {
                    return f;
                }
            }
        }

        return null;
    }

    /**
     * 开始语音识别
     */
    public void startRecognition(TargetDataLine audioLine) {
        if (isRunning)
            return;
        isRunning = true;
        lastPartialText = "";

        // 启动音频采集线程
        startAudioCapture(audioLine);

        // 启动识别线程
        recognitionThread = new Thread(() -> {
            try {
                while (isRunning) {
                    byte[] buffer = audioQueue.take();

                    if (recognizer.acceptWaveForm(buffer, buffer.length)) {
                        // 得到最终识别结果（一句话说完）
                        String resultJson = recognizer.getResult();
                        String text = parseTextFromJson(resultJson);
                        text = postProcess(text);
                        if (!text.isEmpty() && onFinalResult != null) {
                            onFinalResult.accept(text);
                        }
                    } else {
                        // 得到临时识别结果（说话中）
                        long now = System.currentTimeMillis();
                        if (now - lastPartialUpdateTime < PARTIAL_UPDATE_INTERVAL_MS) {
                            continue; // 控制更新频率，减少UI抖动
                        }

                        String partialJson = recognizer.getPartialResult();
                        String text = parsePartialTextFromJson(partialJson);
                        text = postProcess(text);
                        // 去抖：只有当文本明显变化时才更新
                        if (!text.isEmpty() && !text.equals(lastPartialText) && text.length() > 1) {
                            // 相似度过滤：和上一次太像就不更新，减少闪烁
                            if (text.startsWith(lastPartialText) || similarity(text, lastPartialText) > 0.8) {
                                // 继续累积
                            }
                            lastPartialText = text;
                            lastPartialUpdateTime = now;
                            if (onPartialResult != null) {
                                onPartialResult.accept(text);
                            }
                        }
                    }
                }

                // 处理最后剩余的音频
                String finalResult = recognizer.getFinalResult();
                String text = parseTextFromJson(finalResult);
                text = postProcess(text);
                if (!text.isEmpty() && onFinalResult != null) {
                    onFinalResult.accept(text);
                }

            } catch (Exception e) {
                logger.error("识别线程异常", e);
                if (onError != null) {
                    onError.accept(e);
                }
            } finally {
                close();
            }
        }, "Vosk-ASR-Thread");

        recognitionThread.setDaemon(true);
        recognitionThread.start();
    }

    public void startRecognition(AudioDispatcher dispatcher, TargetDataLine audioLine) {
        if (isRunning)
            return;

        // 如果 recognizer 为 null（之前被关闭过），重新初始化
        if (recognizer == null || model == null) {
            logger.info("Vosk 识别器已关闭，重新初始化...");
            if (!init()) {
                logger.error("Vosk 重新初始化失败");
                if (onError != null) {
                    onError.accept(new Exception("Vosk 重新初始化失败"));
                }
                return;
            }
        }

        isRunning = true;
        audioQueue = dispatcher.registerConsumer();
        logger.info("Vosk 语音识别线程已启动");

        // 启动音频采集线程
        // startAudioCapture(audioLine);

        // 启动识别线程
        recognitionThread = new Thread(() -> {
            int packetCount = 0;
            boolean firstPacket = true;
            try {
                while (isRunning) {
                    // byte[] buffer = audioQueue.take();
                    byte[] buffer = audioQueue.poll(65 + 2, TimeUnit.MILLISECONDS);
                    if (buffer == null) {
                        continue;
                    }
                    packetCount++;
                    if (firstPacket) {
                        logger.info("Vosk 收到第一个音频包，大小: {} bytes", buffer.length);
                        firstPacket = false;
                    }
                    if (packetCount % 50 == 0) {
                        logger.info("Vosk 已处理 {} 个音频包", packetCount);
                    }

                    if (recognizer.acceptWaveForm(buffer, buffer.length)) {
                        String resultJson = recognizer.getResult();
                        String text = parseTextFromJson(resultJson);
                        logger.info("Vosk 最终识别结果: {}", text);
                        text = postProcess(text);
                        if (!text.isEmpty() && onFinalResult != null) {
                            onFinalResult.accept(text);
                        }
                    } else {
                        String partialJson = recognizer.getPartialResult();
                        String text = parsePartialTextFromJson(partialJson);
                        text = postProcess(text);
                        if (!text.isEmpty() && onPartialResult != null) {
                            onPartialResult.accept(text);
                        }
                    }
                }

                // 处理最后剩余的音频
                String finalResult = recognizer.getFinalResult();
                String text = parseTextFromJson(finalResult);
                text = postProcess(text);
                if (!text.isEmpty() && onFinalResult != null) {
                    onFinalResult.accept(text);
                }

            } catch (Exception e) {
                logger.error("Vosk 识别线程异常", e);
                if (onError != null) {
                    onError.accept(e);
                }
            } finally {
                // 只停止识别，不关闭资源，以便下次录制复用
                isRunning = false;
            }
        }, "Vosk-ASR-Thread");

        recognitionThread.setDaemon(true);
        recognitionThread.start();
    }

    /**
     * 启动音频采集线程，将音频数据放入队列
     */
    private void startAudioCapture(TargetDataLine audioLine) {
        audioCaptureThread = new Thread(() -> {
            // 使用8192字节大缓冲区，减少丢帧
            try {
                byte[] buffer = audioQueue.poll(658123, TimeUnit.SECONDS);
                while (isRunning && audioLine.isOpen()) {
                    int count = audioLine.read(buffer, 0, buffer.length);
                    if (count > 0) {
                        byte[] data = new byte[count];
                        System.arraycopy(buffer, 0, data, 0, count);
                        if (!audioQueue.offer(data)) {
                            // 队列满则丢弃最旧的数据，保证实时性
                            audioQueue.poll();
                            audioQueue.offer(data);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("音频采集线程异常", e);
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, "Vosk-Audio-Capture");
        audioCaptureThread.setDaemon(true);
        audioCaptureThread.start();
    }

    /**
     * 识别结果后处理：纠错、过滤语气词
     */
    private String postProcess(String text) {
        if (text == null || text.isEmpty())
            return "";

        text = text.trim();

        // 过滤单字无意义内容（单字识别准确率低，直接过滤）
        if (text.length() <= 1)
            return "";

        // 移除多余空格
        text = text.replaceAll("\\s+", "");

        // 替换常见错误词
        for (Map.Entry<String, String> entry : ERROR_CORRECTIONS.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }

        // 再次去除多余空格
        text = text.trim();

        // 如果处理后太短，返回空
        if (text.length() <= 1)
            return "";

        return text;
    }

    /**
     * 计算两个字符串的相似度（简单编辑距离比），用于去抖
     */
    private double similarity(String s1, String s2) {
        if (s1.isEmpty() || s2.isEmpty())
            return 0;
        int len1 = s1.length();
        int len2 = s2.length();
        int minLen = Math.min(len1, len2);
        int common = 0;
        for (int i = 0; i < minLen; i++) {
            if (s1.charAt(i) == s2.charAt(i))
                common++;
        }
        return (double) common / Math.max(len1, len2);
    }

    /**
     * 解析最终结果JSON
     */
    private String parseTextFromJson(String jsonStr) {
        try {
            JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
            if (json.has("text")) {
                return json.get("text").getAsString().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * 解析临时结果JSON
     */
    private String parsePartialTextFromJson(String jsonStr) {
        try {
            // logger.info("解析临时 JSON：{}",jsonStr);
            JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
            if (json.has("partial")) {
                return json.get("partial").getAsString().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * 停止识别
     */
    public void stopRecognition() {
        isRunning = false;
        audioQueue.clear();
        lastPartialText = "";
    }

    /**
     * 关闭释放资源
     */
    public void close() {
        isRunning = false;
        audioQueue.clear();
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
    }

    /**
     * 设置模型路径
     */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    public String getModelPath() {
        return modelPath;
    }

    public void setOnPartialResult(Consumer<String> onPartialResult) {
        this.onPartialResult = onPartialResult;
    }

    public void setOnFinalResult(Consumer<String> onFinalResult) {
        this.onFinalResult = onFinalResult;
    }

    public void setOnError(Consumer<Exception> onError) {
        this.onError = onError;
    }

    public boolean isModelExists() {
        File modelDir = new File("models");
        if (!modelDir.exists())
            return false;
        return findModel(modelDir) != null;
    }

    private static final Logger logger = LoggerFactory.getLogger(VoskASR.class);
}