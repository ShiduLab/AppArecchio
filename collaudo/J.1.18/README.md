# AppArecchio J.1.18 · Collaudo

Progetto Android con riconoscimento vocale sul dispositivo e ML Kit per la traduzione. Usa JDK 21, Android SDK Platform 35, Build Tools 35 e Gradle 8.9. Da questa cartella:

```powershell
gradle "-PocchioService=https://www.shidulab.org/AppArecchio-Connect/" assembleRelease lintRelease
```

La compilazione produce un APK da firmare. Il collegamento configurato serve alle stanze Internet. Il riconoscimento e la traduzione non richiedono credenziali del servizio. Android può scaricare i modelli al primo uso.

[Manuale di AppArecchio](https://www.shidulab.org/SofficiWare-ShiduLab/Manuale-AppArecchio.html)
