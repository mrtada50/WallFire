# NetGuard Lite — المرحلة 1

تطبيق أندرويد يعرض التطبيقات المثبتة، يراقب استخدامها الحالي للإنترنت،
ويحضّر البنية لإضافة الحجب الفعلي عبر VPN بالمراحل القادمة.

## محتوى هذي المرحلة
- هيكل مشروع Gradle Kotlin DSL كامل.
- واجهة Jetpack Compose تعرض التطبيقات + مؤشر "نشط الآن".
- مفتاح تفعيل لكل تطبيق (يخزن بالذاكرة فقط حالياً، بدون تأثير فعلي بعد).
- GitHub Actions يبني APK تلقائياً عند كل push على main.

## كيفية الرفع من Termux

```bash
pkg install git -y
cd NetGuardLite   # داخل مجلد المشروع بعد فك الضغط
git init
git add .
git commit -m "Phase 1: project skeleton + UI + traffic monitor"
git branch -M main
git remote add origin https://github.com/USERNAME/REPO.git
git push -u origin main
```

بعد الـ push، روح لتبويب **Actions** بالمستودع على GitHub،
راح تلاقي workflow اسمه "Build APK" يشتغل تلقائياً.
لما يخلص (~3-5 دقائق)، افتح الـ run وحمّل الملف من قسم **Artifacts**
باسم `netguardlite-debug-apk`.

## المراحل القادمة
- **المرحلة 2:** خدمة VpnService تلتقط الحركة وتستخرج UID لكل اتصال (مراقبة فقط).
- **المرحلة 3:** حجب فعلي للتطبيقات غير المسموحة عبر relay حقيقي (TCP/UDP).
