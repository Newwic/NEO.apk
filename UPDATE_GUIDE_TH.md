# วิธี Build และ Update NEO อัตโนมัติ

ไม่ต้องใช้ Android Studio บนคอมของคุณ

## ดาวน์โหลด APK
1. เข้า repository `Newwic/NEO.apk`
2. เปิดแท็บ **Actions**
3. เลือก **Build NEO APK**
4. เปิด run ล่าสุดที่เป็นสีเขียว
5. ที่หัวข้อ **Artifacts** ดาวน์โหลด `NEO-APK`
6. แตก ZIP แล้วจะได้ `NEO.apk`
7. ส่งเข้า OPPO แล้วกดติดตั้ง

## เวลาเราแก้ NEO ในแชตนี้
เมื่อมีการ commit เข้า `main` GitHub จะ Build APK ใหม่ให้อัตโนมัติ คุณเพียงดาวน์โหลด APK build ล่าสุดแล้วติดตั้งทับเวอร์ชันเดิม

## Live Reload
Prompt, ชื่อโมเดล, mode 3B/7B, temperature และ coding keywords สามารถแก้ผ่าน `config/neo-config.json` โดยไม่ต้องสร้าง APK ใหม่ เมื่อใช้ PC Worker สำหรับ Live Config

## Signing
ระบบ Auto Build ใช้ Android debug signing key ที่เก็บใน GitHub Actions cache เพื่อให้ build รุ่นถัดไปใช้ key เดิมโดยอัตโนมัติ โปรเจกต์นี้ยังเป็น development build ไม่ใช่ Production/Play Store signing
