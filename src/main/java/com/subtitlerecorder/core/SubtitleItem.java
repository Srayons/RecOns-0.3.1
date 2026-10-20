package com.subtitlerecorder.core;

/**
 * 字幕条目
 */
public class SubtitleItem {
    private long startTime; // 开始时间（毫秒，相对于录制开始）
    private long endTime;   // 结束时间（毫秒）
    private String text;    // 字幕文本
    private boolean isFinal; // 是否是最终识别结果

    public SubtitleItem() {
    }

    public SubtitleItem(long startTime, long endTime, String text, boolean isFinal) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.text = text;
        this.isFinal = isFinal;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public void setFinal(boolean aFinal) {
        isFinal = aFinal;
    }

    /**
     * 格式化为SRT时间格式 HH:mm:ss,SSS
     */
    public String formatSrtTime(long timeMs) {
        long hours = timeMs / 3600000;
        long minutes = (timeMs % 3600000) / 60000;
        long seconds = (timeMs % 60000) / 1000;
        long millis = timeMs % 1000;
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    /**
     * 转换为SRT格式字符串
     */
    public String toSrt(int index) {
        return String.format("%d\n%s --> %s\n%s\n\n",
                index,
                formatSrtTime(startTime),
                formatSrtTime(endTime),
                text);
    }
}
