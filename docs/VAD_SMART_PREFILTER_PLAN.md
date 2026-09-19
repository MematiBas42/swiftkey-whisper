# Silero VAD Akıllı Pencereli Kırpma (Smart Windowed Splicing) Mimarisi ve Uygulama Planı

Bu doküman; Whisper ASR modeline (Groq Cloud) gönderilen ses verisindeki gürültü, fısıltı ve sessizlik kaynaklı halüsinasyonları önlemek amacıyla geliştirilen **Akıllı Pencereli Kırpma (Smart Windowed Splicing with Speech Padding)** mimarisini ve Android üzerindeki uygulama planını detaylandırır.

---

## 1. Problem Tanımı ve Karşılaştırmalı Analiz

Whisper modeli doğrudan ham ses dalgası aldığında, konuşma aralarındaki boşluklar ve arka plan dip gürültüleri modelin dikkat (attention) mekanizmasını yanıltarak halüsinasyonlara (*"Altyazı M.K."*, *"İzlediğiniz için teşekkürler"*, anlamsız hece döngüleri) yol açar.

Bu sorunu çözmek için değerlendirilen yaklaşımlar:

| Yöntem | Mekanizma | Sonuç / Risk | Karar |
| :--- | :--- | :--- | :--- |
| **Sesi Kısmak (Volume Attenuation)** | Sessiz/gürültülü bölgelerin desibelini düşürmek (-20 dB / -40 dB). | **Fısıltı Yanılgısı:** Whisper bir desibel dedektörü değil, Log-Mel spektrogram örüntü tanıyıcısıdır. Kısık sinyalleri "uzaktan fısıldanan konuşma" sanarak halüsinasyonu daha da artırır. | ❌ **Kesinlikle Reddedildi** |
| **Körlemesine Kırpmak (Hard Cut / Splicing)** | VAD bittiği anda 0 ms'de sesi kesip konuşmaları uç uca yapıştırmak. | **Hece Yutma & Click Gürültüsü:** Düşük enerjili patlamalı/ıslıklı ünsüzler (*p, t, k, s, ş*) kesilir. Sıfır geçişi (zero-crossing) gözetilmezse faz sıçraması ("tık" sesi) oluşur. Cümle ritmi bozulur. | ❌ **Kesinlikle Reddedildi** |
| **Akıllı Pencereli Kırpma (Smart Windowed Splicing)** | Çift eşikli histerezis, 250ms konuşma koruma tamponu (pad) ve yalnızca >750ms sessizlikleri ayıklama. | **Doğal Akış & Sıfır Halüsinasyon:** Doğal nefes ve ritim korunur, harf/hece kaybı olmaz, ölü sessizlikler ve uğultular tamamen yok edilir. | ✅ **Kabul Edildi (Endüstri Standardı)** |

---

## 2. Endüstri Standardı: `faster-whisper` (`vad.py`) Referans Mimarisi

Masaüstü `hyprwhspr` ve `faster-whisper` kütüphanesinin CTranslate2 seviyesinde kullandığı `vad_filter=True` algoritmasının temel prensipleri:

```
[Konuşma 1] ---> [ 250ms Speech Pad ] \
                                       \___ (Sadece >750ms sessizlik varsa arayı kes)
[ 250ms Pre-roll ] ---> [Konuşma 2]   /
```

### 4 Altın Kural:
1. **Kısa Duraksamaları Koruma (`min_silence_duration_ms = 750ms`):**
   * Cümle içindeki 100ms – 500ms arasındaki nefes alma ve düşünme boşluklarına dokunulmaz. Dil modeli kelime sınırlarını bu doğal ritimle tanır.
   * Yalnızca **750 ms'den uzun** süren gerçek ölü sessizlikler/duraksamalar aradan ayıklanır.
2. **Akustik Etek Payı (`speech_pad_ms = 250ms`):**
   * Konuşma bittiği anda kesilmez; kelime sonundaki zayıf sesleri kurtarmak için **250 ms sönümlenme payı** eklenir.
   * Yeni konuşma başlangıcında geriye doğru **250 ms pre-roll** eklenir (kelime başı patlamalı ünsüzler korunur).
3. **Çift Eşikli Histerezis (Dual-Threshold Hysteresis):**
   * Konuşmaya giriş eşiği: `0.55` (Yüksek kesinlikli başlangıç).
   * Konuşmadan çıkış eşiği: `0.35` (Ses yavaşça sönerken erken kesilmeyi önler).
4. **Yumuşak Geçiş (10ms Cosine Cross-Fade):**
   * İki konuşma parçası birbirine bağlanırken ek yerinde faz sıçraması ("click" darbesi) oluşmaması için 10 ms'lik yumuşak çapraz geçiş uygulanır.

---

## 3. Android `SwiftKeyWhisper` Uygulama Mimarisi

Groq Cloud REST API'sinde `vad_filter` parametresi bulunmadığı için, bu filtreleme işlemi Android istemcisi üzerinde **ses ağa gönderilmeden hemen önce** yerel `silero_vad.onnx` modeli ve ONNX Runtime Mobile aracılığıyla gerçekleştirilir.

### Mimari Akış:

```
[ Mikrofon Ham PCM (16kHz 16-bit Mono) ]
                   │
                   ▼
       [ VoiceActivityDetector ]
       (ONNX Runtime: silero_vad.onnx)
                   │
       ┌───────────┴───────────┐
       ▼                       ▼
 [Canlı Endpointer]    [Akıllı Tamponlama]
 (Konuşma Başladı/Bitti)  (Histerezis + 250ms Pad)
                               │
                               ▼
                   [ Smart Splicer / Trimmer ]
                   (>750ms sessizlikleri filtrele)
                               │
                               ▼
                   [ WavWriter.pcmToWav ]
                               │
                               ▼
                   [ Groq Whisper API ]
                   (Sıfır Sessizlik, Yüksek Doğruluk)
```

---

## 4. Kod Değişiklikleri ve Entegrasyon Adımları

### A. `VoiceActivityDetector.java`
- `filterSilence(byte[] pcmData)` metodu eklenir.
- Girdi PCM verisi 512 örnekli (32ms) frame'ler halinde Silero VAD ile taranır.
- Çift eşik (`0.55` / `0.35`) ile konuşma segmentleri (`[start, end]`) belirlenir.
- 750ms'den kısa sessizlikler birleştirilir (`merge`).
- Her segmente başından ve sonundan 250ms'lik padding uygulanır.
- Seçilen segmentler 10ms cross-fade ile tek bir temiz PCM dizisinde birleştirilir.

### B. `AudioRecorderManager.java`
- `processSentenceSegment(sessionId, utteranceId, pcmData)` çağrılmadan önce:
  ```java
  byte[] cleanPcm = vad.filterSilence(pcmData);
  byte[] wavBytes = WavWriter.pcmToWav(cleanPcm, SAMPLE_RATE, 1, 16);
  ```
- Bu sayede Groq'a yalnızca filtrelenmiş, yüksek kaliteli ses gönderilir.

---

## 5. Beklenen Kazanımlar

1. **Sıfır Halüsinasyon:** Whisper modeli hiç sessizlik veya monoton uğultu görmediği için jenerik/altyazı metinleri üretmez.
2. **Daha Yüksek Doğruluk (WER Düşüşü):** Akustik bağlam korunurken gereksiz uzun duraksamaların elenmesi modelin cümle bütünlüğünü korumasını sağlar.
3. **Daha Düşük Ağ Yükü ve Gecikme:** Boş sessizlikler yüklenmediği için gönderilen WAV dosya boyutu %30-%50 küçülür, upload süresi kısalır.
