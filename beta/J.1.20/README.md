# AppArecchio J.1.20 Beta

Per compilare servono JDK 21, Android SDK Platform 35 e Gradle 8.9.

```powershell
gradle "-PocchioService=https://www.shidulab.org/AppArecchio-Connect/" assembleRelease lintRelease
```

Ambiente LIVE usa il riconoscitore locale di Android, ML Kit e il modello [YAMNet](https://ai.google.dev/edge/mediapipe/solutions/audio/audio_classifier). [Manuale di Ambiente LIVE](https://www.shidulab.org/SofficiWare-ShiduLab/Manuale-AppArecchio.html#traduzione).
