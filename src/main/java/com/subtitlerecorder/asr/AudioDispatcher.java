package com.subtitlerecorder.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音频分发器 - 单一麦克风输入分发给多个消费者
 *
 * 解决 TargetDataLine 单消费者问题：
 * ScreenRecorder 和 VoskASR 都需要音频数据，
 * 但 Java Sound 的 TargetDataLine.read() 会消费数据，
 * 两个线程同时读会导致一方读不到。
 */
public class AudioDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(AudioDispatcher.class);

    private final TargetDataLine audioLine;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread dispatchThread;

    // 消费者列表：每个消费者有自己的音频队列
    private final CopyOnWriteArrayList<Consumer> consumers = new CopyOnWriteArrayList<>();

    public AudioDispatcher(TargetDataLine audioLine) {
        this.audioLine = audioLine;
    }

    /**
     * 注册一个音频消费者
     * @return 该消费者的音频数据队列
     */
    public BlockingQueue<byte[]> registerConsumer() {
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(200);
        consumers.add(new Consumer(queue));
        logger.info("音频消费者注册成功，当前消费者数: {}", consumers.size());
        return queue;
    }

    /**
     * 注销消费者
     */
    public void unregisterConsumer(BlockingQueue<byte[]> queue) {
        consumers.removeIf(c -> c.queue == queue);
        logger.info("音频消费者注销，当前消费者数: {}", consumers.size());
    }

    /**
     * 开始分发音频数据
     */
    public void start() {
        if (isRunning.get()) return;
        isRunning.set(true);

        dispatchThread = new Thread(this::dispatchLoop, "Audio-Dispatcher");
        dispatchThread.setDaemon(true);
        dispatchThread.start();
        logger.info("音频分发器已启动");
    }

    /**
     * 停止分发
     */
    public void stop() {
        isRunning.set(false);
        if (dispatchThread != null) {
            dispatchThread.interrupt();
        }
        consumers.clear();
        logger.info("音频分发器已停止");
    }

    private void dispatchLoop() {
        byte[] buffer = new byte[4096];
        int packetCount = 0;
        boolean firstPacket = true;
        int zeroCount = 0;
        while (isRunning.get()) {
            try {
                if (!audioLine.isOpen()) {
                    logger.info("audioLine 未打开");
                    Thread.sleep(3000);
                    continue;
                }

                int bytesRead = audioLine.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    packetCount++;
                    zeroCount = 0;
                    if (firstPacket) {
                        logger.info("AudioDispatcher 收到第一个音频包，大小: {} bytes，消费者数: {}", bytesRead, consumers.size());
                        firstPacket = false;
                    }
                    if (packetCount % 100 == 0) {
                        logger.info("AudioDispatcher 已分发 {} 个音频包", packetCount);
                    }

                    // 复制数据分发给所有消费者
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(buffer, 0, data, 0, bytesRead);

                    for (Consumer consumer : consumers) {
                        if (!consumer.queue.offer(data.clone())) {
                            consumer.queue.poll();
                            consumer.queue.offer(data.clone());
                        }
                    }
                } else if (bytesRead == 0) {
                    zeroCount++;
                    if (zeroCount % 1000 == 0) {
                        logger.info("AudioDispatcher 连续 {} 次读取到0字节", zeroCount);
                    }
                    Thread.sleep(360);
                } else {
                    logger.error("AudioDispatcher 读取音频失败，返回值: {}", bytesRead);
                    break;
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                logger.error("音频分发异常", e);
            }
        }
    }

    private static class Consumer {
        final BlockingQueue<byte[]> queue;
        Consumer(BlockingQueue<byte[]> queue) {
            this.queue = queue;
        }
    }
}