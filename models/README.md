# 语音模型放置目录

请将下载的Vosk中文模型解压到本目录。

## 推荐模型下载地址：

### 【推荐 - 识别准确率高】标准版 (1.3GB)
https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip

### 【轻量 - 启动速度快】轻量版 (40MB)
https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip

## 正确目录结构：
```
models/
└── vosk-model-cn-0.22/    ← 解压后的模型文件夹
    ├── am/
    ├── conf/              ← 必须有conf文件夹
    ├── graph/
    └── ivector/
```

## 其他语言模型：
需要其他语言（英语/粤语/日语/韩语等）请到官网下载：
https://alphacephei.com/vosk/models
解压后放到本目录即可，软件会自动识别。