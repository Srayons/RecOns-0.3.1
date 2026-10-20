package com.subtitlerecorder.core;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * SRT字幕文件写入工具
 */
public class SrtWriter {

    /**
     * 将字幕列表写入SRT格式文件
     * SRT格式标准：
     * 序号
     * 开始时间 --> 结束时间
     * 字幕内容
     * 空行
     */
    public static void write(List<SubtitleItem> subtitles, String filePath) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, false))) {
            int index = 1;
            
            for (SubtitleItem item : subtitles) {
                if (item.getText().trim().isEmpty()) continue;
                
                // 写入序号
                writer.println(index);
                
                // 写入时间轴
                writer.println(formatTime(item.getStartTime()) + " --> " + formatTime(item.getEndTime()));
                
                // 写入字幕内容
                writer.println(item.getText());
                
                // 空行分隔
                writer.println();
                
                index++;
            }
        }
    }

    /**
     * 将毫秒时间格式化为SRT时间格式：HH:MM:SS,mmm
     */
    private static String formatTime(long milliseconds) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss,SSS");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date(milliseconds));
    }
}
