package com.subtitlerecorder.util;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 配置管理器 - 单例模式
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static ConfigManager instance;
    private PropertiesConfiguration config;

    private ConfigManager() {
        loadConfig();
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            synchronized (ConfigManager.class) {
                if (instance == null) {
                    instance = new ConfigManager();
                }
            }
        }
        return instance;
    }

    private void loadConfig() {
        try {
            // 先尝试从外部配置文件加载
            File externalConfig = new File("config.properties");
            if (externalConfig.exists()) {
                config = new PropertiesConfiguration(externalConfig);
                logger.info("从外部配置文件加载配置");
                return;
            }

            // 从classpath加载
            InputStream is = getClass().getClassLoader().getResourceAsStream("config.properties");
            if (is != null) {
                // 复制到工作目录方便修改
                File tempConfig = new File("config.properties");
                copyInputStreamToFile(is, tempConfig);
                config = new PropertiesConfiguration(tempConfig);
                logger.info("从classpath加载配置并复制到工作目录");
            }
        } catch (ConfigurationException | IOException e) {
            logger.error("加载配置文件失败，使用默认配置", e);
        }
    }

    private void copyInputStreamToFile(InputStream in, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    public String getTencentAppId() {
        return config.getString("tencent.app.id", "");
    }

    public String getTencentSecretId() {
        return config.getString("tencent.secret.id", "");
    }

    public String getTencentSecretKey() {
        return config.getString("tencent.secret.key", "");
    }

    public String getAsrEngineType() {
        return config.getString("asr.engine.type", "16k_zh");
    }

    public int getVideoFps() {
        return config.getInt("recorder.video.fps", 30);
    }

    public int getVideoBitrate() {
        return config.getInt("recorder.video.bitrate", 4000000);
    }

    public int getAudioSampleRate() {
        return config.getInt("recorder.audio.sample.rate", 16000);
    }

    public int getAudioChannels() {
        return config.getInt("recorder.audio.channels", 1);
    }

    public int getSubtitleFontSize() {
        return config.getInt("subtitle.font.size", 24);
    }

    public String getSubtitleFontFamily() {
        return config.getString("subtitle.font.family", "Microsoft YaHei");
    }

    public String getOutputDirectory() {
        return config.getString("output.directory", "./recordings");
    }

    public boolean isRealtimeSubtitle() {
        return config.getBoolean("subtitle.show.in.realtime", true);
    }

    public boolean isBurnSubtitle() {
        return config.getBoolean("subtitle.burn.into.video", false);
    }

    public void save() {
        try {
            config.save();
        } catch (ConfigurationException e) {
            logger.error("保存配置失败", e);
        }
    }

    public void setProperty(String key, Object value) {
        config.setProperty(key, value);
    }
}
