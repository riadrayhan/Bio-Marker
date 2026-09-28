# How to build `risk_model.tflite`

> **TL;DR (3 commands):**
> ```powershell
> cd model_training
> pip install tensorflow-cpu==2.17.0 numpy "pandas>=2.0" "scikit-learn>=1.3"
> python train_legacy.py
> ```
> ⏱  ~5 min download (TensorFlow is large) + ~30 sec training.

---

## 1. কেন এই ফাইল দরকার?

`risk_model.tflite` হলো অ্যাপের **AI মডেল** — এটি ১২টি behavioural ফিচার (motion, typing, voice, time-of-day) থেকে একটি risk score (০–১) বের করে, যেটি দিয়ে PHQ-9 estimate তৈরি হয়।

**ভাল খবর:** এই ফাইল না থাকলেও অ্যাপ ক্র্যাশ করবে না। `TFLiteRiskEngine` automatically `HeuristicRiskEngine` এ fallback দেয় — সংখ্যা তখনো আসবে, শুধু একটু কম accurate হবে।

লগে দেখা যাবে:
```
I/TFLiteRiskEngine: No bundled TFLite model (risk_model.tflite). Using fallback engine.
```

---

## 2. কোন স্ক্রিপ্ট কখন?

| স্ক্রিপ্ট                | কী তৈরি করে                             | কখন ব্যবহার করবেন                       |
| ------------------------ | --------------------------------------- | --------------------------------------- |
| `train_legacy.py` ⭐      | Dense (12-feature) → 1-output           | **এখন এটাই দরকার** — current engine এর সাথে compatible |
| `train_model.py`         | LSTM (7 দিন × 15 ফিচার) → 1-output     | Slice 5 (LSTM engine swap হওয়ার পর)     |

`train_legacy.py` আউটপুট ফাইল: `app/src/main/assets/risk_model.tflite` (~১৫ KB)।

---

## 3. ধাপে ধাপে গাইড (Windows / PowerShell)

### Step 1 — Python ও pip যাচাই
```powershell
python --version    # 3.10+ হলেই হবে
pip --version
```

### Step 2 — TensorFlow ও dependencies install
```powershell
pip install tensorflow-cpu==2.17.0 numpy "pandas>=2.0" "scikit-learn>=1.3"
```
> **Note:** `tensorflow-cpu` ≈ ৬০০ MB ডাউনলোড, ১.৫ GB ইনস্টলড। ভালো ইন্টারনেটে ৩–৫ মিনিট লাগে। GPU ছাড়াই training হবে (১২-ফিচার মডেল খুবই ছোট)।
>
> Alternatively, virtualenv ব্যবহার করুন pollution avoid করতে:
> ```powershell
> cd model_training
> python -m venv venv
> venv\Scripts\Activate.ps1
> pip install -r requirements.txt
> pip install tensorflow-cpu==2.17.0
> ```

### Step 3 — Training চালান
```powershell
cd C:\Users\BM COMPUTERS\AndroidStudioProjects\Bio_Marker2\model_training
python train_legacy.py
```
Expected output (শেষ কয়েক লাইন):
```
Test MAE on PHQ-9 scale: 3.42
Screening AUC (PHQ-9>=10): 0.871
Wrote ...\app\src\main\assets\risk_model.tflite (15.3 KB)
Rebuild the Android app to bundle the new model.
```

### Step 4 — অ্যাপ rebuild
```powershell
cd ..
.\gradlew.bat :app:assembleDebug
```
APK পথ: `app/build/outputs/apk/debug/app-debug.apk`

### Step 5 — যাচাই করুন
- ফোনে অ্যাপ install করে চালান
- Logcat filter: `Bio_Marker` বা `TFLiteRiskEngine`
- যদি model load হয়, "engine: tflite-risk-v1" দেখাবে dashboard এর Clinician tab এ
- যদি না হয়, "engine: heuristic-v1" দেখাবে

---

## 4. সাধারণ সমস্যা ও সমাধান

| সমস্যা                                                 | সমাধান                                                    |
| ------------------------------------------------------ | --------------------------------------------------------- |
| `pip install tensorflow-cpu` "timed out"               | আবার চালান, বা `pip install --default-timeout=600 tensorflow-cpu==2.17.0` |
| `ModuleNotFoundError: No module named 'tensorflow'`    | Step 2 আবার চালান, virtualenv এ থাকলে `Activate.ps1` চালু আছে কিনা চেক করুন |
| `MemoryError` ট্রেনিং এ                                | `train_legacy.py` এর `n_users=400` কে `200` করে দিন        |
| অ্যাপে এখনো heuristic দেখাচ্ছে                          | (a) `assets/risk_model.tflite` exist করে কিনা চেক করুন (b) `assembleDebug` rebuild করুন (c) ফোনে app uninstall + reinstall |
| `RuntimeError: Failed to allocate memory ...`          | অন্য কোন বড় app বন্ধ করুন; tensorflow-cpu সাধারণ ১৬ GB RAM মেশিনে চলে |

---

## 5. প্রোডাকশন বিল্ডের জন্য

বাস্তব ক্লিনিকাল ডাটা থাকলে:
```powershell
python train_legacy.py --data-csv path\to\real_data.csv
```
(currently `train_legacy.py` সিন্থেটিক ডাটা hard-coded; CSV support add করতে `synth()` কে `pd.read_csv(...)` দিয়ে replace করুন।)

CSV columns must match `LEGACY_FEATURES` list in the script + `phq9` label + `user_id`.

---

## 6. পরবর্তী ধাপ (Slice 5 — LSTM upgrade)

যখন আপনি LSTM-based model এ upgrade করতে চান:
1. `python train_model.py` চালান (LSTM 7×15 তৈরি)
2. `app/build.gradle.kts` এ যোগ করুন:
   ```kotlin
   implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.17.0")
   ```
3. `TFLiteRiskEngine.kt` এর `infer()` ফাংশন পরিবর্তন করুন `(1, 7, 15)` input তৈরি করতে — `DOCUMENTATION_BN.md` Section ১০.৪ এ recipe আছে।

---

> **সংক্ষেপ:** আজকের জন্য `python train_legacy.py` চালান — সেটাই এনাফ। ভবিষ্যতে LSTM swap করলে `train_model.py`-এ যাবেন।

