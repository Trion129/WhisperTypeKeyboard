# Native libraries (not in git)

Download prebuilt Android libs from sherpa-onnx and place the `.so` files here:

```text
jniLibs/
  arm64-v8a/
  armeabi-v7a/
  x86/
  x86_64/
    libonnxruntime.so
    libsherpa-onnx-c-api.so
    libsherpa-onnx-cxx-api.so
    libsherpa-onnx-jni.so
```

Example (v1.13.4):

```bash
# download
curl -L -o sherpa-onnx-android.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-v1.13.4-android.tar.bz2

# extract and copy jniLibs/* into this directory
tar -xjf sherpa-onnx-android.tar.bz2
cp -r jniLibs/* app/src/main/jniLibs/
```

Windows PowerShell:

```powershell
Invoke-WebRequest -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-v1.13.4-android.tar.bz2" -OutFile sherpa-onnx-android.tar.bz2
tar -xjf sherpa-onnx-android.tar.bz2
Copy-Item -Recurse -Force jniLibs\* app\src\main\jniLibs\
```

See: https://github.com/k2-fsa/sherpa-onnx/releases
